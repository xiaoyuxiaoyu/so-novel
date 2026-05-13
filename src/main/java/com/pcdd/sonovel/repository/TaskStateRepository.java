package com.pcdd.sonovel.repository;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.model.LocalTaskState;
import com.pcdd.sonovel.model.remote.RemoteRejectedChapter;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * tasks.json 读写仓库。整个文件按 {@code Map<taskId, LocalTaskState>} 结构存储。
 * <p>
 * 所有写操作 {@code synchronized}（本期不引入更复杂锁；并发回推 / 重推同一 task
 * 不应同时发生，UI 层已保证）。
 * <p>
 * 字段 JSON 名约定：与 Java 字段同名（驼峰），不做 snake_case 转换。这是
 * 客户端私有文件，不与 AI 后台契约共享。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
public class TaskStateRepository {

    private final File tasksFile;

    /**
     * @param workDir 通常传 {@code System.getProperty("user.dir")}；
     *                tasks.json 落地路径为 {@code ${workDir}/.so-novel/tasks.json}
     */
    public TaskStateRepository(String workDir) {
        File dir = FileUtil.mkdir(new File(workDir + File.separator + ".so-novel"));
        this.tasksFile = new File(dir, "tasks.json");
    }

    public File getTasksFile() {
        return tasksFile;
    }

    public synchronized Optional<LocalTaskState> get(String taskId) {
        return Optional.ofNullable(loadAll().get(taskId));
    }

    public synchronized List<LocalTaskState> list() {
        return loadAll().values().stream()
                .sorted(Comparator.comparing(
                        (LocalTaskState s) -> s.getCreatedAt() == null ? 0L : s.getCreatedAt())
                        .reversed())
                .toList();
    }

    public synchronized List<LocalTaskState> listPending() {
        return list().stream()
                .filter(s -> s.getStatus() == LocalTaskState.Status.DOWNLOADED_NOT_PUSHED
                        || s.getStatus() == LocalTaskState.Status.PARTIAL
                        || s.getStatus() == LocalTaskState.Status.FAILED)
                .toList();
    }

    public synchronized void save(LocalTaskState st) {
        Map<String, LocalTaskState> all = loadAll();
        st.setUpdatedAt(System.currentTimeMillis());
        if (st.getCreatedAt() == null) {
            st.setCreatedAt(st.getUpdatedAt());
        }
        all.put(st.getTaskId(), st);
        writeAll(all);
    }

    public synchronized void markPushed(String taskId) {
        updateStatus(taskId, s -> {
            s.setStatus(LocalTaskState.Status.PUSHED);
            s.setRejected(null);
            s.setErrorMessage(null);
        });
    }

    public synchronized void markPartial(String taskId, List<RemoteRejectedChapter> rejected) {
        updateStatus(taskId, s -> {
            s.setStatus(LocalTaskState.Status.PARTIAL);
            s.setRejected(rejected);
            s.setErrorMessage(null);
        });
    }

    public synchronized void markFailed(String taskId, String errorMessage) {
        updateStatus(taskId, s -> {
            s.setStatus(LocalTaskState.Status.FAILED);
            s.setErrorMessage(errorMessage);
        });
    }

    // ---------- 内部 ----------

    private void updateStatus(String taskId, java.util.function.Consumer<LocalTaskState> mutator) {
        Map<String, LocalTaskState> all = loadAll();
        LocalTaskState st = all.get(taskId);
        if (st == null) {
            return;
        }
        mutator.accept(st);
        st.setUpdatedAt(System.currentTimeMillis());
        all.put(taskId, st);
        writeAll(all);
    }

