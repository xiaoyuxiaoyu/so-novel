package com.pcdd.sonovel.repository;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.exception.RemoteBackendException;
import com.pcdd.sonovel.model.remote.RemoteBookInfo;
import com.pcdd.sonovel.model.remote.RemoteChapterPushItem;
import com.pcdd.sonovel.model.remote.RemoteClientMeta;
import com.pcdd.sonovel.model.remote.RemotePingResponse;
import com.pcdd.sonovel.model.remote.RemotePushRequest;
import com.pcdd.sonovel.model.remote.RemotePushResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 mock 分支 + snake_case 编解码 + 业务错误抛异常。
 * 真实 HTTP 的 5xx 重试 / 超时留 M5 联调验证。
 *
 * @author 石宇涛
 * Created at 2026/5/13
 */
class RemoteBackendClientTest {

    // ---------- MockRemoteBackend ----------

    @Test
    void mock_getBookInfo_returnsPresetBook() {
        RemoteBookInfo info = MockRemoteBackend.getBookInfo(123);
        assertEquals(123, info.getBookId());
        assertEquals("斗破苍穹", info.getBookName());
        assertEquals("天蚕土豆", info.getAuthor());
        assertEquals(1245, info.getLatestChapterNo());
        assertTrue(info.getLatestChapterTitle().contains("第 1245 章"));
    }

    @Test
    void mock_getBookInfo_999999_throws() {
        assertThrows(RemoteBackendException.class, () -> MockRemoteBackend.getBookInfo(999999));
    }

    @Test
    void mock_getBookInfo_0_returnsEmptyBook() {
        RemoteBookInfo info = MockRemoteBackend.getBookInfo(0);
        assertEquals(0, info.getLatestChapterNo());
        assertEquals("", info.getLatestChapterTitle());
    }

    @Test
    void mock_reportChapters_allAccepted() {
        RemotePushRequest req = RemotePushRequest.builder()
                .bookId(123)
                .chapters(List.of(
                        RemoteChapterPushItem.builder().chapterNo(1246).title("第 1246 章 归途").content("正文 A").build(),
                        RemoteChapterPushItem.builder().chapterNo(1247).title("第 1247 章 故人").content("正文 B").build()
                ))
                .clientMeta(sampleMeta())
                .build();
        RemotePushResponse resp = MockRemoteBackend.reportChapters(req);
        assertEquals(2, resp.getAcceptedCount());
        assertEquals(0, resp.getUpdatedCount());
        assertTrue(resp.getRejected().isEmpty());
    }

    @Test
    void mock_ping_returnsV1() {
        RemotePingResponse resp = MockRemoteBackend.ping();
        assertEquals("v1", resp.getApiVersion());
        assertNotNull(resp.getServerTime());
    }

    // ---------- snake_case 编解码 ----------

    @Test
    void parseBookInfo_snakeCaseToCamel() {
        JSONObject data = JSONUtil.parseObj("""
                {
                    "book_id": 123,
                    "book_name": "斗破苍穹",
                    "author": "天蚕土豆",
                    "latest_chapter_no": 1245,
                    "latest_chapter_title": "第 1245 章 异界之旅",
                    "updated_at": "2026-05-10T03:15:22Z"
                }
                """);
        RemoteBookInfo info = RemoteBackendClient.parseBookInfo(data);
        assertEquals(123, info.getBookId());
        assertEquals("斗破苍穹", info.getBookName());
        assertEquals(1245, info.getLatestChapterNo());
        assertEquals("第 1245 章 异界之旅", info.getLatestChapterTitle());
        assertEquals("2026-05-10T03:15:22Z", info.getUpdatedAt());
    }

    @Test
    void parseBookInfo_emptyBookHas0AndEmptyTitle() {
        JSONObject data = JSONUtil.parseObj("""
                {"book_id":99,"book_name":"新书","author":"X","latest_chapter_no":0,"latest_chapter_title":"","updated_at":""}
                """);
        RemoteBookInfo info = RemoteBackendClient.parseBookInfo(data);
        assertEquals(0, info.getLatestChapterNo());
        assertEquals("", info.getLatestChapterTitle());
    }

    @Test
    void serializePushRequest_outputsSnakeCase() {
        RemotePushRequest req = RemotePushRequest.builder()
                .bookId(123)
                .chapters(List.of(
                        RemoteChapterPushItem.builder().chapterNo(1246).title("第 1246 章 归途").content("正文").build()
                ))
                .clientMeta(sampleMeta())
                .build();

        JSONObject out = RemoteBackendClient.serializePushRequest(req);

        assertEquals(123, out.getInt("book_id"));
        JSONArray chs = out.getJSONArray("chapters");
        assertEquals(1, chs.size());
        JSONObject ch0 = chs.getJSONObject(0);
        assertEquals(1246, ch0.getInt("chapter_no"));
        assertEquals("第 1246 章 归途", ch0.getStr("title"));
        assertEquals("正文", ch0.getStr("content"));

        JSONObject meta = out.getJSONObject("client_meta");
        assertEquals("笔趣阁主站", meta.getStr("source_name"));
        assertEquals("https://www.bqg.com/book/12345/", meta.getStr("source_url"));
        assertEquals("web-1.4.0", meta.getStr("app_version"));

        // 严防驼峰漏写
        assertNull(out.get("bookId"));
        assertNull(out.get("clientMeta"));
        assertNull(ch0.get("chapterNo"));
    }

    @Test
    void parsePushResponse_withRejected() {
        JSONObject data = JSONUtil.parseObj("""
                {
                    "accepted_count": 48,
                    "updated_count": 2,
                    "rejected": [
                        {"chapter_no": 1250, "title": "第 1250 章 X", "reason": "content_oversize"}
                    ]
                }
                """);
        RemotePushResponse resp = RemoteBackendClient.parsePushResponse(data);
        assertEquals(48, resp.getAcceptedCount());
        assertEquals(2, resp.getUpdatedCount());
        assertEquals(1, resp.getRejected().size());
        assertEquals(1250, resp.getRejected().get(0).getChapterNo());
        assertEquals("content_oversize", resp.getRejected().get(0).getReason());
    }

    @Test
    void parsePushResponse_emptyRejected() {
        JSONObject data = JSONUtil.parseObj("""
                {"accepted_count": 5, "updated_count": 0, "rejected": []}
                """);
        RemotePushResponse resp = RemoteBackendClient.parsePushResponse(data);
        assertTrue(resp.getRejected().isEmpty());
    }

    // ---------- helpers ----------

    private RemoteClientMeta sampleMeta() {
        return RemoteClientMeta.builder()
                .sourceName("笔趣阁主站")
                .sourceUrl("https://www.bqg.com/book/12345/")
                .appVersion("web-1.4.0")
                .build();
    }
}
