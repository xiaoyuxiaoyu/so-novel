package com.pcdd.sonovel.web.servlet;

import com.pcdd.sonovel.model.LocalTaskState;
import com.pcdd.sonovel.repository.TaskStateRepository;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 返回本地 tasks.json 的精简视图（不含章节正文 / 完整 ChapterRef 列表），
 * 供 WebUI"上报任务"区块渲染。
 *
 * <pre>GET /tasks</pre>
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class TaskListServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        TaskStateRepository repo = new TaskStateRepository(System.getProperty("user.dir"));
        List<Map<String, Object>> view = repo.list().stream()
                .map(TaskListServlet::summarize)
                .toList();
        RespUtils.writeJson(resp, view);
    }

    private static Map<String, Object> summarize(LocalTaskState s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", s.getTaskId());
        m.put("bookId", s.getBookId());
        m.put("sourceName", s.getSourceName());
        m.put("bookUrl", s.getBookUrl());
        m.put("status", s.getStatus() == null ? null : s.getStatus().name());
        m.put("chapterCount", s.getChapters() == null ? 0 : s.getChapters().size());
        m.put("rejectedCount", s.getRejected() == null ? 0 : s.getRejected().size());
        m.put("errorMessage", s.getErrorMessage());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }
}
