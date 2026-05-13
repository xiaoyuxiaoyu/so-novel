package com.pcdd.sonovel.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.setting.Setting;
import cn.hutool.setting.dialect.Props;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.RemoteBackendConfig;
import com.pcdd.sonovel.util.EnvUtils;
import com.pcdd.sonovel.util.FileUtils;
import com.pcdd.sonovel.util.LangUtil;
import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author pcdd
 * Created at 2024/3/23
 */
@UtilityClass
public class AppConfigLoader {

    private final String SELECTION_GLOBAL = "global";
    private final String SELECTION_DOWNLOAD = "download";
    private final String SELECTION_SOURCE = "source";
    private final String SELECTION_CRAWL = "crawl";
    private final String SELECTION_WEB = "web";
    private final String SELECTION_COOKIE = "cookie";
    private final String SELECTION_PROXY = "proxy";
    private final String SELECTION_REMOTE_BACKEND = "remote-backend";
    public final AppConfig APP_CONFIG = loadConfig();

    /**
     * 加载应用属性
     */
    public Props sys() {
        return Props.getProp("application.properties", StandardCharsets.UTF_8);
    }

    /**
     * 加载用户属性
     */
    public Setting usr() {
        // 从虚拟机选项 -Dconfig.file 获取用户配置文件路径
        String configFilePath = System.getProperty("config.file");

        // 若未指定或指定路径不存在，则从默认位置获取
        if (!FileUtil.exist(configFilePath)) {
            // 用户配置文件默认路径
            String defaultPath = resolveConfigFileName();
            // 若默认路径也不存在，则抛出 FileNotFoundException
            return new Setting(defaultPath);
        }

        Path absolutePath = Paths.get(configFilePath).toAbsolutePath();

        return new Setting(absolutePath.toString());
    }

    public AppConfig loadConfig() {
        AppConfig cfg = new AppConfig();
        cfg.setVersion(sys().getStr("version"));
        Setting usr = usr();

        // [global]
        cfg.setAutoUpdate(usr.getInt("auto-update", SELECTION_GLOBAL, 0));
        cfg.setGhProxy(usr.getStr("gh-proxy", SELECTION_GLOBAL, ""));
        cfg.setCfBypass(usr.getStr("cf-bypass", SELECTION_GLOBAL, null));

        // [download]
        cfg.setDownloadPath(getStrOrDefault(usr, "download-path", SELECTION_DOWNLOAD, "downloads"));
        cfg.setExtName(getStrOrDefault(usr, "extname", SELECTION_DOWNLOAD, "epub").toLowerCase());
        cfg.setTxtEncoding(getStrOrDefault(usr, "txt-encoding", SELECTION_DOWNLOAD, "UTF-8"));
        cfg.setPreserveChapterCache(usr.getInt("preserve-chapter-cache", SELECTION_DOWNLOAD, 0));

        // [source]
        cfg.setLanguage(getStrOrDefault(usr, "language", SELECTION_SOURCE, LangUtil.getCurrentLang()));
        cfg.setActiveRules(getStrOrDefault(usr, "active-rules", SELECTION_SOURCE, "main.json"));
        cfg.setSourceId(usr.getInt("source-id", SELECTION_SOURCE, -1));
        cfg.setSearchLimit(usr.getInt("search-limit", SELECTION_SOURCE, -1));
        cfg.setSearchFilter(usr.getInt("search-filter", SELECTION_SOURCE, 1));

        // [crawl]
        cfg.setConcurrency(usr.getInt("concurrency", SELECTION_CRAWL, -1));
        cfg.setMinInterval(usr.getInt("min-interval", SELECTION_CRAWL, 200));
        cfg.setMaxInterval(usr.getInt("max-interval", SELECTION_CRAWL, 400));
        cfg.setEnableRetry(usr.getInt("enable-retry", SELECTION_CRAWL, 1));
        cfg.setMaxRetries(usr.getInt("max-retries", SELECTION_CRAWL, 5));
        cfg.setRetryMinInterval(usr.getInt("retry-min-interval", SELECTION_CRAWL, 2000));
        cfg.setRetryMaxInterval(usr.getInt("retry-max-interval", SELECTION_CRAWL, 4000));

        // [web]
        String mode = System.getProperty("mode", "tui");
        if ("web".equalsIgnoreCase(mode)) {
            cfg.setWebEnabled(1);
        } else {
            cfg.setWebEnabled(usr.getInt("enabled", SELECTION_WEB, 0));
        }
        cfg.setWebPort(usr.getInt("port", SELECTION_WEB, 7765));

        // [cookie]
        cfg.setQidianCookie(usr.getStr("qidian", SELECTION_COOKIE, ""));

        // [proxy]
        cfg.setProxyEnabled(usr.getInt("enabled", SELECTION_PROXY, 0));
        cfg.setProxyHost(getStrOrDefault(usr, "host", SELECTION_PROXY, "127.0.0.1"));
        cfg.setProxyPort(usr.getInt("port", SELECTION_PROXY, 7890));

        // [remote-backend]
        cfg.setRemoteBackend(loadRemoteBackend(usr));

        return cfg;
    }

    /**
     * 加载 [remote-backend] section。
     * mock=0 且 base-url 或 api-key 任一为空时抛 IllegalStateException 终止启动，
     * 避免裸跑往未配置的地址打请求。
     */
    private RemoteBackendConfig loadRemoteBackend(Setting usr) {
        RemoteBackendConfig c = new RemoteBackendConfig();
        c.setBaseUrl(StrUtil.trimToEmpty(usr.getByGroup("base-url", SELECTION_REMOTE_BACKEND)));
        c.setApiKey(StrUtil.trimToEmpty(usr.getByGroup("api-key", SELECTION_REMOTE_BACKEND)));
        c.setConnectTimeoutMs(getIntOrDefault(usr, "connect-timeout-ms", SELECTION_REMOTE_BACKEND, 5000));
        c.setReadTimeoutMs(getIntOrDefault(usr, "read-timeout-ms", SELECTION_REMOTE_BACKEND, 30000));
        c.setMock(parseNullableInt(usr.getByGroup("mock", SELECTION_REMOTE_BACKEND)));
        c.setPushBatchWarnThreshold(getIntOrDefault(usr, "push-batch-warn-threshold", SELECTION_REMOTE_BACKEND, 500));
        c.setPushBatchRejectThreshold(getIntOrDefault(usr, "push-batch-reject-threshold", SELECTION_REMOTE_BACKEND, 2000));
        c.setStartupPing(getIntOrDefault(usr, "startup-ping", SELECTION_REMOTE_BACKEND, 1));

        if (!c.isMockMode()) {
            if (StrUtil.isBlank(c.getBaseUrl()) || StrUtil.isBlank(c.getApiKey())) {
                throw new IllegalStateException(
                        "[remote-backend] mock=0 时 base-url 与 api-key 均必填，请检查 config.ini");
            }
        }
        return c;
    }

    private Integer parseNullableInt(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer getIntOrDefault(Setting setting, String key, String group, int defaultValue) {
        Integer v = parseNullableInt(setting.getByGroup(key, group));
        return v == null ? defaultValue : v;
    }

    // 修复 hutool 空串不能触发默认值的 bug
    private String getStrOrDefault(Setting setting, String key, String group, String defaultValue) {
        String value = setting.getByGroup(key, group);
        return StrUtil.isEmpty(value) ? defaultValue : value;
    }

    private String resolveConfigFileName() {
        return FileUtils.toAbsolutePath(EnvUtils.isDev() ? "config-dev.ini" : "config.ini");
    }

}