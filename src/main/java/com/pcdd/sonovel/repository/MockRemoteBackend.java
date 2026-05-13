package com.pcdd.sonovel.repository;

import com.pcdd.sonovel.exception.RemoteBackendException;
import com.pcdd.sonovel.model.remote.RemoteBookInfo;
import com.pcdd.sonovel.model.remote.RemotePingResponse;
import com.pcdd.sonovel.model.remote.RemotePushRequest;
import com.pcdd.sonovel.model.remote.RemotePushResponse;
import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.util.Collections;

/**
 * AI 小说后台 mock 实现，用于 base-url 为空 / mock=1 / 测试场景。
 * <p>
 * 行为约定：
 * <ul>
 *   <li>{@code bookId=999999} 视为"后台不存在该书"，抛 RemoteBackendException</li>
 *   <li>{@code bookId=0} 视为"无章节新书"，{@code latest_chapter_no=0} + 空标题</li>
 *   <li>其他 bookId 一律返回一本预设书 ("斗破苍穹" / 已有 1245 章)</li>
 *   <li>reportChapters 一律全部 accepted，updated=0，rejected=[]</li>
 * </ul>
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
@UtilityClass
public class MockRemoteBackend {

    public RemoteBookInfo getBookInfo(int bookId) {
        if (bookId == 999999) {
            throw new RemoteBackendException("书籍不存在 (mock)");
        }
        if (bookId == 0) {
            return RemoteBookInfo.builder()
                    .bookId(bookId)
                    .bookName("空书 (mock)")
                    .author("无")
                    .latestChapterNo(0)
                    .latestChapterTitle("")
                    .updatedAt("")
                    .build();
        }
        return RemoteBookInfo.builder()
                .bookId(bookId)
                .bookName("斗破苍穹")
                .author("天蚕土豆")
                .latestChapterNo(1245)
                .latestChapterTitle("第 1245 章 异界之旅")
                .updatedAt("2026-05-10T03:15:22Z")
                .build();
    }

    public RemotePushResponse reportChapters(RemotePushRequest req) {
        int n = req.getChapters() == null ? 0 : req.getChapters().size();
        return RemotePushResponse.builder()
                .acceptedCount(n)
                .updatedCount(0)
                .rejected(Collections.emptyList())
                .build();
    }

    public RemotePingResponse ping() {
        return RemotePingResponse.builder()
                .serverTime(Instant.now().toString())
                .apiVersion("v1")
                .build();
    }
}
