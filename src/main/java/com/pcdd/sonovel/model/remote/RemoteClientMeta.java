package com.pcdd.sonovel.model.remote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 接口 2 (report_chapters) client_meta，三项均必填。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteClientMeta {

    /** 源站名称（如"笔趣阁主站"） */
    private String sourceName;

    /** 源站书籍详情页 URL */
    private String sourceUrl;

    /** 下载器版本号 */
    private String appVersion;
}
