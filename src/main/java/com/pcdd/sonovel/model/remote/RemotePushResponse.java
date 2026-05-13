package com.pcdd.sonovel.model.remote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 接口 2 (report_chapters) 响应 data。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemotePushResponse {

    /** 新增入库章节数 */
    private Integer acceptedCount;

    /** 覆盖更新章节数（对应后台 upsert 命中已有 chapter_no） */
    private Integer updatedCount;

    /** 因写入异常未入库的章节，空数组表示无失败 */
    private List<RemoteRejectedChapter> rejected;
}
