package com.pcdd.sonovel.exception;

/**
 * AI 小说后台返回 code=0 或客户端调用失败时抛出。
 * <p>
 * 参考：docs/网页端下载-AI小说后台对接接口文档.md § "统一响应格式"
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class RemoteBackendException extends RuntimeException {

    public RemoteBackendException(String message) {
        super(message);
    }

    public RemoteBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
