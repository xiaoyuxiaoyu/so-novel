package com.pcdd.sonovel.repository;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpException;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.exception.RemoteBackendException;
import com.pcdd.sonovel.model.RemoteBackendConfig;
import com.pcdd.sonovel.util.WebReportLog;
import com.pcdd.sonovel.model.remote.RemoteBookInfo;
import com.pcdd.sonovel.model.remote.RemoteChapterPushItem;
import com.pcdd.sonovel.model.remote.RemoteClientMeta;
import com.pcdd.sonovel.model.remote.RemotePingResponse;
import com.pcdd.sonovel.model.remote.RemotePushRequest;
import com.pcdd.sonovel.model.remote.RemotePushResponse;
import com.pcdd.sonovel.model.remote.RemoteRejectedChapter;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * AI 小说后台 HTTP 客户端，封装三个接口：get_book_info / report_chapters / ping。
 * <p>
 * 约定：
 * <ul>
 *   <li>JSON 字段使用 snake_case，与 DTO 驼峰之间在本类内手动映射</li>
 *   <li>响应统一壳 {code,status,msg,data}；code=0 抛 {@link RemoteBackendException}</li>
 *   <li>5xx / IOException 重试 2 次（间隔 2s / 5s）；4xx 与 code=0 不重试</li>
 *   <li>mock 模式直接走 {@link MockRemoteBackend}，不发出 HTTP 请求</li>
 * </ul>
 * 参考：docs/网页端下载-AI小说后台对接接口文档.md v0.1
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@UtilityClass
public class RemoteBackendClient {

    static final String PATH_GET_BOOK_INFO = "/api/book-collector/web-downloader/get_book_info";
    static final String PATH_REPORT_CHAPTERS = "/api/book-collector/web-downloader/report_chapters";
    static final String PATH_PING = "/api/book-collector/web-downloader/ping";

    private static final String HEADER_API_KEY = "X-Agent-API-Key";
    private static final int MAX_RETRIES = 2;
    private static final long[] RETRY_BACKOFF_MS = {2000L, 5000L};

    /** 查书况：用于弹窗回显二次确认 + 取 latest_chapter_title 做反查 */
    public RemoteBookInfo getBookInfo(int bookId) {
        RemoteBackendConfig cfg = currentConfig();
        if (cfg.isMockMode()) {
            return MockRemoteBackend.getBookInfo(bookId);
        }
        JSONObject body = new JSONObject().set("book_id", bookId);
        JSONObject data = callWithRetry(cfg, PATH_GET_BOOK_INFO, body);
        return parseBookInfo(data);
    }

