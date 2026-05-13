package com.pcdd.sonovel.model.remote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 接口 2 (report_chapters) 请求体。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemotePushRequest {

    private Integer bookId;
    private List<RemoteChapterPushItem> chapters;
    private RemoteClientMeta clientMeta;
}
