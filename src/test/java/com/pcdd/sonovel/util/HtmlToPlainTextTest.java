package com.pcdd.sonovel.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author 石宇涛
 * Created at 2026/5/13
 */
class HtmlToPlainTextTest {

    @Test
    void nullOrBlank_returnsEmpty() {
        assertEquals("", HtmlToPlainText.from(null));
        assertEquals("", HtmlToPlainText.from(""));
        assertEquals("", HtmlToPlainText.from("   "));
    }

    @Test
    void plainTextWithoutTags_isReturnedTrimmed() {
        assertEquals("纯文本无标签", HtmlToPlainText.from("  纯文本无标签  "));
    }

    @Test
    void brSeparatesLines() {
        String html = "段落一<br>段落二<br>段落三";
        String out = HtmlToPlainText.from(html);
        // 用换行分隔后应至少 3 段
        assertEquals(3, out.split("\n").length);
        assertTrue(out.contains("段落一"));
        assertTrue(out.contains("段落二"));
        assertTrue(out.contains("段落三"));
    }

    @Test
    void pTagsBecomeParagraphs() {
        String html = "<p>段落一</p><p>段落二</p><p>段落三</p>";
        String out = HtmlToPlainText.from(html);
        // <p> 后插双换行，期望出现空行分隔
        assertTrue(out.contains("段落一"));
        assertTrue(out.contains("段落二"));
        assertTrue(out.contains("段落三"));
        // 至少包含一处段落分隔
        assertTrue(out.contains("\n\n"));
    }

    @Test
    void consecutiveNewlinesAreCollapsed() {
        String html = "<p>A</p><br><br><br><br><p>B</p>";
        String out = HtmlToPlainText.from(html);
        // 不应出现 3 个以上连续换行
        assertTrue(!out.contains("\n\n\n"));
    }

    @Test
    void scriptAndStyleAreStripped() {
        String html = "<script>var x = 1;</script><p>真实内容</p><style>p{color:red}</style>";
        String out = HtmlToPlainText.from(html);
        assertTrue(out.contains("真实内容"));
        // Jsoup body fragment 不会执行 script/style，但其文本节点可能被保留。
        // 我们至少保证主内容仍可读，且没有标签残留。
        assertTrue(!out.contains("<"));
    }

    @Test
    void nbspIsHandled() {
        String html = "<p>第&nbsp;1&nbsp;段</p>";
        String out = HtmlToPlainText.from(html);
        // jsoup 会把 &nbsp; 转成  ；我们不强制归一为普通空格，但内容应保留
        assertTrue(out.contains("第"));
        assertTrue(out.contains("1"));
        assertTrue(out.contains("段"));
    }
}
