package com.pcdd.sonovel.repository;

import cn.hutool.core.io.FileUtil;
import com.pcdd.sonovel.model.LocalTaskState;
import com.pcdd.sonovel.model.remote.RemoteRejectedChapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author 石宇涛
 * Created at 2026/5/13
 */
class TaskStateRepositoryTest {

    private Path tmpWorkDir;

    @BeforeEach
    void setup() throws Exception {
        tmpWorkDir = Files.createTempDirectory("rb-tasks-");
    }

    @AfterEach
    void cleanup() {
        if (tmpWorkDir != null) {
            FileUtil.del(tmpWorkDir.toFile());
        }
    }

    @Test
    void tasksFileCreatedUnderSoNovelDir() {
        TaskStateRepository repo = new TaskStateRepository(tmpWorkDir.toString());
        File expected = new File(tmpWorkDir.toFile(), ".so-novel/tasks.json");
        // 文件尚未写入，但父目录应该被 mkdir 创建
        assertTrue(expected.getParentFile().exists());
        assertEquals(expected.getAbsolutePath(), repo.getTasksFile().getAbsolutePath());
    }

    @Test
    void saveAndGetRoundtrip() {
        TaskStateRepository repo = new TaskStateRepository(tmpWorkDir.toString());
        LocalTaskState st = sampleState("task-1");
        repo.save(st);

        Optional<LocalTaskState> got = repo.get("task-1");
        assertTrue(got.isPresent());
        assertEquals("task-1", got.get().getTaskId());
        assertEquals(Integer.valueOf(123), got.get().getBookId());
        assertEquals("笔趣阁主站", got.get().getSourceName());
        assertEquals(2, got.get().getChapters().size());
        assertEquals(Integer.valueOf(501), got.get().getChapters().get(0).getSourceOrder());
        assertEquals(Integer.valueOf(101), got.get().getChapters().get(0).getChapterNo());
        assertEquals(LocalTaskState.Status.DOWNLOADED_NOT_PUSHED, got.get().getStatus());
        assertNotNull(got.get().getCreatedAt());
        assertNotNull(got.get().getUpdatedAt());
    }

    @Test
    void saveSecondTaskDoesNotOverwriteFirst() {
        TaskStateRepository repo = new TaskStateRepository(tmpWorkDir.toString());
        repo.save(sampleState("task-1"));
        repo.save(sampleState("task-2"));

        assertTrue(repo.get("task-1").isPresent());
        assertTrue(repo.get("task-2").isPresent());
        assertEquals(2, repo.list().size());
    }

    @Test
    void markPushed_updatesStatusAndClearsRejected() {
        TaskStateRepository repo = new TaskStateRepository(tmpWorkDir.toString());
        LocalTaskState st = sampleState("task-1");
        st.setRejected(List.of(rejection(105, "content_oversize")));
        st.setStatus(LocalTaskState.Status.PARTIAL);
        repo.save(st);

        repo.markPushed("task-1");

        LocalTaskState got = repo.get("task-1").orElseThrow();
        assertEquals(LocalTaskState.Status.PUSHED, got.getStatus());
        assertNull(got.getRejected());
        assertNull(got.getErrorMessage());
    }

    @Test
    void markPartial_storesRejected() {
        TaskStateRepository repo = new TaskStateRepository(tmpWorkDir.toString());
        repo.save(sampleState("task-1"));

        repo.markPartial("task-1", List.of(
                rejection(105, "content_oversize"),
                rejection(106, "content_empty")
        ));

        LocalTaskState got = repo.get("task-1").orElseThrow();
        assertEquals(LocalTaskState.Status.PARTIAL, got.getStatus());
        assertEquals(2, got.getRejected().size());
        assertEquals("content_oversize", got.getRejected().get(0).getReason());
    }

    @Test
    void markFailed_storesErrorMessage() {
        TaskStateRepository repo = new TaskStateRepository(tmpWorkDir.toString());
        repo.save(sampleState("task-1"));

        repo.markFailed("task-1", "AI 后台连接失败");

        LocalTaskState got = repo.get("task-1").orElseThrow();
        assertEquals(LocalTaskState.Status.FAILED, got.getStatus());
        assertEquals("AI 后台连接失败", got.getErrorMessage());
    }

    @Test
    void listPending_includesNonPushedStatuses() {
        TaskStateRepository repo = new TaskStateRepository(tmpWorkDir.toString());
        LocalTaskState a = sampleState("task-A");
        a.setStatus(LocalTaskState.Status.PUSHED);
        LocalTaskState b = sampleState("task-B");
        b.setStatus(LocalTaskState.Status.PARTIAL);
        LocalTaskState c = sampleState("task-C");
        c.setStatus(LocalTaskState.Status.FAILED);
        LocalTaskState d = sampleState("task-D");
        d.setStatus(LocalTaskState.Status.DOWNLOADED_NOT_PUSHED);
        repo.save(a);
        repo.save(b);
        repo.save(c);
        repo.save(d);

        List<LocalTaskState> pending = repo.listPending();
        assertEquals(3, pending.size());
        // 不应包含 task-A (PUSHED)
        assertTrue(pending.stream().noneMatch(s -> "task-A".equals(s.getTaskId())));
    }

    @Test
    void persistAcrossInstances() {
        TaskStateRepository repo1 = new TaskStateRepository(tmpWorkDir.toString());
        repo1.save(sampleState("task-1"));

        TaskStateRepository repo2 = new TaskStateRepository(tmpWorkDir.toString());
        Optional<LocalTaskState> got = repo2.get("task-1");
        assertTrue(got.isPresent());
        assertEquals(2, got.get().getChapters().size());
    }

    @Test
    void missingFile_returnsEmpty() {
        TaskStateRepository repo = new TaskStateRepository(tmpWorkDir.toString());
        assertTrue(repo.get("does-not-exist").isEmpty());
        assertTrue(repo.list().isEmpty());
    }

    @Test
    void markOnNonexistentTask_isNoop() {
        TaskStateRepository repo = new TaskStateRepository(tmpWorkDir.toString());
        // 不应抛异常，但也不应该创建任务
        repo.markPushed("nope");
        assertTrue(repo.get("nope").isEmpty());
    }

    // ---------- helpers ----------

    private LocalTaskState sampleState(String taskId) {
        return LocalTaskState.builder()
                .taskId(taskId)
                .bookId(123)
                .sourceId(1)
                .sourceName("笔趣阁主站")
                .bookUrl("https://www.bqg.com/book/12345/")
                .chapters(List.of(
                        LocalTaskState.ChapterRef.builder().sourceOrder(501).chapterNo(101).title("第 101 章 A").build(),
                        LocalTaskState.ChapterRef.builder().sourceOrder(502).chapterNo(102).title("第 102 章 B").build()
                ))
                .status(LocalTaskState.Status.DOWNLOADED_NOT_PUSHED)
                .build();
    }

    private RemoteRejectedChapter rejection(int chapterNo, String reason) {
        return RemoteRejectedChapter.builder()
                .chapterNo(chapterNo)
                .title("章 " + chapterNo)
                .reason(reason)
                .build();
    }
}
