package com.pcdd.sonovel.web.servlet;

import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.core.RemoteBackendHealth;
import com.pcdd.sonovel.model.RemoteBackendConfig;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 返回当前 AI 后台健康状态 + 基础配置（base-url / mock 模式）的只读视图。
 * 不主动触发 ping；前端启动时调一次决定是否显示顶部条幅 + 设置面板的连接信息。
 *
 * <pre>GET /backend-status</pre>
 *
 * 故意不暴露 api-key（敏感字段，浏览器永远不应拿到）。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class BackendStatusServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        RemoteBackendConfig rb = AppConfigLoader.APP_CONFIG.getRemoteBackend();

        Map<String, Object> data = new LinkedHashMap<>();
        if (rb == null) {
            data.put("baseUrl", "");
            data.put("mockMode", true);
        } else {
            data.put("baseUrl", rb.getBaseUrl() == null ? "" : rb.getBaseUrl());
            data.put("mockMode", rb.isMockMode());
        }
        data.put("health", RemoteBackendHealth.snapshot());
        RespUtils.writeJson(resp, data);
    }
}
