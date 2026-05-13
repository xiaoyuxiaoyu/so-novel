package com.pcdd.sonovel.web.servlet;

import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.exception.RemoteBackendException;
import com.pcdd.sonovel.model.remote.RemoteBookInfo;
import com.pcdd.sonovel.repository.RemoteBackendClient;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 转发 AI 小说后台 /get_book_info 接口给前端弹窗回显二次确认。
 *
 * <pre>GET /remote-book-info?bookId=123</pre>
 *
 * 成功时 data 字段为 {@link RemoteBookInfo}。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class RemoteBookInfoServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        String bookIdStr = req.getParameter("bookId");
        if (StrUtil.isBlank(bookIdStr)) {
            RespUtils.writeError(resp, 400, "bookId 不能为空");
            return;
        }
        int bookId;
        try {
            bookId = Integer.parseInt(bookIdStr.trim());
        } catch (NumberFormatException e) {
            RespUtils.writeError(resp, 400, "bookId 必须为正整数");
            return;
        }
        if (bookId < 1) {
            RespUtils.writeError(resp, 400, "bookId 必须 >= 1");
            return;
        }

        try {
            RemoteBookInfo info = RemoteBackendClient.getBookInfo(bookId);
            RespUtils.writeJson(resp, info);
        } catch (RemoteBackendException e) {
            RespUtils.writeError(resp, 404, e.getMessage());
        } catch (Exception e) {
            RespUtils.writeError(resp, 500, "查询书况失败: " + e.getMessage());
        }
    }
}
