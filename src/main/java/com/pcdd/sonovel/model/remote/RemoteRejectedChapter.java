package com.pcdd.sonovel.model.remote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 接口 2 响应 data.rejected[] 元素。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteRejectedChapter {

    private Integer chapterNo;
    private String title;

    /** 失败原因码：content_empty / content_oversize / storage_write_failed */
    private String reason;
}
