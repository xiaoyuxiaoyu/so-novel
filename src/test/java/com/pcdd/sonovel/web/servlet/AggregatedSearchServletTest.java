package com.pcdd.sonovel.web.servlet;

import com.pcdd.sonovel.model.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖网页端精确过滤：书名 + 作者均 trim 后严格相等。
 * <p>
 * 显式断言不做大小写 / 全半角 / 繁简归一化（防止后续误改放宽匹配规则，
 * 与方案 § 4.2 "禁止下载器侧引入归一化" 保持一致）。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
class AggregatedSearchServletTest {

    @Test
    void bothFieldsMatch_keepsOnlyExact() {
        List<SearchResult> raw = List.of(
                sr("斗破苍穹", "天蚕土豆", 1),
                sr("斗罗大陆", "唐家三少", 2),
                sr("斗破苍穹同人", "天蚕土豆", 3),
                sr("斗破苍穹", "另一作者", 4)
        );
        List<SearchResult> got = AggregatedSearchServlet.filterExact(raw, "斗破苍穹", "天蚕土豆");
        assertEquals(1, got.size());
        assertEquals(Integer.valueOf(1), got.get(0).getSourceId());
    }

    @Test
    void leadingTrailingSpacesAreTrimmed() {
        List<SearchResult> raw = List.of(
                sr("  斗破苍穹  ", "  天蚕土豆  ", 1)
        );
        List<SearchResult> got = AggregatedSearchServlet.filterExact(raw, "  斗破苍穹  ", "  天蚕土豆  ");
        assertEquals(1, got.size());
    }

    @Test
    void caseDifferenceIsNotNormalized() {
        // 大小写不一致不应命中（明确不做归一化）
        List<SearchResult> raw = List.of(sr("Re从零开始的异世界生活", "Author", 1));
        List<SearchResult> got = AggregatedSearchServlet.filterExact(raw, "re从零开始的异世界生活", "Author");
        assertTrue(got.isEmpty());
    }

    @Test
    void halfwidthFullwidthDifferenceIsNotNormalized() {
        // 全半角差异不应命中
        List<SearchResult> raw = List.of(sr("斗破苍穹（特别版）", "天蚕土豆", 1));
        List<SearchResult> got = AggregatedSearchServlet.filterExact(raw, "斗破苍穹(特别版)", "天蚕土豆");
        assertTrue(got.isEmpty());
    }

    @Test
    void emptyInput_returnsEmpty() {
        List<SearchResult> got = AggregatedSearchServlet.filterExact(List.of(), "X", "Y");
        assertTrue(got.isEmpty());
    }

    @Test
    void nullBookNameOrAuthor_filteredOut() {
        List<SearchResult> raw = List.of(
                sr(null, "天蚕土豆", 1),
                sr("斗破苍穹", null, 2),
                sr("斗破苍穹", "天蚕土豆", 3)
        );
        List<SearchResult> got = AggregatedSearchServlet.filterExact(raw, "斗破苍穹", "天蚕土豆");
        assertEquals(1, got.size());
        assertEquals(Integer.valueOf(3), got.get(0).getSourceId());
    }

    @Test
    void authorMismatch_filteredOut() {
        List<SearchResult> raw = List.of(
                sr("斗破苍穹", "天蚕土豆", 1),
                sr("斗破苍穹", "天蚕土豆 ", 2)  // 末尾空格 trim 后还是相等
        );
        List<SearchResult> got = AggregatedSearchServlet.filterExact(raw, "斗破苍穹", "天蚕土豆");
        assertEquals(2, got.size());
    }

    // ---------- helpers ----------

    private SearchResult sr(String bookName, String author, int sourceId) {
        return SearchResult.builder()
                .sourceId(sourceId)
                .bookName(bookName)
                .author(author)
                .build();
    }
}
