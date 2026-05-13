package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.parse.TocParser;
import com.pcdd.sonovel.util.SourceUtils;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拉源站完整目录，返回精简版给前端做"未命中"分支的锚点选择。
 *
 * <pre>GET /source-toc?url=https://...</pre>
 *
 * 返回结构：
 * <pre>data: { tocSize: 5000, items: [ {order, title}, ... ] }</pre>
 * <p>
 * 故意剥掉 chapter url 减少传输体量 (~150KB vs ~500KB)；用户选定锚点后由
 * IncrementalDownloadServlet 在源站重新切片。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class SourceTocServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        String bookUrl = req.getParameter("url");
        if (StrUtil.isBlank(bookUrl)) {
            RespUtils.writeError(resp, 400, "url 不能为空");
            return;
        }

        try {
            Rule rule = SourceUtils.getRule(bookUrl);
            if (rule == null) {
                RespUtils.writeError(resp, 400, "不支持的源站 URL");
                return;
            }
            AppConfig cfg = BeanUtil.copyProperties(AppConfigLoader.APP_CONFIG, AppConfig.class);
            cfg.setSourceId(rule.getId());

            List<Chapter> toc = new TocParser(cfg).parseAll(bookUrl);
            if (toc == null || toc.isEmpty()) {
                RespUtils.writeError(resp, 502, "源站目录为空或抓取失败");
                return;
            }

            List<Map<String, Object>> items = toc.stream()
                    .map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>(2);
                        m.put("order", c.getOrder());
                        m.put("title", c.getTitle());
                        return m;
                    })
                    .toList();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tocSize", items.size());
            data.put("items", items);
            RespUtils.writeJson(resp, data);
        } catch (Exception e) {
            RespUtils.writeError(resp, 502, "源站目录抓取失败: " + e.getMessage());
        }
    }
}
