package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.IncrementalAnchorResolver;
import com.pcdd.sonovel.exception.RemoteBackendException;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.RemoteBackendConfig;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.model.remote.RemoteBookInfo;
import com.pcdd.sonovel.parse.TocParser;
import com.pcdd.sonovel.repository.RemoteBackendClient;
import com.pcdd.sonovel.util.SourceUtils;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 网页端弹窗预览页"一把抓"接口：查后台书况 + 抓源站 toc + 锚点匹配 + 默认下载范围。
 *
 * <pre>GET /toc-preview?bookId=123&url=https://...</pre>
 *
 * 返回结构（成功）：
 * <pre>
 * data: {
 *   bookInfo: { bookName, author, latestChapterNo, latestChapterTitle },
 *   toc: [ { order, title, url } ... ],
 *   matchedIndex: 11 | null,    // 0-based，源站 toc 中匹配后台最后章节标题的下标
 *   defaultStartOrder: 13,      // 1-based，默认下载起点
 *   defaultEndOrder: 512,       // 1-based，默认下载终点 (= min(start+maxBatch-1, toc.size()))
 *   maxBatch: 500
 * }
 * </pre>
 *
 * 取代旧的 /remote-book-info + /source-toc 二次调用模式：弹窗仅一次 GET 拿全。
 *
 * @author 石宇涛
 * Created at 2026/5/14
 */
public class TocPreviewServlet extends HttpServlet {

    /** 单次下载/上报硬上限（章数）。前端、IncrementalDownloadServlet 都按此判定。 */
    public static final int MAX_BATCH = 500;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        String bookIdStr = req.getParameter("bookId");
        String bookUrl = req.getParameter("url");

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

        // 1) 查后台书况
        RemoteBookInfo bookInfo;
        try {
            bookInfo = RemoteBackendClient.getBookInfo(bookId);
        } catch (RemoteBackendException e) {
            RespUtils.writeError(resp, 404, "未在后台找到该书：" + e.getMessage());
            return;
        } catch (Exception e) {
            RespUtils.writeError(resp, 502, "查询后台书况失败：" + e.getMessage());
            return;
        }

        // 2) 拉源站完整 toc
        AppConfig cfg = BeanUtil.copyProperties(AppConfigLoader.APP_CONFIG, AppConfig.class);
        cfg.setSourceId(rule.getId());
        List<Chapter> toc;
        try {
            toc = new TocParser(cfg).parseAll(bookUrl);
        } catch (Exception e) {
            RespUtils.writeError(resp, 502, "源站目录抓取失败：" + e.getMessage());
            return;
        }
        if (toc == null || toc.isEmpty()) {
            RespUtils.writeError(resp, 502, "源站目录为空");
            return;
        }

        // 3) 锚点匹配 → 默认起始/终止
        Optional<Integer> matched = IncrementalAnchorResolver.resolve(toc, bookInfo.getLatestChapterTitle());
        int defaultStart;
        Integer matchedIndex;
        if (matched.isPresent()) {
            matchedIndex = matched.get();
            defaultStart = matchedIndex + 2; // 1-based 起点 = (matched 0-based) + 1 + 1
        } else {
            matchedIndex = null;
            // 未匹配：默认从源站第一章未在后台的位置开始；保守起见用 toc 末尾减后台 latest 估算
            // —— 但这容易把不同源站的章节序号错位，安全起见直接 1-based = 1
            // 让用户在前端手动调整。
            defaultStart = 1;
        }
        int maxBatch = readMaxBatch();
        int defaultEnd = Math.min(defaultStart + maxBatch - 1, toc.size());
        if (defaultStart > toc.size()) {
            // 源站没有新章节
            defaultStart = toc.size();
            defaultEnd = toc.size();
        }

        // 4) 组装响应
        List<Map<String, Object>> items = toc.stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>(3);
                    m.put("order", c.getOrder());
                    m.put("title", c.getTitle());
                    m.put("url", c.getUrl());
                    return m;
                })
                .toList();

        Map<String, Object> bookInfoMap = new LinkedHashMap<>();
        bookInfoMap.put("bookId", bookInfo.getBookId());
        bookInfoMap.put("bookName", bookInfo.getBookName());
        bookInfoMap.put("author", bookInfo.getAuthor());
        bookInfoMap.put("latestChapterNo", bookInfo.getLatestChapterNo());
        bookInfoMap.put("latestChapterTitle", bookInfo.getLatestChapterTitle());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookInfo", bookInfoMap);
        data.put("toc", items);
        data.put("matchedIndex", matchedIndex);
        data.put("defaultStartOrder", defaultStart);
        data.put("defaultEndOrder", defaultEnd);
        data.put("maxBatch", maxBatch);
        RespUtils.writeJson(resp, data);
    }

    private int readMaxBatch() {
        RemoteBackendConfig rb = AppConfigLoader.APP_CONFIG.getRemoteBackend();
        Integer hard = rb == null ? null : rb.getPushBatchRejectThreshold();
        if (hard == null || hard < 1) return MAX_BATCH;
        return Math.min(MAX_BATCH, hard);
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
