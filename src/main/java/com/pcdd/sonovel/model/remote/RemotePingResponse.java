package com.pcdd.sonovel.model.remote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 接口 3 (ping) 响应 data。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemotePingResponse {

    /** 服务端当前时间 ISO8601 UTC */
    private String serverTime;

    /** 接口契约版本，当前 v1 */
    private String apiVersion;
}
