package com.pcdd.sonovel.model.remote;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 小说后台统一响应壳：{code, status, msg, data}。
 * code=1 成功，code=0 失败（由 RemoteBackendClient 转 RemoteBackendException）。
 *
 * @param <T> data 字段类型
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteApiResult<T> {

    private Integer code;
    private String status;
    private String msg;
    private T data;

    public boolean isSuccess() {
        return code != null && code == 1;
    }
}
