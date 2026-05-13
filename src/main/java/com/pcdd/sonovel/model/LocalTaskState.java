package com.pcdd.sonovel.model;

import com.pcdd.sonovel.model.remote.RemoteRejectedChapter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 网页端"主动下载并回推"任务状态，持久化到 {@code ${workDir}/.so-novel/tasks.json}。
 * <p>
 * 与 {@link com.pcdd.sonovel.core.ReportCollector} 在
 * {@code ${workDir}/.so-novel/reports/{taskId}/} 下落地的纯文本副本配套使用：
 * <ul>
 *   <li>reports 目录里按源站 order 命名的 {@code N.txt} 存章节正文</li>
 *   <li>本类的 {@link #chapters} 记录每章的 (sourceOrder, chapterNo, title) 三元组，
 *       供"重新上报"按钮按目标 chapter_no 重建 RemotePushRequest</li>
 * </ul>
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalTaskState {

    public enum Status {
        /** 章节已落地本地 reports 副本，但回推未完成 */
        DOWNLOADED_NOT_PUSHED,
        /** 全部章节成功 upsert 到 AI 后台 */
        PUSHED,
        /** 后台返回 code=1 但 rejected 非空 */
        PARTIAL,
        /** 调 reportChapters 抛异常 */
        FAILED
    }

    private String taskId;
    private Integer bookId;
    private Integer sourceId;
    private String sourceName;
    private String bookUrl;

    /** 每章 (源站 order, 目标 chapter_no, 完整标题)；重推时按这个数组加载 reports 副本重建 push body */
    private List<ChapterRef> chapters;

    private Status status;

    /** PARTIAL 时记录后台返回的 rejected 章节，便于前端展示 */
    private List<RemoteRejectedChapter> rejected;

    /** FAILED 时的失败信息 */
    private String errorMessage;

    /** 创建时间戳（毫秒） */
    private Long createdAt;

    /** 最近一次状态更新时间戳（毫秒） */
    private Long updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChapterRef {
        /** 源站章节序号（reports 文件名用此） */
        private Integer sourceOrder;
        /** AI 后台 DB 内目标章节序号 */
        private Integer chapterNo;
        /** 完整标题 */
        private String title;
    }
}
