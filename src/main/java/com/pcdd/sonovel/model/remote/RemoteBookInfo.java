package com.pcdd.sonovel.model.remote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 接口 1 (get_book_info) 返回数据。
 * JSON 字段为 snake_case，由 RemoteBackendClient 手动映射。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteBookInfo {

    /** AI 小说后台书籍 ID */
    private Integer bookId;

    /** 书名 */
    private String bookName;

    /** 作者名 */
    private String author;

    /** 当前已入库章节最大序号；尚无章节时为 0 */
    private Integer latestChapterNo;

    /** 最后章节完整标题（含"第 X 章"前缀，与入库格式一致）；尚无章节时为空串 */
    private String latestChapterTitle;

    /** 最近一次章节入库时间 ISO8601 UTC */
    private String updatedAt;
}