    /**
     * 上报增量章节：本次下载完成后整体回灌。
     * <p>
     * 内部按 {@link RemoteBackendConfig#getReportBatchSize()} 切分多个 batch 串行发送，
     * 避免一次性 body 过大触发 read-timeout。任意 batch 失败（重试 2 次仍失败）直接抛出，
     * 已上报 batch 由后端 upsert 保证幂等，重推按钮可安全续推。
     * <p>
     * 每个 batch 发送前通过 {@code progress} 回调汇报阶段，便于 SSE 推送进度。
     * 回调可为 null。
     */
    public RemotePushResponse reportChapters(RemotePushRequest req, BatchProgressListener progress) {
        RemoteBackendConfig cfg = currentConfig();
        if (cfg.isMockMode()) {
            return MockRemoteBackend.reportChapters(req);
        }
        List<RemoteChapterPushItem> all = req.getChapters() == null ? List.of() : req.getChapters();
        int batchSize = cfg.getReportBatchSize() == null || cfg.getReportBatchSize() < 1
                ? 100 : cfg.getReportBatchSize();
        int total = all.size();
        int totalBatches = total == 0 ? 0 : (total + batchSize - 1) / batchSize;
        WebReportLog.info("reportChapters begin: bookId={}, totalChapters={}, batchSize={}, totalBatches={}",
                req.getBookId(), total, batchSize, totalBatches);

        int acceptedSum = 0;
        int updatedSum = 0;
        List<RemoteRejectedChapter> rejectedAll = new ArrayList<>();
        for (int i = 0; i < totalBatches; i++) {
            int from = i * batchSize;
            int to = Math.min(from + batchSize, total);
            List<RemoteChapterPushItem> sub = all.subList(from, to);
            int batchIdx = i + 1;
            int firstChapterNo = sub.get(0).getChapterNo();
            int lastChapterNo = sub.get(sub.size() - 1).getChapterNo();
            if (progress != null) {
                progress.onBatchStart(batchIdx, totalBatches, sub.size());
            }
            WebReportLog.info("batch {}/{} POST size={} chapter_no=[{}..{}]",
                    batchIdx, totalBatches, sub.size(), firstChapterNo, lastChapterNo);
            RemotePushRequest batchReq = RemotePushRequest.builder()
                    .bookId(req.getBookId())
                    .chapters(sub)
                    .clientMeta(req.getClientMeta())
                    .build();
            JSONObject body = serializePushRequest(batchReq);
            long started = System.currentTimeMillis();
            JSONObject data;
            try {
                data = callWithRetry(cfg, PATH_REPORT_CHAPTERS, body);
            } catch (RuntimeException e) {
                long cost = System.currentTimeMillis() - started;
                WebReportLog.error(e, "batch {}/{} failed after {}ms, abort. Already pushed batches=0..{} are kept on backend (upsert).",
                        batchIdx, totalBatches, cost, batchIdx - 1);
                throw e;
            }
            long cost = System.currentTimeMillis() - started;
            RemotePushResponse resp = parsePushResponse(data);
            WebReportLog.info("batch {}/{} done in {}ms: accepted={}, updated={}, rejected={}",
                    batchIdx, totalBatches, cost,
                    resp.getAcceptedCount(), resp.getUpdatedCount(),
                    resp.getRejected() == null ? 0 : resp.getRejected().size());
            acceptedSum += resp.getAcceptedCount();
            updatedSum += resp.getUpdatedCount();
            if (resp.getRejected() != null) rejectedAll.addAll(resp.getRejected());
        }
        WebReportLog.info("reportChapters done: bookId={}, accepted_total={}, updated_total={}, rejected_total={}",
                req.getBookId(), acceptedSum, updatedSum, rejectedAll.size());
        return RemotePushResponse.builder()
                .acceptedCount(acceptedSum)
                .updatedCount(updatedSum)
                .rejected(rejectedAll)
                .build();
    }

    /** 兼容旧调用方：等价于 reportChapters(req, null) */
    public RemotePushResponse reportChapters(RemotePushRequest req) {
        return reportChapters(req, null);
    }

    /** batch 进度回调接口。 */
    @FunctionalInterface
    public interface BatchProgressListener {
        void onBatchStart(int batchIndex, int totalBatches, int batchSize);
    }

    /** 健康检查 */
    public RemotePingResponse ping() {
        RemoteBackendConfig cfg = currentConfig();
        if (cfg.isMockMode()) {
            return MockRemoteBackend.ping();
        }
        JSONObject data = callWithRetry(cfg, PATH_PING, new JSONObject());
        return RemotePingResponse.builder()
                .serverTime(data.getStr("server_time", ""))
                .apiVersion(data.getStr("api_version", ""))
                .build();
    }

    // ---------- HTTP 调用 + 重试 ----------

    private JSONObject callWithRetry(RemoteBackendConfig cfg, String path, JSONObject body) {
        Supplier<JSONObject> attempt = () -> callOnce(cfg, path, body);

        int attempts = 0;
        Exception lastRetryable = null;
        while (attempts <= MAX_RETRIES) {
            try {
                return attempt.get();
            } catch (RemoteBackendException e) {
                // 业务失败（code=0）/ 4xx：不重试，直接抛
                throw e;
            } catch (RuntimeException e) {
                // HttpException / 网络异常 / 5xx：重试（RemoteBackendException 已在上面拦截）
                lastRetryable = e;
                WebReportLog.warn("HTTP attempt #{} on {} failed: {}: {}",
                        attempts + 1, path, e.getClass().getSimpleName(), e.getMessage());
                if (attempts >= MAX_RETRIES) {
                    break;
                }
                WebReportLog.info("sleep {}ms before retry #{}", RETRY_BACKOFF_MS[attempts], attempts + 2);
                sleepQuiet(RETRY_BACKOFF_MS[attempts]);
                attempts++;
            }
        }
        // 把根因带出来，避免 UI 看到笼统的 "连接失败" 不知道原因
        String detail = lastRetryable == null
                ? ""
                : "：" + lastRetryable.getClass().getSimpleName()
                + (lastRetryable.getMessage() == null ? "" : ": " + lastRetryable.getMessage());
        throw new RemoteBackendException(
                "AI 后台连接失败，已重试 " + MAX_RETRIES + " 次" + detail,
                lastRetryable);
    }

