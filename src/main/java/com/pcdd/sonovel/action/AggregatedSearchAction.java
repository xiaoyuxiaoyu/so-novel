package com.pcdd.sonovel.action;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Console;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.Source;
import com.pcdd.sonovel.handle.SearchResultsHandler;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.parse.SearchParser;
import com.pcdd.sonovel.parse.SearchParserQuanben5;
import com.pcdd.sonovel.util.SourceUtils;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.fusesource.jansi.AnsiRenderer.render;

/**
 * 聚合搜索，从全部书源搜索
 *
 * @author pcdd
 * Created at 2025/3/26
 */
@AllArgsConstructor
public class AggregatedSearchAction {

    public void execute() {
        Scanner sc = Console.scanner();
        Console.print(render("==> 请输入书名或作者（尽量输完整）: ", "green"));
        String kw = sc.nextLine().strip();
        if (kw.isEmpty()) return;

        List<SearchResult> results = getSearchResults(kw);

        if (CollUtil.isEmpty(results)) {
            Console.log(render("聚合搜索结果为空！", "yellow"));
            return;
        }

        SearchParser.printAggregateSearchResult(results);

        new DownloadAction().execute(results);
    }

    @SneakyThrows
    public static List<SearchResult> getSearchResults(String kw) {
        Console.log("<== 搜索关键字 “{}”", kw);
        List<SearchResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Source> searchableSources = SourceUtils.getSearchableSources();
        CountDownLatch latch = new CountDownLatch(searchableSources.size());

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (Source source : searchableSources) {
                executor.execute(() -> {
                    try {
                        List<SearchResult> res = "proxy-required.json".equals(source.config.getActiveRules()) && source.config.getSourceId() == 2
                                ? new SearchParserQuanben5(source.config).parse(kw)
                                : new SearchParser(source.config).parse(kw);
                        Rule rule = source.rule;
                        if (CollUtil.isNotEmpty(res)) {
                            Console.log("<== 书源 {} ({})\t搜索到 {} 条记录", rule.getId(), rule.getName(), res.size());
                            results.addAll(res);
                        } else {
                            Console.log("<== 书源 {} ({})\t无结果", rule.getId(), rule.getName());
                        }
                    } catch (Exception e) {
                        Console.error("搜索源 {} 异常：{}", source.rule.getName(), e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }

            int aggregateTimeoutSec = 60;
            boolean completed = latch.await(aggregateTimeoutSec, TimeUnit.SECONDS);
            if (!completed) {
                Console.log(render("<== 聚合搜索超时 ({}s)，部分书源未响应，使用已收集到的结果", "yellow"), aggregateTimeoutSec);
            }
            return AppConfigLoader.APP_CONFIG.getSearchFilter() == 1
                    ? SearchResultsHandler.filterSort(results, kw)
                    : results;
        } finally {
            executor.shutdownNow();
        }
    }

}