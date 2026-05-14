package com.pcdd.sonovel.core;

import cn.hutool.core.io.FileUtil;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.remote.RemoteChapterPushItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author 石宇涛
 * Created at 2026/5/13
 */
class ReportCollectorTest {

    private Path tmpWorkDir;

    @BeforeEach
    void setup() throws Exception {
        tmpWorkDir = Files.createTempDirectory("rc-test-");
    }

    @AfterEach
    void cleanup() {
        if (tmpWorkDir != null) {
            FileUtil.del(tmpWorkDir.toFile());
        }
    }

    @Test
    void blankTaskId_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ReportCollector("", "/tmp"));
        assertThrows(IllegalArgumentException.class, () -> new ReportCollector(null, "/tmp"));
    }

    @Test
    void reportDirCreatedUnderSoNovelDir() {
        ReportCollector rc = new ReportCollector("task-1", tmpWorkDir.toString());
        File expected = tmpWorkDir.resolve(".so-novel/reports/task-1").toFile();
        assertTrue(expected.exists());
        assertEquals(expected.getAbsolutePath(), rc.getReportDir().getAbsolutePath());
    }

    @Test
    void collectAccumulatesAndWritesPlainText() {
        ReportCollector rc = new ReportCollector("task-2", tmpWorkDir.toString());
        rc.collect(chapter(1, "第 1 章 起", "<p>段一</p><p>段二</p>"));
        rc.collect(chapter(2, "第 2 章 承", "<p>段三</p>"));

        assertEquals(2, rc.size());
        File f1 = new File(rc.getReportDir(), "1.txt");
        assertTrue(f1.exists());
        String c1 = FileUtil.readUtf8String(f1);
        assertTrue(c1.contains("段一"));
        assertTrue(c1.contains("段二"));
        // 落盘的应是纯文本（无标签）
        assertTrue(!c1.contains("<"));
    }

    @Test
    void snapshotIsSortedByOrder() {
        ReportCollector rc = new ReportCollector("task-3", tmpWorkDir.toString());
        // 故意乱序插入
        rc.collect(chapter(3, "第 3 章", "内容 C"));
        rc.collect(chapter(1, "第 1 章", "内容 A"));
        rc.collect(chapter(2, "第 2 章", "内容 B"));

        List<RemoteChapterPushItem> snap = rc.snapshot();
        assertEquals(3, snap.size());
        assertEquals(Integer.valueOf(1), snap.get(0).getChapterNo());
        assertEquals(Integer.valueOf(2), snap.get(1).getChapterNo());
        assertEquals(Integer.valueOf(3), snap.get(2).getChapterNo());
        // chapter_no 此处是源站 order 占位，等 IncrementalDownloadServlet 重写
        assertTrue(snap.get(0).getContent().contains("内容 A"));
    }

    @Test
    void leadingTitleStripped_whenContentStartsWithTitle() {
        // 模拟 ChapterRenderer 渲染 txt 后的 content 形态：以 title 开头 + 双换行 + 正文段
        ReportCollector rc = new ReportCollector("task-strip", tmpWorkDir.toString());
        rc.collect(Chapter.builder()
                .order(1).title("第 1 章 起")
                .content("第 1 章 起\n\n　　段一\n　　段二")
                .build());
        String content = rc.snapshot().get(0).getContent();
        assertTrue(!content.startsWith("第 1 章"), "标题应被剥掉，实际: " + content);
        assertTrue(content.contains("段一"));
    }

    @Test
    void stripLeadingTitle_noOpWhenNotPrefixed() {
        assertEquals("纯正文", ReportCollector.stripLeadingTitle("纯正文", "第 1 章 起"));
        assertEquals("纯正文", ReportCollector.stripLeadingTitle("纯正文", ""));
        assertEquals("纯正文", ReportCollector.stripLeadingTitle("纯正文", null));
    }

    @Test
    void nullChapterIsIgnored() {
        ReportCollector rc = new ReportCollector("task-4", tmpWorkDir.toString());
        rc.collect(null);
        rc.collect(Chapter.builder().title("无 order").content("X").build());  // order=null
        assertEquals(0, rc.size());
    }

    // ---------- helpers ----------

    private Chapter chapter(int order, String title, String htmlContent) {
        return Chapter.builder()
                .order(order)
                .title(title)
                .content(htmlContent)
                .build();
    }
}
