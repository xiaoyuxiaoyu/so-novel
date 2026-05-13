package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.Crawler;
import com.pcdd.sonovel.core.IncrementalAnchorResolver;
import com.pcdd.sonovel.core.ReportCollector;
import com.pcdd.sonovel.exception.RemoteBackendException;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.LocalTaskState;
import com.pcdd.sonovel.model.RemoteBackendConfig;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 网页端"主动下载并回推 AI 后台"主体 Servlet。
 *
 * <p>请求样例：
 * <pre>GET /incremental-download?bookId=123&url=https://...&format=epub
 *     &language=zh_cn&concurrency=10&startOrder=可选&confirm=可选
 * </pre>
 *
 * <p>串联流程（沿用既有 /book-fetch 模式：fetch 同步挂连接、进度走 /download-progress SSE）：
 * <ol>
 *   <li>校验入参 + 调 {@link RemoteBackendClient#getBookInfo(int)} 拿后台书况</li>
 *   <li>拉源站完整 toc</li>
 *   <li>若入参 startOrder 非空 → 从该处下；否则用
 *       {@link IncrementalAnchorResolver#resolve} 反查 latest_chapter_title</li>
 *   <li>未命中 → 返 {@code data.needAnchor=true} + toc，让前端切到锚点选择</li>
 *   <li>体量 &gt; reject 阈值 → 直接拒；&gt; warn 阈值且未 confirm → 返
 *       {@code data.needConfirm=true} 让前端二次确认</li>
 *   <li>正常分支：Crawler 同步下载 + {@link ReportCollector} 收集纯文本副本</li>
 *   <li>构造回推体（chapter_no 从 {@code latest_chapter_no + 1} 起递增重写），
 *       调 {@link RemoteBackendClient#reportChapters}</li>
 *   <li>回推前后通过 SSE {@code type=report-progress} 事件推送阶段</li>
 *   <li>HTTP 响应汇总 {accepted/updated/rejected/taskId}</li>
 * </ol>
 *
 * <p>本期不持久化 task 状态（{@code .so-novel/tasks.json} 由 M4 引入）。
 * 回推失败时 reports 副本已在 {@code .so-novel/reports/{taskId}/} 落盘，待 M4
 * RepushServlet 接管重推。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class IncrementalDownloadServlet extends HttpServlet {

    private static final Set<String> ALLOWED_FORMATS = Set.of("epub", "txt", "html", "pdf");
    private static final Set<String> ALLOWED_LANGUAGES = Set.of("zh_cn", "zh_tw", "zh_hant");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        // ---- 1. 解析 + 基础校验 ----
        String bookIdStr = req.getParameter("bookId");
        String bookUrl = req.getParameter("url");
        String format = req.getParameter("format");
        String language = req.getParameter("language");
        String concurrencyStr = req.getParameter("concurrency");
        String startOrderStr = req.getParameter("startOrder");
        boolean userConfirmed = "true".equalsIgnoreCase(req.getParameter("confirm"));

        Integer bookId = parsePositiveInt(bookIdStr);
        if (bookId == null) {
            RespUtils.writeError(resp, 400, "bookId 必须为正整数");
            return;
        }
        if (StrUtil.isBlank(bookUrl)) {
            RespUtils.writeError(resp, 400, "url 不能为空");
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
        if (StrUtil.isNotBlank(format) && !ALLOWED_FORMATS.contains(format.toLowerCase())) {
            RespUtils.writeError(resp, 400, "不支持的下载格式: " + format);
            return;
        }
        if (StrUtil.isNotBlank(language) && !ALLOWED_LANGUAGES.contains(language.toLowerCase())) {
            RespUtils.writeError(resp, 400, "不支持的语言: " + language);
            return;
        }
        Integer concurrency = null;
        if (StrUtil.isNotBlank(concurrencyStr)) {
            try {
                concurrency = Integer.parseInt(concurrencyStr.trim());
            } catch (NumberFormatException e) {
                RespUtils.writeError(resp, 400, "concurrency 必须为整数");
                return;
            }
        }
        Integer startOrder = null;
        if (StrUtil.isNotBlank(startOrderStr)) {
            startOrder = parsePositiveInt(startOrderStr);
            if (startOrder == null) {
                RespUtils.writeError(resp, 400, "startOrder 必须为正整数");
                return;
            }
        }

        try {
            // ---- 2. 查 AI 后台书况 ----
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
            if (StrUtil.isNotBlank(format)) cfg.setExtName(format.toLowerCase());
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

            // ---- 4. 计算下载起点 ----
            int from;
            if (startOrder != null) {
                // 未命中分支：用户已经在前端选了锚点 idx'，下载从 idx'+1 开始
                from = startOrder - 1;
                if (from > fullToc.size()) {
                    from = fullToc.size();
                }
            } else {
                Optional<Integer> idx = IncrementalAnchorResolver.resolve(fullToc, bookInfo.getLatestChapterTitle());
                if (idx.isEmpty()) {
                    // 未命中 → 返完整 toc 让前端选锚点
                    RespUtils.writeJson(resp, buildNeedAnchorResp(fullToc, bookInfo));
                    return;
                }
                from = idx.get() + 1;
            }

            // ---- 5. 无新增章节 ----
            if (from >= fullToc.size()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("noNewChapters", true);
                data.put("message", "后台已是最新，无新增章节");
                RespUtils.writeJson(resp, data);
                return;
            }

            List<Chapter> subToc = new ArrayList<>(fullToc.subList(from, fullToc.size()));

            // ---- 6. 体量校验 ----
            RemoteBackendConfig rb = AppConfigLoader.APP_CONFIG.getRemoteBackend();
            int rejectThreshold = rb.getPushBatchRejectThreshold();
            int warnThreshold = rb.getPushBatchWarnThreshold();
            if (subToc.size() > rejectThreshold) {
                RespUtils.writeError(resp, 413, "本次增量 " + subToc.size()
                        + " 章超过最大上限 " + rejectThreshold + "，请先在后台对齐再下");
                return;
            }
            if (subToc.size() > warnThreshold && !userConfirmed) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("needConfirm", true);
                data.put("incrementCount", subToc.size());
                data.put("warnThreshold", warnThreshold);
                data.put("message", "增量过大 (" + subToc.size() + " 章)，建议先在后台对齐再下");
                RespUtils.writeJson(resp, data);
                return;
            }

            // ---- 7. 下载 + 收集纯文本副本 ----
            String taskId = UUID.randomUUID().toString();
            String workDir = System.getProperty("user.dir");
            ReportCollector collector = new ReportCollector(taskId, workDir);

            new Crawler(cfg).crawl(bookUrl, subToc, collector);

            // ---- 8. 构造回推请求 + 持久化 DOWNLOADED_NOT_PUSHED 状态 ----
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

            // ---- 9. 回推 + SSE 进度推送 + 状态收敛 ----
            DownloadProgressSseServlet.sendProgress(JSONUtil.toJsonStr(Map.of(
                    "type", "report-progress",
                    "phase", "reporting",
                    "count", chapters.size()
            )));

            RemotePushResponse pushResp;
            try {
                pushResp = RemoteBackendClient.reportChapters(pushReq);
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

    /** 未命中分支响应体：含完整 toc（剥 url）+ 后台 latest 信息，前端据此渲染锚点选择 UI。 */
    private static Map<String, Object> buildNeedAnchorResp(List<Chapter> fullToc, RemoteBookInfo bookInfo) {
        List<Map<String, Object>> items = fullToc.stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>(2);
                    m.put("order", c.getOrder());
                    m.put("title", c.getTitle());
                    return m;
                })
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("needAnchor", true);
        data.put("latestTitle", bookInfo.getLatestChapterTitle());
        data.put("latestChapterNo", bookInfo.getLatestChapterNo());
        data.put("tocSize", items.size());
        data.put("toc", items);
        return data;
    }

    /**
     * 把 {@link ReportCollector#snapshot()} 输出的章节列表按"AI 后台 DB 目标序号"重写
     * chapter_no：依次赋值 {@code startChapterNo, startChapterNo+1, ...}。
     * <p>
     * 仅追加场景（方案 v0.2 修订 ①），不处理覆盖错章；保留 title / content 原样。
     * package-private 便于单测直接覆盖。
     */
    /**
     * 把 {@link com.pcdd.sonovel.core.ReportCollector#snapshot()} 输出（chapterNo 此时是
     * 源站 order 占位）转为 {@link LocalTaskState.ChapterRef} 列表：
     * 同时填入"源站 order"（reports 副本文件名用此）与"AI 后台目标 chapter_no"。
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
