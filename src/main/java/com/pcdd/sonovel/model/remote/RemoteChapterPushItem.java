package com.pcdd.sonovel.model.remote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 接口 2 (report_chapters) chapters[] 元素。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteChapterPushItem {

    /** AI 后台 DB 内目标章节序号（不是源站序号），由 RemoteBackendClient 调用方按 latest_chapter_no+1 起递增赋值 */
    private Integer chapterNo;

    /** 完整章节标题（含"第 X 章"前缀），直接传源站原文不做归一化 */
    private String title;

    /** 章节正文，统一纯文本，单章 ≤ 1MB */
    private String content;
}