    private JSONObject callOnce(RemoteBackendConfig cfg, String path, JSONObject body) {
        String url = stripTrailingSlash(cfg.getBaseUrl()) + path;
        HttpResponse resp = null;
        try {
            resp = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .header(HEADER_API_KEY, cfg.getApiKey())
                    .setConnectionTimeout(cfg.getConnectTimeoutMs())
                    .setReadTimeout(cfg.getReadTimeoutMs())
                    .body(body.toString())
                    .execute();

            int status = resp.getStatus();
            String raw = resp.body();
            if (status >= 500) {
                // 触发上层重试
                WebReportLog.warn("POST {} → HTTP {}, body: {}", path, status, raw);
                throw new HttpException("AI 后台返回 " + status + ": " + StrUtil.maxLength(raw, 200));
            }
            JSONObject json = parseJsonSafe(raw);
            int code = json.getInt("code", 0);
            if (code != 1) {
                // 业务失败 / 鉴权失败 / 4xx 都从这里抛；完整原始 body 写日志便于排查
                WebReportLog.warn("POST {} → HTTP {}, code != 1, full body: {}", path, status, raw);
                String msg = json.getStr("msg", "AI 后台返回未知错误");
                throw new RemoteBackendException(msg + " [HTTP " + status + ", body=" + StrUtil.maxLength(raw, 300) + "]");
            }
            return json.getJSONObject("data") == null ? new JSONObject() : json.getJSONObject("data");
        } finally {
            if (resp != null) {
                try {
                    resp.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ---------- snake_case 映射（package-private 便于单测直接覆盖解析逻辑） ----------

    RemoteBookInfo parseBookInfo(JSONObject data) {
        return RemoteBookInfo.builder()
                .bookId(data.getInt("book_id"))
                .bookName(data.getStr("book_name", ""))
                .author(data.getStr("author", ""))
                .latestChapterNo(data.getInt("latest_chapter_no", 0))
                .latestChapterTitle(data.getStr("latest_chapter_title", ""))
                .updatedAt(data.getStr("updated_at", ""))
                .build();
    }

    JSONObject serializePushRequest(RemotePushRequest req) {
        JSONObject out = new JSONObject();
        out.set("book_id", req.getBookId());

        JSONArray arr = new JSONArray();
        for (RemoteChapterPushItem ch : req.getChapters()) {
            JSONObject item = new JSONObject();
            item.set("chapter_no", ch.getChapterNo());
            item.set("title", ch.getTitle());
            item.set("content", ch.getContent());
            arr.add(item);
        }
        out.set("chapters", arr);

        RemoteClientMeta meta = req.getClientMeta();
        JSONObject metaJson = new JSONObject();
        metaJson.set("source_name", meta.getSourceName());
        metaJson.set("source_url", meta.getSourceUrl());
        metaJson.set("app_version", meta.getAppVersion());
        out.set("client_meta", metaJson);
        return out;
    }

    RemotePushResponse parsePushResponse(JSONObject data) {
        List<RemoteRejectedChapter> rejected = new ArrayList<>();
        JSONArray arr = data.getJSONArray("rejected");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                rejected.add(RemoteRejectedChapter.builder()
                        .chapterNo(item.getInt("chapter_no"))
                        .title(item.getStr("title", ""))
                        .reason(item.getStr("reason", ""))
                        .build());
            }
        }
        return RemotePushResponse.builder()
                .acceptedCount(data.getInt("accepted_count", 0))
                .updatedCount(data.getInt("updated_count", 0))
                .rejected(rejected)
                .build();
    }

    // ---------- helpers ----------

    private RemoteBackendConfig currentConfig() {
        RemoteBackendConfig c = AppConfigLoader.APP_CONFIG.getRemoteBackend();
        if (c == null) {
            throw new RemoteBackendException("[remote-backend] 配置未加载");
        }
        return c;
    }

    private String stripTrailingSlash(String s) {
        if (StrUtil.isBlank(s)) {
            return "";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private JSONObject parseJsonSafe(String raw) {
        if (StrUtil.isBlank(raw)) {
            throw new RemoteBackendException("AI 后台返回体为空");
        }
        try {
            return JSONUtil.parseObj(raw);
        } catch (Exception e) {
            throw new RemoteBackendException("AI 后台返回非 JSON：" + StrUtil.maxLength(raw, 200), e);
        }
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
