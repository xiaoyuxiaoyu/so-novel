package com.pcdd.sonovel.model;

import cn.hutool.core.util.StrUtil;
import lombok.Data;

/**
 * AI 小说后台对接配置，对应 config.ini 的 [remote-backend] section。
 * <p>
 * 参考：docs/网页端下载-AI小说后台对接方案.md § 2.5
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@Data
public class RemoteBackendConfig {

    /** AI 小说后台域名（含 scheme，结尾不带斜杠）；空时自动走 mock */
    private String baseUrl;

    /** 接入 API Key，对应请求头 X-Agent-API-Key；mock=0 时必填 */
    private String apiKey;

    /** 连接超时（毫秒） */
    private Integer connectTimeoutMs;

    /** 读取超时（毫秒） */
    private Integer readTimeoutMs;

    /** 1=强制 mock，0=真实链路，null=按 baseUrl 自动判断 */
    private Integer mock;

    /** 单次回推章节数预警阈值（前端 UI 二次确认） */
    private Integer pushBatchWarnThreshold;

    /** 单次回推章节数硬拒绝阈值 */
    private Integer pushBatchRejectThreshold;

    /** 启动期是否调用 /ping，1=调，0=不调 */
    private Integer startupPing;

    /**
     * mock=1 → 强制 mock；mock=0 → 真实；null → base-url 为空时自动 mock
     */
    public boolean isMockMode() {
        if (mock != null) {
            return mock == 1;
        }
        return StrUtil.isBlank(baseUrl);
    }
}
