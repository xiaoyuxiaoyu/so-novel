package com.pcdd.sonovel.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.remote.RemoteChapterPushItem;
import com.pcdd.sonovel.util.HtmlToPlainText;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 增量下载任务的"回推收集器"：Crawler 每章解析完同步落盘的同时调用
 * {@link #collect(Chapter)} 累加纯文本副本到内存 + 写一份到
 * {@code ${workDir}/.so-novel/reports/{taskId}/{order}.txt}。
 * <p>
 * 设计参考开发文档 § 5.2.4 / 方案 v0.2 修订 ④（两件套）：
 * <ul>
 *   <li>内存累加供本次任务结束后整体回推，与下载格式 (TXT/EPUB/PDF/HTML) 解耦</li>
 *   <li>磁盘副本供"重新上报"按钮使用，进程重启仍可重推</li>
 * </ul>
 * 线程安全：Crawler 用虚拟线程并发解析；{@link ConcurrentLinkedQueue} +
 * 单文件写入即可，无需额外锁。
 * <p>
 * {@link RemoteChapterPushItem#chapterNo} 此处先用源站 order 占位；
 * IncrementalDownloadServlet 在 {@link #snapshot()} 后按 {@code latest_chapter_no + 1}
 * 起递增重写。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class ReportCollector {

    private final String taskId;
    private final File reportDir;
    private final ConcurrentLinkedQueue<RemoteChapterPushItem> queue = new ConcurrentLinkedQueue<>();

    public ReportCollector(String taskId, String workDir) {
        if (StrUtil.isBlank(taskId)) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        this.taskId = taskId;
        this.reportDir = FileUtil.mkdir(new File(workDir + File.separator
                + ".so-novel" + File.separator
                + "reports" + File.separator
                + taskId));
    }

    /** Crawler 内部每章解析完调一次。content 取 HTML 原文，本类内部转纯文本。 */
    public void collect(Chapter chapter) {
        if (chapter == null || chapter.getOrder() == null) {
            return;
        }
        String plain = HtmlToPlainText.from(chapter.getContent());

        queue.add(RemoteChapterPushItem.builder()
                .chapterNo(chapter.getOrder())   // 占位，回推前重写
                .title(chapter.getTitle() == null ? "" : chapter.getTitle())
                .content(plain)
                .build());

        // 同步落地纯文本副本（按源站 order 命名），供重推按钮使用
        FileUtil.writeString(plain,
                new File(reportDir, chapter.getOrder() + ".txt"),
                StandardCharsets.UTF_8);
    }

    /** 按源站 order 升序返回快照，IncrementalDownloadServlet 在此基础上重写 chapter_no。 */
    public List<RemoteChapterPushItem> snapshot() {
        return queue.stream()
                .sorted(Comparator.comparingInt(RemoteChapterPushItem::getChapterNo))
                .toList();
    }

    public String getTaskId() {
        return taskId;
    }

    public File getReportDir() {
        return reportDir;
    }

    public int size() {
        return queue.size();
    }
}
