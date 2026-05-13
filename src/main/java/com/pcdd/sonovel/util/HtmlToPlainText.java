package com.pcdd.sonovel.util;

import cn.hutool.core.util.StrUtil;
import lombok.experimental.UtilityClass;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * HTML 章节内容 → 纯文本（保留段落感）。
 * <p>
 * 用于回推到 AI 后台前的内容归一：方案 § 6 决策 H 约定统一纯文本上报。
 * 实现思路：在 &lt;br&gt; / 块级标签后插入 literal "\n" 占位符（Jsoup
 * {@code text()} 不会折叠 literal），最后把占位符替换回真正换行。
 * 不依赖额外库（仅 jsoup，已有依赖）。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@UtilityClass
public class HtmlToPlainText {

    private static final String NL_MARKER = "\\n";
    private static final String DOUBLE_NL_MARKER = "\\n\\n";
    private static final String BLOCK_SELECTORS =
            "p, div, section, article, blockquote, h1, h2, h3, h4, h5, h6, li, tr";

    /** 把 HTML 章节正文转为段落化纯文本；输入为空或不含标签时按纯文本处理。 */
    public String from(String html) {
        if (html == null) {
            return "";
        }
        if (StrUtil.isBlank(html)) {
            return "";
        }
        if (!html.contains("<")) {
            return html.trim();
        }

        Document doc = Jsoup.parseBodyFragment(html);
        // 注意 append 的是 literal "\n" 而非真实换行：text() 会折叠真实换行，
        // 但保留这串占位符；最后再做一次 replace。
        doc.select("br").append(NL_MARKER);
        doc.select(BLOCK_SELECTORS).append(DOUBLE_NL_MARKER);

        String text = doc.text().replace(NL_MARKER, "\n");
        // 收敛连续 3+ 换行
        return text.replaceAll("\n{3,}", "\n\n").trim();
    }
}
