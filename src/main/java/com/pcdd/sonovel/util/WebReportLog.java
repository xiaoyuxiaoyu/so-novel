package com.pcdd.sonovel.util;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.experimental.UtilityClass;

import java.io.File;

/**
 * 网页端回推 AI 后台链路专用日志，单独写到 {@code logs/web-report-{yyyy-MM-dd}.log}，
 * 按天滚动。与 {@link LogUtils}（章节下载日志，按"书名+作者"命名）分离，关注点不同：
 * <ul>
 *   <li>{@link LogUtils}：单本书的章节抓取/重试明细</li>
 *   <li>{@link WebReportLog}：跨多本书的回推任务流水（HTTP 请求/响应、batch 进度、重试 cause）</li>
 * </ul>
 *
 * <p>用法：在 servlet 入口 {@link #setTask(String)} 把 taskId 放进 ThreadLocal，
 * 之后任何嵌套调用打的日志行都会自动带上 {@code [task=xxx]} 前缀，便于过滤同一次回推的全部日志。
 * Servlet 结束前必须 {@link #clearTask()} 防止线程复用串台。
 *
 * <pre>
 * WebReportLog.setTask(taskId);
 * try {
 *     WebReportLog.info("download begin, chapters={}", n);
 *     ...
 * } finally {
 *     WebReportLog.clearTask();
 * }
 * </pre>
 *
 * 不依赖 {@link com.pcdd.sonovel.context.BookContext}，因此回推阶段（Crawler.crawl 结束后）也可安全调用。
 *
 * @author 石宇涛
 * Created at 2026/5/14
 */
@UtilityClass
public class WebReportLog {

    private static final ThreadLocal<String> CURRENT_TASK = new ThreadLocal<>();

    public static void setTask(String taskId) {
        if (StrUtil.isNotBlank(taskId)) {
            CURRENT_TASK.set(taskId);
        }
    }

    public static void clearTask() {
        CURRENT_TASK.remove();
    }

    public static File getLogFile() {
        String fileName = StrUtil.format("web-report {}.log", DateUtil.today());
        return FileUtil.touch("logs", fileName);
    }

    public static void info(String template, Object... args) {
        write("INFO", template, args, null);
    }

    public static void warn(String template, Object... args) {
        write("WARN", template, args, null);
    }

    public static void error(String template, Object... args) {
        write("ERROR", template, args, null);
    }

    public static void error(Throwable t, String template, Object... args) {
        write("ERROR", template, args, t);
    }

    private static void write(String level, String template, Object[] args, Throwable t) {
        String msg = StrUtil.format(template, args);
        String task = CURRENT_TASK.get();
        String taskPart = task == null ? "" : "[task=" + task + "] ";
        StringBuilder line = new StringBuilder();
        line.append('[').append(DateUtil.now()).append("] [").append(level).append("] ")
                .append(taskPart).append(msg).append('\n');
        if (t != null) {
            line.append(stackTrace(t));
        }
        try {
            FileUtil.appendUtf8String(line.toString(), getLogFile());
        } catch (Exception ignored) {
            // 日志失败不影响主流程
        }
    }

    private static String stackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.toString()).append('\n');
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("\tat ").append(el).append('\n');
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append("Caused by: ").append(stackTrace(cause));
        }
        return sb.toString();
    }
}
