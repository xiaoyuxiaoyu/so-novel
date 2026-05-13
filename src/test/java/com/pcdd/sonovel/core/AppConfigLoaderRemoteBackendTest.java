package com.pcdd.sonovel.core;

import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.RemoteBackendConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 [remote-backend] section 的加载与启动期校验。
 * <p>
 * 通过 -Dconfig.file 指向临时 ini 文件直接驱动 {@link AppConfigLoader#loadConfig()}，
 * 不依赖 APP_CONFIG 静态字段的初始化时序。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
class AppConfigLoaderRemoteBackendTest {

    private Path tmpIni;

    @AfterEach
    void cleanup() throws Exception {
        System.clearProperty("config.file");
        if (tmpIni != null) {
            Files.deleteIfExists(tmpIni);
        }
    }

    @Test
    void mockOff_baseUrlEmpty_throws() throws Exception {
        writeIni("""
                [remote-backend]
                mock = 0
                base-url =
                api-key = some-key
                """);
        assertThrows(IllegalStateException.class, AppConfigLoader::loadConfig);
    }

    @Test
    void mockOff_apiKeyEmpty_throws() throws Exception {
        writeIni("""
                [remote-backend]
                mock = 0
                base-url = https://example.com
                api-key =
                """);
        assertThrows(IllegalStateException.class, AppConfigLoader::loadConfig);
    }

    @Test
    void mockOff_bothFilled_passesAndUsesRealMode() throws Exception {
        writeIni("""
                [remote-backend]
                mock = 0
                base-url = https://example.com
                api-key = test-key
                """);
        AppConfig cfg = AppConfigLoader.loadConfig();
        RemoteBackendConfig rb = cfg.getRemoteBackend();
        assertNotNull(rb);
        assertEquals(0, rb.getMock());
        assertEquals("https://example.com", rb.getBaseUrl());
        assertEquals("test-key", rb.getApiKey());
        assertEquals(false, rb.isMockMode());
        // 默认值校验
        assertEquals(5000, rb.getConnectTimeoutMs());
        assertEquals(30000, rb.getReadTimeoutMs());
        assertEquals(500, rb.getPushBatchWarnThreshold());
        assertEquals(2000, rb.getPushBatchRejectThreshold());
        assertEquals(1, rb.getStartupPing());
    }

    @Test
    void mockOn_baseUrlAndApiKeyCanBeEmpty() throws Exception {
        writeIni("""
                [remote-backend]
                mock = 1
                base-url =
                api-key =
                """);
        AppConfig cfg = AppConfigLoader.loadConfig();
        RemoteBackendConfig rb = cfg.getRemoteBackend();
        assertEquals(1, rb.getMock());
        assertTrue(rb.isMockMode());
    }

    @Test
    void mockUnset_baseUrlEmpty_autoMockNotThrows() throws Exception {
        writeIni("""
                [remote-backend]
                mock =
                base-url =
                api-key =
                """);
        AppConfig cfg = AppConfigLoader.loadConfig();
        RemoteBackendConfig rb = cfg.getRemoteBackend();
        assertNull(rb.getMock());
        // base-url 空 + mock 未设置 → 自动 mock
        assertTrue(rb.isMockMode());
    }

    @Test
    void timeoutAndThresholdsHonorOverrides() throws Exception {
        writeIni("""
                [remote-backend]
                mock = 1
                connect-timeout-ms = 8000
                read-timeout-ms = 45000
                push-batch-warn-threshold = 100
                push-batch-reject-threshold = 1000
                startup-ping = 0
                """);
        RemoteBackendConfig rb = AppConfigLoader.loadConfig().getRemoteBackend();
        assertEquals(8000, rb.getConnectTimeoutMs());
        assertEquals(45000, rb.getReadTimeoutMs());
        assertEquals(100, rb.getPushBatchWarnThreshold());
        assertEquals(1000, rb.getPushBatchRejectThreshold());
        assertEquals(0, rb.getStartupPing());
    }

    private void writeIni(String content) throws Exception {
        tmpIni = Files.createTempFile("rb-test-config", ".ini");
        Files.writeString(tmpIni, content);
        System.setProperty("config.file", tmpIni.toAbsolutePath().toString());
    }
}
