package com.pcdd.sonovel.core;

import com.pcdd.sonovel.model.remote.RemotePingResponse;
import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进程级 AI 后台健康状态单例。
 * <p>
 * 由 {@code Main} 启动期异步 ping 一次 + WebUI 的"测试连接"按钮按需更新；
 * {@code /backend-status} 直接读取此处快照，避免每次都触发真实 HTTP 调用。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@UtilityClass
public class RemoteBackendHealth {

    public enum Status { UNKNOWN, OK, FAILED }

    private volatile Status status = Status.UNKNOWN;
    private volatile String message = "";
    private volatile Long checkedAt = null;
    private volatile String apiVersion = "";
    private volatile String serverTime = "";

    public synchronized void recordOk(RemotePingResponse r) {
        status = Status.OK;
        message = "OK";
        checkedAt = System.currentTimeMillis();
        apiVersion = r == null ? "" : safe(r.getApiVersion());
        serverTime = r == null ? "" : safe(r.getServerTime());
    }

    public synchronized void recordFailed(String msg) {
        status = Status.FAILED;
        message = safe(msg);
        checkedAt = System.currentTimeMillis();
        apiVersion = "";
        serverTime = "";
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.name());
        m.put("message", message);
        m.put("checkedAt", checkedAt);
        m.put("apiVersion", apiVersion);
        m.put("serverTime", serverTime);
        return m;
    }

    public Status currentStatus() {
        return status;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
