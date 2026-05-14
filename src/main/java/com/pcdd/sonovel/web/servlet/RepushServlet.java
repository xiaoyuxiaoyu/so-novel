package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.exception.RemoteBackendException;
import com.pcdd.sonovel.model.LocalTaskState;
import com.pcdd.sonovel.model.remote.RemoteChapterPushItem;
import com.pcdd.sonovel.model.remote.RemoteClientMeta;
import com.pcdd.sonovel.model.remote.RemotePushRequest;
import com.pcdd.sonovel.model.remote.RemotePushResponse;
import com.pcdd.sonovel.repository.RemoteBackendClient;
import com.pcdd.sonovel.repository.TaskStateRepository;
import com.pcdd.sonovel.util.WebReportLog;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 重新上报"未上报 / 部分失败 / 上报失败"状态的任务。
 *
 * <pre>GET /repush?taskId=xxx</pre>
 *
 * 流程：
 * <ol>
 *   <li>tasks.json 加载任务状态；已 PUSHED → 直接返回 {@code alreadyPushed}</li>
 *   <li>从 {@code .so-novel/reports/{taskId}/{sourceOrder}.txt} 读章节正文，
 *       结合 ChapterRef 重建 RemotePushRequest</li>
 *   <li>调 reportChapters；按响应更新 task 状态（PUSHED / PARTIAL / FAILED）</li>
 * </ol>
 * 本期不分"只重发 rejected 章节"，整批重发 —— 后台按 {@code (book_id, chapter_no)}
 * upsert 已 accepted 的不会被破坏。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class RepushServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        String taskId = req.getParameter("taskId");
        if (StrUtil.isBlank(taskId)) {
            RespUtils.writeError(resp, 400, "taskId 不能为空");
            return;
        }

        String workDir = System.getProperty("user.dir");
        TaskStateRepository repo = new TaskStateRepository(workDir);

        Optional<LocalTaskState> opt = repo.get(taskId);
        if (opt.isEmpty()) {
            RespUtils.writeError(resp, 404, "未找到任务: " + taskId);
            return;
        }
        LocalTaskState state = opt.get();

        if (state.getStatus() == LocalTaskState.Status.PUSHED) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", taskId);
            data.put("alreadyPushed", true);
            data.put("message", "该任务已成功上报，无需重推");
            RespUtils.writeJson(resp, data);
            return;
        }

        File reportsDir = new File(workDir
                + File.separator + ".so-novel"
                + File.separator + "reports"
                + File.separator + taskId);
        if (!reportsDir.exists() || !reportsDir.isDirectory()) {
            RespUtils.writeError(resp, 410, "本地章节副本目录已丢失，无法重推");
            return;
        }

        List<LocalTaskState.ChapterRef> refs = state.getChapters();
        if (refs == null || refs.isEmpty()) {
            RespUtils.writeError(resp, 410, "任务记录里没有章节信息");
            return;
        }

        List<RemoteChapterPushItem> chapters = new ArrayList<>(refs.size());
        for (LocalTaskState.ChapterRef ref : refs) {
            File f = new File(reportsDir, ref.getSourceOrder() + ".txt");
            if (!f.exists()) {
                RespUtils.writeError(resp, 410,
                        "章节副本缺失: " + ref.getSourceOrder() + ".txt（reports 目录已被清理？）");
                return;
            }
            String content = FileUtil.readUtf8String(f);
            chapters.add(RemoteChapterPushItem.builder()
                    .chapterNo(ref.getChapterNo())
                    .title(ref.getTitle())
                    .content(content)
                    .build());
        }

        RemoteClientMeta meta = RemoteClientMeta.builder()
                .sourceName(state.getSourceName())
                .sourceUrl(state.getBookUrl())
                .appVersion(AppConfigLoader.APP_CONFIG.getVersion())
                .build();

        RemotePushRequest pushReq = RemotePushRequest.builder()
                .bookId(state.getBookId())
                .chapters(chapters)
                .clientMeta(meta)
                .build();

        WebReportLog.setTask(taskId);
        try {
            WebReportLog.info("repush begin: bookId={}, sourceName={}, chapters={}",
                    state.getBookId(), state.getSourceName(), chapters.size());
            DownloadProgressSseServlet.sendProgress(JSONUtil.toJsonStr(Map.of(
                    "type", "report-progress",
                    "phase", "reporting",
                    "count", chapters.size()
            )));

            RemotePushResponse pushResp;
            try {
                pushResp = RemoteBackendClient.reportChapters(pushReq);
            } catch (RemoteBackendException e) {
                WebReportLog.error(e, "repush failed");
                repo.markFailed(taskId, e.getMessage());
                DownloadProgressSseServlet.sendProgress(JSONUtil.toJsonStr(Map.of(
                        "type", "report-progress",
                        "phase", "failed",
                        "msg", e.getMessage()
                )));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", taskId);
                data.put("pushed", false);
                data.put("errorMessage", e.getMessage());
                RespUtils.writeJson(resp, data);
                return;
            } catch (Exception e) {
                WebReportLog.error(e, "repush failed (non-remote)");
                repo.markFailed(taskId, e.getMessage());
                RespUtils.writeError(resp, 500, "重推失败: " + e.getMessage());
                return;
            }

            boolean hasRejected = pushResp.getRejected() != null && !pushResp.getRejected().isEmpty();
            if (hasRejected) {
                repo.markPartial(taskId, pushResp.getRejected());
            } else {
                repo.markPushed(taskId);
            }

            DownloadProgressSseServlet.sendProgress(JSONUtil.toJsonStr(Map.of(
                    "type", "report-progress",
                    "phase", "done",
                    "accepted", pushResp.getAcceptedCount(),
                    "updated", pushResp.getUpdatedCount(),
                    "rejected", pushResp.getRejected() == null ? 0 : pushResp.getRejected().size()
            )));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", taskId);
            data.put("pushed", true);
            data.put("accepted", pushResp.getAcceptedCount());
            data.put("updated", pushResp.getUpdatedCount());
            data.put("rejected", pushResp.getRejected());
            RespUtils.writeJson(resp, data);
        } finally {
            WebReportLog.clearTask();
        }
    }
}