    private Map<String, LocalTaskState> loadAll() {
        if (!tasksFile.exists() || tasksFile.length() == 0) {
            return new LinkedHashMap<>();
        }
        String raw = FileUtil.readUtf8String(tasksFile);
        JSONObject json;
        try {
            json = JSONUtil.parseObj(raw);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
        Map<String, LocalTaskState> map = new LinkedHashMap<>();
        for (String key : json.keySet()) {
            JSONObject item = json.getJSONObject(key);
            if (item == null) continue;
            map.put(key, parseState(item));
        }
        return map;
    }

    private void writeAll(Map<String, LocalTaskState> all) {
        JSONObject root = new JSONObject();
        all.forEach((k, v) -> root.set(k, serializeState(v)));
        FileUtil.writeUtf8String(JSONUtil.toJsonPrettyStr(root), tasksFile);
    }

    private LocalTaskState parseState(JSONObject o) {
        LocalTaskState.LocalTaskStateBuilder b = LocalTaskState.builder()
                .taskId(o.getStr("taskId"))
                .bookId(o.getInt("bookId"))
                .sourceId(o.getInt("sourceId"))
                .sourceName(o.getStr("sourceName"))
                .bookUrl(o.getStr("bookUrl"))
                .errorMessage(o.getStr("errorMessage"))
                .createdAt(o.getLong("createdAt"))
                .updatedAt(o.getLong("updatedAt"));

        String statusStr = o.getStr("status");
        if (statusStr != null) {
            try {
                b.status(LocalTaskState.Status.valueOf(statusStr));
            } catch (IllegalArgumentException ignored) {
            }
        }

        JSONArray chArr = o.getJSONArray("chapters");
        if (chArr != null) {
            List<LocalTaskState.ChapterRef> chs = new ArrayList<>(chArr.size());
            for (int i = 0; i < chArr.size(); i++) {
                JSONObject c = chArr.getJSONObject(i);
                chs.add(LocalTaskState.ChapterRef.builder()
                        .sourceOrder(c.getInt("sourceOrder"))
                        .chapterNo(c.getInt("chapterNo"))
                        .title(c.getStr("title"))
                        .build());
            }
            b.chapters(chs);
        }

        JSONArray rejArr = o.getJSONArray("rejected");
        if (rejArr != null) {
            List<RemoteRejectedChapter> rs = new ArrayList<>(rejArr.size());
            for (int i = 0; i < rejArr.size(); i++) {
                JSONObject r = rejArr.getJSONObject(i);
                rs.add(RemoteRejectedChapter.builder()
                        .chapterNo(r.getInt("chapterNo"))
                        .title(r.getStr("title"))
                        .reason(r.getStr("reason"))
                        .build());
            }
            b.rejected(rs);
        }

        return b.build();
    }

    private JSONObject serializeState(LocalTaskState st) {
        JSONObject o = new JSONObject();
        o.set("taskId", st.getTaskId());
        o.set("bookId", st.getBookId());
        o.set("sourceId", st.getSourceId());
        o.set("sourceName", st.getSourceName());
        o.set("bookUrl", st.getBookUrl());
        o.set("status", st.getStatus() == null ? null : st.getStatus().name());
        o.set("errorMessage", st.getErrorMessage());
        o.set("createdAt", st.getCreatedAt());
        o.set("updatedAt", st.getUpdatedAt());

        if (st.getChapters() != null) {
            JSONArray arr = new JSONArray();
            for (LocalTaskState.ChapterRef c : st.getChapters()) {
                JSONObject co = new JSONObject();
                co.set("sourceOrder", c.getSourceOrder());
                co.set("chapterNo", c.getChapterNo());
                co.set("title", c.getTitle());
                arr.add(co);
            }
            o.set("chapters", arr);
        }
        if (st.getRejected() != null) {
            JSONArray arr = new JSONArray();
            for (RemoteRejectedChapter r : st.getRejected()) {
                JSONObject ro = new JSONObject();
                ro.set("chapterNo", r.getChapterNo());
                ro.set("title", r.getTitle());
                ro.set("reason", r.getReason());
                arr.add(ro);
            }
            o.set("rejected", arr);
        }
        return o;
    }
}
