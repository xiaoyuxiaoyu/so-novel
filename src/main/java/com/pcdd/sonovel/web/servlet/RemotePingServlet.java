package com.pcdd.sonovel.web.servlet;

import com.pcdd.sonovel.core.RemoteBackendHealth;
import com.pcdd.sonovel.exception.RemoteBackendException;
import com.pcdd.sonovel.model.remote.RemotePingResponse;
import com.pcdd.sonovel.repository.RemoteBackendClient;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主动触发 AI 后台健康检查，更新 {@link RemoteBackendHealth} 并返回结果。
 *
 * <pre>GET /remote-ping</pre>
 *
 * 用于设置面板"测试连接"按钮；启动期由 {@code Main} 也调用一次。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class RemotePingServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            RemotePingResponse r = RemoteBackendClient.ping();
            RemoteBackendHealth.recordOk(r);
            data.put("status", "OK");
            data.put("apiVersion", r.getApiVersion());
            data.put("serverTime", r.getServerTime());
            RespUtils.writeJson(resp, data);
        } catch (RemoteBackendException e) {
            RemoteBackendHealth.recordFailed(e.getMessage());
            RespUtils.writeError(resp, 502, "AI 后台不可达：" + e.getMessage());
        } catch (Exception e) {
            RemoteBackendHealth.recordFailed(e.getMessage());
            RespUtils.writeError(resp, 500, "测试连接失败：" + e.getMessage());
        }
    }
}
