package com.pcdd.sonovel.web.servlet;

import com.pcdd.sonovel.model.remote.RemoteChapterPushItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 chapter_no 重写逻辑（方案 v0.2 修订 ① 简化：只支持追加）。
 * <p>
 * Servlet 主体走真实网络与 Crawler，靠 M5 联调验证；本测试只锁住核心算法。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
class IncrementalDownloadServletTest {

    @Test
    void appendCase_K100_N5_yields101To105() {
        // 模拟 ReportCollector snapshot（占位 chapter_no = 源站 order）
        List<RemoteChapterPushItem> raw = List.of(
                item(501, "第 501 章 A", "内容 A"),
                item(502, "第 502 章 B", "内容 B"),
                item(503, "第 503 章 C", "内容 C"),
                item(504, "第 504 章 D", "内容 D"),
                item(505, "第 505 章 E", "内容 E")
        );
        // 后台 latest_chapter_no = 100，下 5 章 → 101..105
        List<RemoteChapterPushItem> out = IncrementalDownloadServlet.renumberForReport(raw, 101);

        assertEquals(5, out.size());
        assertEquals(Integer.valueOf(101), out.get(0).getChapterNo());
        assertEquals(Integer.valueOf(102), out.get(1).getChapterNo());
        assertEquals(Integer.valueOf(103), out.get(2).getChapterNo());
        assertEquals(Integer.valueOf(104), out.get(3).getChapterNo());
        assertEquals(Integer.valueOf(105), out.get(4).getChapterNo());
        // title / content 不变
        assertEquals("第 501 章 A", out.get(0).getTitle());
        assertEquals("内容 A", out.get(0).getContent());
    }

    @Test
    void emptyRaw_returnsEmpty() {
        assertTrue(IncrementalDownloadServlet.renumberForReport(List.of(), 100).isEmpty());
        assertTrue(IncrementalDownloadServlet.renumberForReport(null, 100).isEmpty());
    }

    @Test
    void newBookCase_K0_N3_yields1To3() {
        // 后台无章节 (latest_chapter_no = 0) → 从 chapter_no=1 起
        List<RemoteChapterPushItem> raw = List.of(
                item(1, "第 1 章", "C1"),
                item(2, "第 2 章", "C2"),
                item(3, "第 3 章", "C3")
        );
        List<RemoteChapterPushItem> out = IncrementalDownloadServlet.renumberForReport(raw, 1);

        assertEquals(Integer.valueOf(1), out.get(0).getChapterNo());
        assertEquals(Integer.valueOf(2), out.get(1).getChapterNo());
        assertEquals(Integer.valueOf(3), out.get(2).getChapterNo());
    }

    @Test
    void sourceOrderUnrelatedToChapterNo() {
        // 用户在未命中分支选了源站第 50 章作锚点，从源站第 51 章开始下载 5 章
        // 但回推 chapter_no 仍接 latest_chapter_no + 1 (例如 100+1)，不沿用源站序号
        List<RemoteChapterPushItem> raw = List.of(
                item(51, "第 51 章 X", "A"),
                item(52, "第 52 章 Y", "B")
        );
        List<RemoteChapterPushItem> out = IncrementalDownloadServlet.renumberForReport(raw, 101);
        assertEquals(Integer.valueOf(101), out.get(0).getChapterNo());
        assertEquals(Integer.valueOf(102), out.get(1).getChapterNo());
        // 章节标题保留源站第 X 章 ↑，与目标 chapter_no=101 不一致——这是 v0.2 修订
        // ① 明确接受的行为：标题原文保留、序号按目标序号递增
    }

    // ---------- helpers ----------

    private RemoteChapterPushItem item(int order, String title, String content) {
        return RemoteChapterPushItem.builder()
                .chapterNo(order)
                .title(title)
                .content(content)
                .build();
    }
}
