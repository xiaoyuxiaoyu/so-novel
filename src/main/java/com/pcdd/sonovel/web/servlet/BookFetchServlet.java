package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.Crawler;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.parse.TocParser;
import com.pcdd.sonovel.util.SourceUtils;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Set;

public class BookFetchServlet extends HttpServlet {

    private static final Set<String> ALLOWED_FORMATS = Set.of("epub", "txt", "html", "pdf");
    private static final Set<String> ALLOWED_LANGUAGES = Set.of("zh_cn", "zh_tw", "zh_hant");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        try {
            String bookUrl = req.getParameter("url");
            String format = req.getParameter("format");
            String language = req.getParameter("language");
            String concurrencyStr = req.getParameter("concurrency");
            String startStr = req.getParameter("start");
            String endStr = req.getParameter("end");
            String latestStr = req.getParameter("latest");
            int id = SourceUtils.getRule(bookUrl).getId();

            if (StrUtil.isNotBlank(format) && !ALLOWED_FORMATS.contains(format.toLowerCase())) {
                RespUtils.writeError(resp, 400, "不支持的下载格式: " + format + "，可选: epub, txt, html, pdf");
                return;
            }

            if (StrUtil.isNotBlank(language) && !ALLOWED_LANGUAGES.contains(language.toLowerCase())) {
                RespUtils.writeError(resp, 400, "不支持的语言: " + language + "，可选: zh_CN, zh_TW, zh_Hant");
                return;
            }

            if (StrUtil.isNotBlank(latestStr) && (StrUtil.isNotBlank(startStr) || StrUtil.isNotBlank(endStr))) {
                RespUtils.writeError(resp, 400, "latest 不能与 start/end 同时使用");
                return;
            }

            Integer concurrency = null;
            if (StrUtil.isNotBlank(concurrencyStr)) {
                concurrency = Integer.parseInt(concurrencyStr);
                int configConcurrency = AppConfigLoader.APP_CONFIG.getConcurrency();
                int maxAllowed = configConcurrency > 0 ? configConcurrency : 50;
                if (concurrency < 1 || concurrency > maxAllowed) {
                    RespUtils.writeError(resp, 400, "并发数须在 1~" + maxAllowed + " 之间");
                    return;
                }
            }

            Integer start = StrUtil.isNotBlank(startStr) ? Integer.parseInt(startStr) : null;
            Integer end = StrUtil.isNotBlank(endStr) ? Integer.parseInt(endStr) : null;
            Integer latest = StrUtil.isNotBlank(latestStr) ? Integer.parseInt(latestStr) : null;

            if (start != null && start < 1) {
                RespUtils.writeError(resp, 400, "start 必须 >= 1");
                return;
            }
            if (end != null && end < 1) {
                RespUtils.writeError(resp, 400, "end 必须 >= 1");
                return;
            }
            if (start != null && end != null && start > end) {
                RespUtils.writeError(resp, 400, "start 不能大于 end");
                return;
            }
            if (latest != null && latest < 1) {
                RespUtils.writeError(resp, 400, "latest 必须 >= 1");
                return;
            }

            SearchResult sr = SearchResult.builder()
                    .sourceId(id)
                    .url(bookUrl)
                    .build();

            downloadFileToServer(sr, format, language, concurrency, start, end, latest);
        } catch (NumberFormatException e) {
            RespUtils.writeError(resp, 400, "参数格式错误: " + e.getMessage());
        } catch (Exception e) {
            RespUtils.writeError(resp, 500, "下载失败: " + e.getMessage());
        }
    }

    private void downloadFileToServer(SearchResult sr, String format, String language,
                                      Integer concurrency, Integer start, Integer end, Integer latest) {
        AppConfig cfg = BeanUtil.copyProperties(AppConfigLoader.APP_CONFIG, AppConfig.class);
        cfg.setSourceId(sr.getSourceId());

        if (StrUtil.isNotBlank(format)) {
            cfg.setExtName(format.toLowerCase());
        }
        if (StrUtil.isNotBlank(language)) {
            cfg.setLanguage(language);
        }
        if (concurrency != null) {
            cfg.setConcurrency(concurrency);
        }

        Console.log("<== 正在获取章节目录...");
        TocParser tocParser = new TocParser(cfg);
        Crawler crawler = new Crawler(cfg);

        // 无章节范围参数，下载全本
        if (start == null && end == null && latest == null) {
            crawler.crawl(sr.getUrl());
            return;
        }

        // 获取全部章节目录
        List<Chapter> toc = tocParser.parseAll(sr.getUrl());
        if (toc.isEmpty()) {
            Console.log("<== 目录为空，中止下载");
            return;
        }
        Console.log("<== 共计 {} 章", toc.size());

        if (latest != null) {
            // 下载最新 N 章
            int from = Math.max(toc.size() - latest, 0);
            toc = CollUtil.sub(toc, from, toc.size());
        } else {
            // 下载指定范围
            int s = (start != null ? start : 1) - 1;
            int e = end != null ? Math.min(end, toc.size()) : toc.size();
            toc = CollUtil.sub(toc, s, e);
        }

        if (toc.isEmpty()) {
            Console.log("<== 指定范围无章节，中止下载");
            return;
        }
        Console.log("<== 将下载 {} 章", toc.size());
        crawler.crawl(sr.getUrl(), toc);
    }

}