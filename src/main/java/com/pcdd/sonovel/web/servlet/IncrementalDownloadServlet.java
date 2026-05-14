package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.Crawler;
import com.pcdd.sonovel.core.ReportCollector;
import com.pcdd.sonovel.exception.RemoteBackendException;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.LocalTaskState;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.model.remote.RemoteBookInfo;
import com.pcdd.sonovel.model.remote.RemoteChapterPushItem;
import com.pcdd.sonovel.model.remote.RemoteClientMeta;
import com.pcdd.sonovel.model.remote.RemotePushRequest;
import com.pcdd.sonovel.model.remote.RemotePushResponse;
import com.pcdd.sonovel.parse.TocParser;
import com.pcdd.sonovel.repository.RemoteBackendClient;
import com.pcdd.sonovel.repository.TaskStateRepository;
import com.pcdd.sonovel.util.SourceUtils;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 网页端"主动下载并回推 AI 后台"主体 Servlet（v2 重构）。
 *
 * <p>请求样例：
 * <pre>GET /incremental-download?bookId=123&url=https://...
 *     &startOrder=13&endOrder=512
 *     &language=zh_cn&concurrency=10
 * </pre>
 *
 * <p>v2 流程（配合 {@link TocPreviewServlet} 单步预览页）：
 * <ol>
 *   <li>校验入参（startOrder/endOrder 必填；(end-start+1) ≤ {@link TocPreviewServlet#MAX_BATCH}）</li>
 *   <li>查 AI 后台书况，仅取 {@code latest_chapter_no} 用于回推章节号续接</li>
 *   <li>拉源站完整 toc，切片 [startOrder, endOrder]</li>
 *   <li>Crawler 同步下载 + {@link ReportCollector} 收集纯文本副本</li>
 *   <li>构造回推体（chapter_no 从 {@code latest_chapter_no + 1} 起递增重写），
 *       调 {@link RemoteBackendClient#reportChapters} 分批回推</li>
 *   <li>每批回推前通过 SSE 推送进度</li>
 *   <li>HTTP 响应汇总 {accepted/updated/rejected/taskId}</li>
 * </ol>
 *
 * <p>不再返回 needAnchor / needConfirm —— 锚点匹配与体量提示都在
 * {@link TocPreviewServlet} 已经一次性给到前端，前端确认后直接打到本接口。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class IncrementalDownloadServlet extends HttpServlet {

    private static final java.util.Set<String> ALLOWED_LANGUAGES = java.util.Set.of("zh_cn", "zh_tw", "zh_hant");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        // ---- 1. 解析 + 基础校验 ----
        Integer bookId = parsePositiveInt(req.getParameter("bookId"));
        if (bookId == null) {
            RespUtils.writeError(resp, 400, "bookId 必须为正整数");
            return;
        }
        String bookUrl = req.getParameter("url");
        if (StrUtil.isBlank(bookUrl)) {
            RespUtils.writeError(resp, 400, "url 不能为空");
            return;
        }
        Integer startOrder = parsePositiveInt(req.getParameter("startOrder"));
        if (startOrder == null) {
            RespUtils.writeError(resp, 400, "startOrder 必须为正整数");
            return;
        }
        Integer endOrder = parsePositiveInt(req.getParameter("endOrder"));
        if (endOrder == null) {
            RespUtils.writeError(resp, 400, "endOrder 必须为正整数");
            return;
        }
        if (endOrder < startOrder) {
            RespUtils.writeError(resp, 400, "endOrder 必须 >= startOrder");
            return;
        }
        int requested = endOrder - startOrder + 1;
        if (requested > TocPreviewServlet.MAX_BATCH) {
            RespUtils.writeError(resp, 400,
                    "单次下载上限 " + TocPreviewServlet.MAX_BATCH + " 章，当前 " + requested + " 章超限");
            return;
        }

        Rule rule;
        try {
            rule = SourceUtils.getRule(bookUrl);
        } catch (IllegalArgumentException e) {
            RespUtils.writeError(resp, 400, "不支持的源站 URL");
            return;
        }
        if (rule == null) {
            RespUtils.writeError(resp, 400, "不支持的源站 URL");
            return;
        }

        String language = req.getParameter("language");
        if (StrUtil.isNotBlank(language) && !ALLOWED_LANGUAGES.contains(language.toLowerCase())) {
            RespUtils.writeError(resp, 400, "不支持的语言: " + language);
            return;
        }
        Integer concurrency = null;
        String concurrencyStr = req.getParameter("concurrency");
        if (StrUtil.isNotBlank(concurrencyStr)) {
            try {
                concurrency = Integer.parseInt(concurrencyStr.trim());
            } catch (NumberFormatException e) {
                RespUtils.writeError(resp, 400, "concurrency 必须为整数");
                return;
            }
        }

        try {
            // ---- 2. 查 AI 后台书况（取 latest_chapter_no 用于续号） ----
            RemoteBookInfo bookInfo;
            try {
                bookInfo = RemoteBackendClient.getBookInfo(bookId);
            } catch (RemoteBackendException e) {
                RespUtils.writeError(resp, 404, "未在后台找到该书：" + e.getMessage());
                return;
            }

            // ---- 3. 准备 AppConfig + 拉源站 toc ----
            AppConfig cfg = BeanUtil.copyProperties(AppConfigLoader.APP_CONFIG, AppConfig.class);
            cfg.setSourceId(rule.getId());
            if (StrUtil.isNotBlank(language)) cfg.setLanguage(language);
            if (concurrency != null) cfg.setConcurrency(concurrency);

            List<Chapter> fullToc;
            try {
                fullToc = new TocParser(cfg).parseAll(bookUrl);
            } catch (Exception e) {
                RespUtils.writeError(resp, 502, "源站目录抓取失败：" + e.getMessage());
                return;
            }
            if (fullToc == null || fullToc.isEmpty()) {
                RespUtils.writeError(resp, 502, "源站目录为空");
                return;
            }
            if (endOrder > fullToc.size()) {
                RespUtils.writeError(resp, 400,
                        "endOrder=" + endOrder + " 超出源站章节总数 " + fullToc.size());
                return;
            }

            // ---- 4. 切片 [startOrder, endOrder] ----
            List<Chapter> subToc = new ArrayList<>(fullToc.subList(startOrder - 1, endOrder));

            // ---- 5. 下载 + 收集纯文本副本 ----
            String taskId = UUID.randomUUID().toString();
            String workDir = System.getProperty("user.dir");
            ReportCollector collector = new ReportCollector(taskId, workDir);

            new Crawler(cfg).crawl(bookUrl, subToc, collector);

            // ---- 6. 构造回推请求 + 持久化 DOWNLOADED_NOT_PUSHED ----
            List<RemoteChapterPushItem> rawSnapshot = collector.snapshot();
            int kPlus1 = bookInfo.getLatestChapterNo() + 1;
            List<RemoteChapterPushItem> chapters = renumberForReport(rawSnapshot, kPlus1);

            TaskStateRepository taskRepo = new TaskStateRepository(workDir);
            taskRepo.save(LocalTaskState.builder()
                    .taskId(taskId)
                    .bookId(bookId)
                    .sourceId(rule.getId())
                    .sourceName(rule.getName())
                    .bookUrl(bookUrl)
                    .chapters(buildChapterRefs(rawSnapshot, kPlus1))
                    .status(LocalTaskState.Status.DOWNLOADED_NOT_PUSHED)
                    .build());

            RemoteClientMeta meta = RemoteClientMeta.builder()
                    .sourceName(rule.getName())
                    .sourceUrl(bookUrl)
                    .appVersion(AppConfigLoader.APP_CONFIG.getVersion())
                    .build();

            RemotePushRequest pushReq = RemotePushRequest.builder()
                    .bookId(bookId)
                    .chapters(chapters)
                    .clientMeta(meta)
                    .build();

            // ---- 7. 分批回推 + SSE 进度 ----
            DownloadProgressSseServlet.sendProgress(JSONUtil.toJsonStr(Map.of(
                    "type", "report-progress",
                    "phase", "reporting",
                    "count", chapters.size()
            )));

            RemotePushResponse pushResp;
            try {
                pushResp = RemoteBackendClient.reportChapters(pushReq, (batchIdx, totalBatches, batchSize) -> {
                    DownloadProgressSseServlet.sendProgress(JSONUtil.toJsonStr(Map.of(
                            "type", "report-progress",
                            "phase", "batch",
                            "batch", batchIdx,
                            "totalBatches", totalBatches,
                            "batchSize", batchSize
                    )));
                });
            } catch (RemoteBackendException e) {
                taskRepo.markFailed(taskId, e.getMessage());
                DownloadProgressSseServlet.sendProgress(JSONUtil.toJsonStr(Map.of(
                        "type", "report-progress",
                        "phase", "failed",
                        "msg", e.getMessage()
                )));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", taskId);
                data.put("downloaded", true);
                data.put("pushed", false);
                data.put("reportFailed", true);
                data.put("errorMessage", e.getMessage());
                RespUtils.writeJson(resp, data);
                return;
            }

            boolean hasRejected = pushResp.getRejected() != null && !pushResp.getRejected().isEmpty();
            if (hasRejected) {
                taskRepo.markPartial(taskId, pushResp.getRejected());
            } else {
                taskRepo.markPushed(taskId);
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
            data.put("downloaded", true);
            data.put("pushed", true);
            data.put("accepted", pushResp.getAcceptedCount());
            data.put("updated", pushResp.getUpdatedCount());
            data.put("rejected", pushResp.getRejected());
            RespUtils.writeJson(resp, data);
        } catch (NumberFormatException e) {
            RespUtils.writeError(resp, 400, "参数格式错误: " + e.getMessage());
        } catch (Exception e) {
            RespUtils.writeError(resp, 500, "下载失败: " + e.getMessage());
        }
    }

    /**
     * 把 {@link ReportCollector#snapshot()} 输出（chapterNo 此时是源站 order 占位）转为
     * {@link LocalTaskState.ChapterRef} 列表：同时填入"源站 order"（reports 副本文件名用此）
     * 与"AI 后台目标 chapter_no"。
     */
    static List<LocalTaskState.ChapterRef> buildChapterRefs(List<RemoteChapterPushItem> rawSnapshot, int startChapterNo) {
        if (rawSnapshot == null || rawSnapshot.isEmpty()) {
            return List.of();
        }
        List<LocalTaskState.ChapterRef> out = new ArrayList<>(rawSnapshot.size());
        for (int i = 0; i < rawSnapshot.size(); i++) {
            RemoteChapterPushItem c = rawSnapshot.get(i);
            out.add(LocalTaskState.ChapterRef.builder()
                    .sourceOrder(c.getChapterNo())   // 占位的是源站 order
                    .chapterNo(startChapterNo + i)
                    .title(c.getTitle())
                    .build());
        }
        return out;
    }

    /**
     * 把 {@link ReportCollector#snapshot()} 输出的章节列表按"AI 后台 DB 目标序号"重写
     * chapter_no：依次赋值 {@code startChapterNo, startChapterNo+1, ...}。
     * 仅追加场景（方案 v0.2 修订 ①），title/content 原样保留。
     */
    static List<RemoteChapterPushItem> renumberForReport(List<RemoteChapterPushItem> raw, int startChapterNo) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<RemoteChapterPushItem> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            RemoteChapterPushItem c = raw.get(i);
            out.add(RemoteChapterPushItem.builder()
                    .chapterNo(startChapterNo + i)
                    .title(c.getTitle())
                    .content(c.getContent())
                    .build());
        }
        return out;
    }

    private static Integer parsePositiveInt(String raw) {
        if (StrUtil.isBlank(raw)) return null;
        try {
            int v = Integer.parseInt(raw.trim());
            return v >= 1 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
