package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.action.AggregatedSearchAction;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

public class AggregatedSearchServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        String kw = req.getParameter("kw");
        String author = req.getParameter("author");
        String exactMatch = req.getParameter("exactMatch");
        String searchLimitStr = req.getParameter("searchLimit");

        boolean isExact = "true".equalsIgnoreCase(exactMatch);

        if (isExact && StrUtil.isBlank(author)) {
            RespUtils.writeError(resp, 400, "exactMatch=true 时 author 必填");
            return;
        }
        if (isExact && StrUtil.isBlank(kw)) {
            RespUtils.writeError(resp, 400, "exactMatch=true 时书名 (kw) 必填");
            return;
        }

        // 网页端 exactMatch 路径绕过 filterSort 的"猜意图"过滤，由服务端单点做"书名+作者严格相等"
        List<SearchResult> results = AggregatedSearchAction.getSearchResults(kw, isExact);

        if (isExact) {
            results = filterExact(results, kw, author);
        }

        if (StrUtil.isNotBlank(searchLimitStr)) {
            try {
                int clientLimit = Integer.parseInt(searchLimitStr);
                int configLimit = AppConfigLoader.APP_CONFIG.getSearchLimit();
                // 不可超过配置文件限制
                if (configLimit > 0 && clientLimit > configLimit) {
                    clientLimit = configLimit;
                }
                if (clientLimit > 0 && clientLimit < results.size()) {
                    results = results.subList(0, clientLimit);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        RespUtils.writeJson(resp, results);
    }

    /**
     * 网页端精确过滤：书名与作者均 trim 后严格相等。
     * <p>
     * 不做大小写 / 全半角 / 繁简归一化（与方案 § 4.2 一致：归一化会与后台口径漂移）。
     * 留 package-private 便于单测直接覆盖。
     */
    static List<SearchResult> filterExact(List<SearchResult> raw, String bookName, String author) {
        String bookNameTrim = bookName.trim();
        String authorTrim = author.trim();
        return raw.stream()
                .filter(sr -> sr.getBookName() != null && bookNameTrim.equals(sr.getBookName().trim()))
                .filter(sr -> sr.getAuthor() != null && authorTrim.equals(sr.getAuthor().trim()))
                .toList();
    }

}
