# 网页端下载 AI 小说后台对接 · 开发文档

> 起草日期：2026-05-13 | 起草人：石宇涛 | 状态：实施中
> 关联方案：[网页端下载-AI小说后台对接方案.md](./网页端下载-AI小说后台对接方案.md)（含 v0.2 修订说明，**实施时以该修订为准**）
> 关联接口契约：[网页端下载-AI小说后台对接接口文档.md](./网页端下载-AI小说后台对接接口文档.md)（v0.1）
> 协作约定：[CLAUDE.md](../CLAUDE.md) § 1~§ 2（分支拓扑 + 改代码约束）

本文是 `feat/web-active-report` 分支的**实施清单**。每个里程碑给出可勾选 TODO + 关键模块代码骨架 + 验收点。代码骨架仅作"该写在哪、长什么样"的参考，最终实现以方案 / 接口文档为准。

---

## 0 实施前必读

### 0.1 本期相对原方案的简化点（来自 2026-05-13 澄清轮次）

| # | 简化点 | 影响 |
|---|---|---|
| ① | **只支持追加，不支持覆盖错章**：上报 `chapter_no` 一律 `latest_chapter_no + 1` 起递增；未命中分支用户选源站锚点 idx' 仅作"下载起点"，回推序号仍接 K+1 | 弹窗去掉"覆盖"概念，UI 简化；后台 upsert 语义保留作为幂等保障 |
| ② | **搜索精确过滤全部下沉服务端**：`exactMatch=true` 时由 `AggregatedSearchServlet` 一次性按"书名 + 作者均 trim 严格相等"过滤，前端 JS 不再二次过滤 | 前端只负责传参 + 渲染 |
| ③ | **长请求模型沿用 `/book-fetch`**：fetch 同步 HTTP 挂连接到全流程结束才返回；进度走既有 `/download-progress` SSE 通道（新增 `report-progress` 事件类型） | 不引入异步任务/轮询 |
| ④ | **回推章节正文：内存收集 + reports/ 目录两件套**：`Crawler` 改造为可接收"回推收集器"，每章解析完同步落盘 + 累加到内存 list；本次任务完成后内存直推；同时把纯文本副本写到 `${workDir}/.so-novel/reports/{taskId}/{chapterNo}.txt`，给"重新上报"按钮用 | 与下载格式（EPUB/PDF/TXT/HTML）解耦 |
| ⑤ | **`feat/web-active-report` 待新建**：从 `main` 当前 commit 起新分支；首批提交先把 CLAUDE.md + 三份 docs 合到 `main` 再开分支 | 见 M0 |

### 0.2 现状代码摸底（写代码前必读）

| 关注点 | 位置 | 关键事实 |
|---|---|---|
| Servlet 路由注册 | `web/WebServer.java:41-53` | `registerServlets()` 单点添加；新增 Servlet 需在此挂载 |
| 既有下载长请求 | `web/servlet/BookFetchServlet.java` + `core/Crawler.java` | 前端 `fetch('/book-fetch?...')` 同步挂；服务端 `Crawler.crawl` 内部每章解析完**立刻写盘并丢弃 Chapter 对象** |
| SSE 进度通道 | `web/servlet/DownloadProgressSseServlet.java` + `core/Crawler.java:125-131` | 静态 `sendProgress(json)` 广播给所有客户端；现在只推 `download-progress` 类型 |
| 章节落盘格式 | `core/Crawler.java:164-179` `generateChapterPath` | TXT 单文件；HTML/EPUB/PDF 走 html 中间格式后由 `CrawlerPostHandler` 后处理（**EPUB/PDF 取不到原章节正文**，这是要做"两件套"的根本原因） |
| 聚合搜索 | `action/AggregatedSearchAction.getSearchResults` + `handle/SearchResultsHandler.filterSort` | `filterSort` 当前按"猜书名 vs 作者"做权重；本期网页端要绕过它（CLI 不受影响） |
| 配置加载 | `core/AppConfigLoader.java` + `model/AppConfig.java` | hutool `Setting` 按 section 读；新增 `[remote-backend]` 必须按现有 SELECTION_* 常量风格扩展 |
| 前端单文件 | `src/main/resources/static/index.html` | 所有 HTML/CSS/JS 都在一份文件里；搜索栏 / 表格 / 下载下拉菜单 / SSE 监听都在此 |

### 0.3 工程纪律

- **路径写绝对**：所有 TODO 引用 `src/main/java/com/pcdd/sonovel/...` 而不是相对路径
- **新增类全在 `feat/web-active-report` 上提**，不动 `main`（参考 CLAUDE.md § 2.1）
- **既有 Servlet 改动必须保持 CLI 兼容**：CLI 不传新参数时走老逻辑
- **commit message 用 gitmoji 前缀**（`:sparkles:` 新功能 / `:bug:` 修复 / `:recycle:` 重构）
- **禁止硬编码外部域名 / token**：一律走 `[remote-backend]` 配置

---

## 1 里程碑总览

| 里程碑 | 内容 | 估时 | 状态 |
|---|---|---|---|
| M0 | 分支前置 + 文档归位 | 0.5 天 | ⬜ |
| M1 | 配置层 + 远端 HTTP 客户端 + DTO + mock | 1 天 | ⬜ |
| M2 | 搜索栏精确化（服务端单点过滤） | 0.5 天 | ⬜ |
| M3 | 下载弹窗 + ID 校验 + 反查 + 锚点 + `Crawler` 改造 + reports 副本 | 2 天 | ⬜ |
| M4 | 回推链路 + 任务状态持久化 + 重推入口 + 健康检查 | 1.5 天 | ⬜ |
| M5 | 联调（mock + 真实后台）+ 回归（CLI / 既有下载格式） | 1 天 | ⬜ |

**总计 ~6.5 天**（不含 AI 后台同学接口开发时间）。

---

## 2 M0 · 分支前置

### 2.1 TODO

- [ ] 在 `main` 上 commit 以下 4 个文件：`CLAUDE.md` / `docs/网页端下载-AI小说后台对接方案.md` / `docs/网页端下载-AI小说后台对接接口文档.md` / `docs/网页端下载-AI小说后台对接开发文档.md`（即本文）。commit message 建议：`:memo: 添加网页端下载 AI 后台对接方案/接口/开发文档及协作说明 CLAUDE.md`
- [ ] `git checkout -b feat/web-active-report`
- [ ] （可选）`git push -u origin feat/web-active-report`
- [ ] 在新分支根目录创建 `.gitignore` 条目（如未有）：`.so-novel/`（任务状态 + reports 副本目录，不入库）

### 2.2 验收

- `main` 与 `feat/web-active-report` 上都能看到 4 份文档
- `git log --oneline main..feat/web-active-report` 应为空（新分支尚未发散）
- `git branch --show-current` 在新分支

---

## 3 M1 · 配置层 + 远端 HTTP 客户端 + DTO + mock

### 3.1 TODO

- [ ] `bundle/config.ini` 末尾追加 `[remote-backend]` section（见 § 3.2.1）
- [ ] 扩展 `model/AppConfig.java`：新增 `RemoteBackendConfig remoteBackend` 字段（见 § 3.2.2）
- [ ] 新建 `model/RemoteBackendConfig.java`
- [ ] `core/AppConfigLoader.loadConfig()`：新增 SELECTION_REMOTE_BACKEND 常量 + 读 8 项配置；校验"mock=0 且 base-url/api-key 任一为空"时 throw + 终止启动
- [ ] 新建 `model/remote/` 目录与 7 个 DTO（见 § 3.2.3）
- [ ] 新建 `exception/RemoteBackendException`
- [ ] 新建 `repository/RemoteBackendClient`（见 § 3.2.4），实现 `getBookInfo` / `reportChapters` / `ping` 三个方法 + mock 分支 + 重试包装
- [ ] 单测：`RemoteBackendClientTest`（mock 分支覆盖 / `code=0` 抛异常 / 5xx 重试 2 次 / 超时）

### 3.2 关键模块代码骨架

#### 3.2.1 `bundle/config.ini` 追加段落

参考方案 § 2.5 的 8 项配置，整段复制即可。落地后该 section 与已有的 `[global]` `[download]` `[source]` `[crawl]` `[web]` `[cookie]` `[proxy]` 同级。

#### 3.2.2 `model/RemoteBackendConfig.java`

```java
package com.pcdd.sonovel.model;

import lombok.Data;

@Data
public class RemoteBackendConfig {
    private String baseUrl;                     // 空 → 自动 mock
    private String apiKey;                       // mock=0 时必填
    private Integer connectTimeoutMs;            // 默认 5000
    private Integer readTimeoutMs;               // 默认 30000
    private Integer mock;                        // 1=强制 mock，0=真实链路，null=按 baseUrl 自动
    private Integer pushBatchWarnThreshold;      // 默认 500
    private Integer pushBatchRejectThreshold;    // 默认 2000
    private Integer startupPing;                 // 默认 1

    public boolean isMockMode() {
        if (mock != null) return mock == 1;
        return baseUrl == null || baseUrl.isBlank();
    }
}
```

并在 `AppConfig.java` 末尾追加 `private RemoteBackendConfig remoteBackend;`。

#### 3.2.3 DTO 一览（`model/remote/`）

| 文件 | 主要字段（驼峰；JSON 落地 snake_case） |
|---|---|
| `RemoteBookInfo.java` | `bookId / bookName / author / latestChapterNo / latestChapterTitle / updatedAt` |
| `RemoteChapterPushItem.java` | `chapterNo / title / content` |
| `RemoteClientMeta.java` | `sourceName / sourceUrl / appVersion` |
| `RemotePushRequest.java` | `bookId / chapters: List<RemoteChapterPushItem> / clientMeta` |
| `RemoteRejectedChapter.java` | `chapterNo / title / reason`（嵌在 `RemotePushResponse.rejected[]` 内） |
| `RemotePushResponse.java` | `acceptedCount / updatedCount / rejected: List<RemoteRejectedChapter>` |
| `RemotePingResponse.java` | `serverTime / apiVersion` |
| `RemoteApiResult<T>.java` | `code / status / msg / data: T`（统一响应壳） |

**序列化**：用 hutool `JSONUtil` 时手动处理 snake_case 映射；或引入 `@JsonProperty`/`@JsonNaming(SnakeCaseStrategy)`。推荐前者，保持与项目现有依赖一致。

#### 3.2.4 `repository/RemoteBackendClient.java` 骨架

```java
package com.pcdd.sonovel.repository;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.core.AppConfigLoader;
import com.pcdd.sonovel.exception.RemoteBackendException;
import com.pcdd.sonovel.model.RemoteBackendConfig;
import com.pcdd.sonovel.model.remote.*;
import lombok.experimental.UtilityClass;

import java.util.Map;

@UtilityClass
public class RemoteBackendClient {

    private static final String PATH_GET_BOOK_INFO   = "/api/book-collector/web-downloader/get_book_info";
    private static final String PATH_REPORT_CHAPTERS = "/api/book-collector/web-downloader/report_chapters";
    private static final String PATH_PING            = "/api/book-collector/web-downloader/ping";

    public RemoteBookInfo getBookInfo(int bookId) {
        var cfg = AppConfigLoader.APP_CONFIG.getRemoteBackend();
        if (cfg.isMockMode()) return MockRemoteBackend.getBookInfo(bookId);
        return callJson(cfg, PATH_GET_BOOK_INFO, Map.of("book_id", bookId), RemoteBookInfo.class);
    }

    public RemotePushResponse reportChapters(RemotePushRequest req) {
        var cfg = AppConfigLoader.APP_CONFIG.getRemoteBackend();
        if (cfg.isMockMode()) return MockRemoteBackend.reportChapters(req);
        return callJson(cfg, PATH_REPORT_CHAPTERS, req, RemotePushResponse.class);
    }

    public RemotePingResponse ping() { /* ... */ }

    private <T> T callJson(RemoteBackendConfig cfg, String path, Object body, Class<T> dataClz) {
        String url = stripTrailingSlash(cfg.getBaseUrl()) + path;
        // 重试 2 次，间隔 2s / 5s；5xx 与网络异常重试；4xx 直接抛
        // 解析 RemoteApiResult，code=0 抛 RemoteBackendException(msg)
        // ...
    }
}
```

**mock 分支单独抽到** `repository/MockRemoteBackend.java`：硬编码返回一本"斗破苍穹"书况 + 模拟 `report_chapters` 全部 accepted。便于 M5 联调前先把端到端跑通。

### 3.3 验收

- 启动 `mvn test -Dtest=RemoteBackendClientTest` 全绿
- `mock=1` 时跑 mock；`mock=0` 且 `base-url`/`api-key` 缺失时启动报错并终止
- mock 模式调 `getBookInfo(123)` 能拿到固定假数据

---

## 4 M2 · 搜索栏精确化（服务端单点过滤）

### 4.1 TODO

- [ ] `web/servlet/AggregatedSearchServlet`：新增可选 `author` + `exactMatch` 入参；`exactMatch=true` 时绕过 `SearchResultsHandler.filterSort` 改走精确过滤；`exactMatch=true` 但 `author` 空 → 400
- [ ] `action/AggregatedSearchAction`：抽出 `getSearchResults(String kw, boolean skipFilterSort)` 重载，避免 CLI 与网页端共用时把 `filterSort` 误删
- [ ] `static/index.html`：搜索栏拆双字段；搜索按钮置灰逻辑（任一为空禁用）；`aggregatedSearch()` 传 `bookName + author + exactMatch=true`；**前端不做任何二次过滤**
- [ ] 测试：CLI 调用（不传 exactMatch）行为不变；网页端"书名+作者" 严格相等过滤生效

### 4.2 关键代码骨架

#### 4.2.1 Servlet 端

```java
// AggregatedSearchServlet.doGet 内：
String kw         = req.getParameter("kw");
String author     = req.getParameter("author");
String exactMatch = req.getParameter("exactMatch");
boolean isExact   = "true".equalsIgnoreCase(exactMatch);

if (isExact && StrUtil.isBlank(author)) {
    RespUtils.writeError(resp, 400, "exactMatch=true 时 author 必填");
    return;
}

List<SearchResult> results = AggregatedSearchAction.getSearchResults(kw, /*skipFilterSort=*/ isExact);

if (isExact) {
    String bookNameTrim = kw.trim();
    String authorTrim   = author.trim();
    results = results.stream()
            .filter(sr -> bookNameTrim.equals(StrUtil.trim(sr.getBookName())))
            .filter(sr -> authorTrim.equals(StrUtil.trim(sr.getAuthor())))
            .toList();
}
// ... searchLimit 处理（沿用既有逻辑）
RespUtils.writeJson(resp, results);
```

#### 4.2.2 `AggregatedSearchAction.getSearchResults` 重载

```java
public static List<SearchResult> getSearchResults(String kw) {
    return getSearchResults(kw, false);
}

public static List<SearchResult> getSearchResults(String kw, boolean skipFilterSort) {
    // ... 原有 latch 聚合逻辑不变 ...
    if (skipFilterSort) return new ArrayList<>(results); // 不再过相似度阈值
    return AppConfigLoader.APP_CONFIG.getSearchFilter() == 1
            ? SearchResultsHandler.filterSort(results, kw)
            : results;
}
```

#### 4.2.3 前端搜索栏

```html
<!-- index.html 搜索栏拆分 -->
<input id="downloadSearchName"   placeholder="书名（必填）" />
<input id="downloadSearchAuthor" placeholder="作者（必填）" />
<button id="searchButton" disabled>搜索</button>
```

```js
// JS 联动按钮置灰
const onInputChange = () => {
  searchButton.disabled =
    !downloadSearchName.value.trim() || !downloadSearchAuthor.value.trim()
}
downloadSearchName.addEventListener('input', onInputChange)
downloadSearchAuthor.addEventListener('input', onInputChange)

// aggregatedSearch 改造
const aggregatedSearch = async () => {
  const bookName = downloadSearchName.value.trim()
  const author   = downloadSearchAuthor.value.trim()
  if (!bookName || !author) return
  const url = `/search/aggregated?kw=${encodeURIComponent(bookName)}`
              + `&author=${encodeURIComponent(author)}`
              + `&exactMatch=true`
  const resp = await fetch(url)
  const data = await resp.json()
  // 直接渲染，前端不做二次过滤
  renderDownloadTable(data.data)
}
```

### 4.3 验收

- 网页端搜索：输入"斗破苍穹 / 天蚕土豆" → 只返回 `bookName == "斗破苍穹" && author == "天蚕土豆"` 的源站结果
- CLI 调用 `/search/aggregated?kw=xx`（不传 exactMatch）→ 行为与改造前完全一致

---

## 5 M3 · 下载弹窗 + Crawler 改造 + reports 副本

> 本节是工作量最大的一步，必须把 § 5.2 各模块都准备好再做联动。

### 5.1 TODO

- [ ] 新建 `web/servlet/RemoteBookInfoServlet`（GET `/remote-book-info?bookId=...`）：转发 `RemoteBackendClient.getBookInfo`
- [ ] 新建 `web/servlet/SourceTocServlet`（GET `/source-toc?url=...`）：调 `TocParser.parseAll`，返回 `List<{order, title}>`（剥 url 减体量）
- [ ] 新建 `core/IncrementalAnchorResolver`：`Optional<Integer> resolve(List<Chapter> toc, String lastTitleFromBackend)`；两侧 trim 后从尾向头严格相等匹配，多处命中取最靠后；**禁止任何归一化**
- [ ] 新建 `web/servlet/IncrementalDownloadServlet`（GET `/incremental-download?...`）：串联"查书况 → 反查 toc → [可选锚点] → 下载 → 收集 → 回推 → 落任务状态"
- [ ] `core/Crawler` 改造：`crawl(bookUrl, toc, ReportCollector collector)` 重载（见 § 5.2.3），既有 `crawl(bookUrl, toc)` 保持转发；不传 collector 时行为不变
- [ ] 新建 `core/ReportCollector`：内存累加 `List<RemoteChapterPushItem>` + 同步写 `${workDir}/.so-novel/reports/{taskId}/{chapterNo}.txt`
- [ ] 新建 `util/HtmlToPlainText`（如已有则复用）：剥 HTML 标签为纯文本（接口文档 § 决策 H：统一纯文本上报）
- [ ] 前端 `static/index.html`：把 "下载为 [格式]" 下拉菜单改为唯一一个"下载"按钮 → 触发弹窗组件
- [ ] 前端弹窗组件：三步式（输入 → 回显确认 → 命中直下 / 未命中选锚点）+ toc 抓取失败降级 "全量/中止"
- [ ] 在 `WebServer.registerServlets` 中挂载 4 个新 Servlet
- [ ] reports 目录 / tasks.json 父目录初始化（`${workDir}/.so-novel/` 不存在则创建）

### 5.2 关键模块代码骨架

#### 5.2.1 `IncrementalAnchorResolver`

```java
package com.pcdd.sonovel.core;

import com.pcdd.sonovel.model.Chapter;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;

@UtilityClass
public class IncrementalAnchorResolver {

    /**
     * 在源站 toc 里反查后台 latest_chapter_title。
     * 严格相等，仅两侧 trim，禁止做大小写 / 全半角 / 数字归一。
     * 多处命中取最靠后一处（应对源站有重复章节名场景）。
     * @return 命中位置在 toc 中的下标；未命中返回 empty
     */
    public Optional<Integer> resolve(List<Chapter> toc, String lastTitleFromBackend) {
        if (toc == null || toc.isEmpty() || lastTitleFromBackend == null) return Optional.empty();
        String target = lastTitleFromBackend.trim();
        if (target.isEmpty()) return Optional.empty();
        for (int i = toc.size() - 1; i >= 0; i--) {
            if (target.equals(toc.get(i).getTitle().trim())) return Optional.of(i);
        }
        return Optional.empty();
    }
}
```

#### 5.2.2 `IncrementalDownloadServlet` 主体

```java
// 入参：bookId / sourceId / bookUrl / startOrder（可选；未命中分支前端传入）
//      / format / language / concurrency
// 输出：JSON {taskId, status, accepted, updated, rejected[]}

public class IncrementalDownloadServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        // 1. 解析入参 + 校验（bookId 正整数 / bookUrl 非空 / startOrder 若有则 >= 1）
        // 2. 调 RemoteBackendClient.getBookInfo(bookId) → RemoteBookInfo bookInfo
        //    失败 → 返 400 + "未在后台找到该 ID"
        // 3. 拉源站 toc：tocParser.parseAll(bookUrl) → List<Chapter> fullToc
        //    失败 → 返 500 + "源站目录抓取失败"
        // 4. 计算下载范围：
        //    - 若 startOrder 入参非空 → from = startOrder - 1（未命中分支已让用户选过锚点）
        //    - 否则 → 反查 IncrementalAnchorResolver.resolve(fullToc, bookInfo.latestChapterTitle)
        //             命中 → from = idx + 1
        //             未命中 → 返特殊响应 {needAnchor: true, toc: [...top3 + 全本], latestTitle: ...}
        //                       前端切到锚点选择 UI；用户选完再带 startOrder 重发请求
        //    若 from >= fullToc.size() → 返 200 + "无新增章节"，不调 Crawler，不调回推
        // 5. 体量校验：subToc.size() 超 reject 阈值直接拒；超 warn 阈值且前端未传 confirm=true → 返特殊响应
        // 6. 生成 taskId（UUID）；构造 ReportCollector(taskId)
        // 7. crawler.crawl(bookUrl, subToc, collector) —— 同步阻塞到全本下完
        // 8. 构造 RemotePushRequest：chapter_no 起点 = bookInfo.latestChapterNo + 1
        //    chapters[] 从 collector.snapshot() 取，chapter_no 依次递增赋值
        // 9. 调 RemoteBackendClient.reportChapters(req)，失败重试 2 次（指数 2s/5s）
        // 10. TaskStateRepository.save(taskState)；写完返回最终 JSON 给前端
    }
}
```

#### 5.2.3 `Crawler` 改造

> 这是 M3 最敏感的改动。改完必须跑既有 CLI 下载回归（M5 会做）。

```java
// core/Crawler.java —— 新增重载，既有方法保持不变
public double crawl(String bookUrl, List<Chapter> toc) {
    return crawl(bookUrl, toc, null);   // 转发，无收集器
}

public double crawl(String bookUrl, List<Chapter> toc, ReportCollector collector) {
    // ... 原有逻辑保持不变 ...
    toc.forEach(item -> limiter.submit(() -> {
        Chapter parsed = chapterParser.parse(item);
        createChapterFile(parsed);                              // 原行为：按用户格式落盘

        if (collector != null && parsed != null) {              // ★ 新增 4 行
            collector.collect(parsed);                          //   内存累加 + 纯文本副本
        }

        // ... 原 SSE 进度推送不变 ...
    }));
}
```

#### 5.2.4 `ReportCollector`

```java
package com.pcdd.sonovel.core;

import cn.hutool.core.io.FileUtil;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.remote.RemoteChapterPushItem;
import com.pcdd.sonovel.util.HtmlToPlainText;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ReportCollector {
    private final String taskId;
    private final File   reportDir;
    private final ConcurrentLinkedQueue<RemoteChapterPushItem> queue = new ConcurrentLinkedQueue<>();

    public ReportCollector(String taskId, String workDir) {
        this.taskId    = taskId;
        this.reportDir = FileUtil.mkdir(new File(workDir + "/.so-novel/reports/" + taskId));
    }

    /** Crawler 内部每章解析完调一次（**线程安全**：Crawler 用虚拟线程并发） */
    public void collect(Chapter chapter) {
        String plain = HtmlToPlainText.from(chapter.getContent());
        // chapter_no 此刻先用 order 占位，IncrementalDownloadServlet 最终再按 K+1 重写
        var item = RemoteChapterPushItem.builder()
                .chapterNo(chapter.getOrder())
                .title(chapter.getTitle())
                .content(plain)
                .build();
        queue.add(item);
        // 同步落地纯文本副本（路径用 chapter.getOrder() 占位，回推前与 chapter_no 对齐）
        FileUtil.writeString(plain,
                new File(reportDir, chapter.getOrder() + ".txt"),
                StandardCharsets.UTF_8);
    }

    /** IncrementalDownloadServlet 在 crawl 返回后调用，拿到按 order 升序排好的列表 */
    public List<RemoteChapterPushItem> snapshot() {
        return queue.stream()
                .sorted((a, b) -> Integer.compare(a.getChapterNo(), b.getChapterNo()))
                .toList();
    }
}
```

> **重写 chapter_no 的位置**：`IncrementalDownloadServlet` 拿到 `snapshot()` 后，遍历列表把 `chapterNo` 从 `latest_chapter_no + 1` 起递增重赋；reports 目录里的文件名暂用源站 order，便于后续重推时通过 `tasks.json` 的 `(sourceStartIdx, chapterNoStart)` 推算映射。

#### 5.2.5 前端弹窗组件（在 `index.html` 内加一个 modal 容器）

```html
<div id="downloadModal" class="modal hidden">
  <!-- 第一步：输入 -->
  <section data-step="input">
    <label>AI 小说书籍 ID <input type="number" min="1" id="bookIdInput" /></label>
    <label>下载格式 <select id="formatSelect">...</select></label>
    <button id="modalConfirmInput" disabled>下一步</button>
  </section>
  <!-- 第二步：回显确认 -->
  <section data-step="confirm" hidden>
    <p>目标书：《<span id="m-book-name"></span>》— 作者：<span id="m-author"></span></p>
    <p>后台已有 <span id="m-latest-no"></span> 章，最后章节：<span id="m-latest-title"></span></p>
    <button id="modalStartDownload">确认下载</button>
  </section>
  <!-- 第三步分支 B：锚点选择 -->
  <section data-step="anchor" hidden>
    <p>未在源站目录里找到后台最后章节标题，请手动选择起点。</p>
    <input id="m-toc-search" placeholder="过滤章节…" />
    <ul id="m-toc-top3">建议候选 Top 3</ul>
    <ul id="m-toc-full">完整目录（分页）</ul>
    <button id="modalConfirmAnchor" disabled>从所选下一章开始下</button>
  </section>
  <!-- 第三步分支 C：toc 抓取失败 -->
  <section data-step="toc-failed" hidden>
    <button id="modalFallbackFull">全量下载</button>
    <button id="modalAbort">中止</button>
  </section>
</div>
```

```js
// 步骤切换 + 事件流（伪代码）
const openDownloadModal = (searchItem) => {
  show('input')
  modalConfirmInput.onclick = async () => {
    const info = await fetch(`/remote-book-info?bookId=${bookIdInput.value}`).then(r => r.json())
    if (info.code !== 1) return alert(info.msg)
    // 渲染 confirm 步
    show('confirm')
  }
  modalStartDownload.onclick = async () => {
    // 直接发起 incremental-download；若服务端返 needAnchor 切到 anchor 步
    const url = `/incremental-download?bookId=${...}&sourceId=${...}&bookUrl=${...}&format=${...}`
    const resp = await fetch(url).then(r => r.json())
    if (resp.needAnchor) {
      renderToc(resp.toc, resp.latestTitle); show('anchor')
    } else if (resp.tocFailed) {
      show('toc-failed')
    } else {
      // 完成，关弹窗，刷新本地书籍列表
    }
  }
  modalConfirmAnchor.onclick = async () => {
    const startOrder = selectedAnchorOrder + 1
    // 带 startOrder 重发
    await fetch(`/incremental-download?...&startOrder=${startOrder}`)
  }
}
```

### 5.3 验收

- mock 后台 + 命中分支：搜 → 选源 → 弹窗 → 输入 ID → 回显 → 确认 → 下载（SSE 推 download-progress）→ 完成
- mock 后台 + 未命中分支：弹窗切换到锚点选择 → 选某章 → 从下一章开始下
- 既有 CLI `crawler.crawl(bookUrl, toc)` 行为完全不变（不调 collector）
- reports 目录正确生成 `{chapterNo}.txt` 副本
- 既有 SSE `download-progress` 事件能正常推送，前端进度条工作

---

## 6 M4 · 回推链路 + 任务状态 + 重推 + 健康检查

### 6.1 TODO

- [ ] 新建 `model/LocalTaskState`：字段 `taskId / bookId / sourceId / bookUrl / sourceStartIdx / sourceEndIdx / chapterNoStart / chapterNoEnd / status: DOWNLOADED_NOT_PUSHED|PUSHED|PARTIAL|FAILED / rejected[] / createdAt`
- [ ] 新建 `repository/TaskStateRepository`：`tasks.json` 读写，方法 `save / markPushed / markPartial(rejected) / markFailed / listPending`
- [ ] `web/servlet/DownloadProgressSseServlet`：**API 不变**，新增 `sendReportProgress(phase, accepted, updated, rejectedSize)` 静态方法，发出 `type=report-progress` 事件
- [ ] `IncrementalDownloadServlet` 回推阶段在调 `reportChapters` 前后调用上述新方法推 `report-progress` 事件
- [ ] 新建 `web/servlet/RepushServlet`（GET `/repush?taskId=...`）：从 `tasks.json` 找出 taskId → 从 reports 目录重读纯文本副本 → 构造 `RemotePushRequest`（chapter_no 直接用 taskState 里记的 `chapterNoStart..chapterNoEnd`）→ 调 `reportChapters`
- [ ] 启动期健康检查：`Main.java` 启动 WebServer 后异步调一次 `RemoteBackendClient.ping()`，失败时通过 SSE 发 `type=backend-warn` 事件，前端顶部条幅显示
- [ ] 设置面板「AI 后台连接」分组：展示当前 `base-url`（只读）+ "测试连接"按钮 → 调 `/remote-ping`（新增极简 Servlet 或并入 ConfigServlet）
- [ ] 前端「已下书籍」列表加状态列（来自 `tasks.json` 与本地文件 join）+ 状态为"未上报/部分失败/上报失败"时显示"重新上报"按钮

### 6.2 关键代码骨架

#### 6.2.1 `TaskStateRepository`

```java
package com.pcdd.sonovel.repository;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONUtil;
import com.pcdd.sonovel.model.LocalTaskState;

import java.io.File;
import java.util.*;

public class TaskStateRepository {

    private static final String FILE = ".so-novel/tasks.json";

    public synchronized void save(LocalTaskState st) {
        Map<String, LocalTaskState> all = loadAll();
        all.put(st.getTaskId(), st);
        FileUtil.writeUtf8String(JSONUtil.toJsonStr(all), getFile());
    }

    public synchronized Optional<LocalTaskState> get(String taskId) { /* ... */ }

    public synchronized List<LocalTaskState> listPending() {
        return loadAll().values().stream()
                .filter(s -> s.getStatus() == LocalTaskState.Status.DOWNLOADED_NOT_PUSHED
                          || s.getStatus() == LocalTaskState.Status.PARTIAL
                          || s.getStatus() == LocalTaskState.Status.FAILED)
                .toList();
    }
    // ...
}
```

#### 6.2.2 `DownloadProgressSseServlet` 扩展

```java
public static void sendReportProgress(String phase, int accepted, int updated, int rejectedSize) {
    sendProgress(JSONUtil.toJsonStr(Map.of(
            "type", "report-progress",
            "phase", phase,                  // "reporting" | "done" | "failed"
            "accepted", accepted,
            "updated", updated,
            "rejected", rejectedSize
    )));
}
```

#### 6.2.3 `RepushServlet`

```java
public class RepushServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        String taskId = req.getParameter("taskId");
        var st = new TaskStateRepository().get(taskId).orElseThrow();
        // 1. 从 .so-novel/reports/{taskId}/ 读取 {order}.txt → 重建 RemoteChapterPushItem 列表
        //    chapter_no 直接按 st.chapterNoStart..chapterNoEnd 递增
        // 2. 调 RemoteBackendClient.reportChapters(...)
        // 3. 按响应更新 task state（PUSHED / PARTIAL / FAILED）
        // 4. 返回 JSON 给前端
    }
}
```

### 6.3 验收

- mock 后台返 503 → 客户端重试 2 次 → 任务标 `FAILED` → 前端"重新上报"按钮可见 → 点击重推成功 → 任务标 `PUSHED`
- mock 后台返部分 `rejected` → 任务标 `PARTIAL` + UI 展开失败章节明细
- 启动期 mock=0 且 base-url 不可达 → 顶部条幅出现"AI 后台连接异常"
- 设置面板"测试连接"按钮成功显示绿色 OK + `api_version` + `server_time`

---

## 7 M5 · 联调 + 回归

### 7.1 TODO

- [ ] mock 模式跑完整链路（命中 / 未命中 / toc 失败 / 无新增 / 体量超阈值 / 回推 5xx / rejected 非空 6 个场景）
- [ ] 切真实后台地址 + 真实 `X-Agent-API-Key` 跑 1~2 本真书联调
- [ ] CLI 回归：聚合搜索（不传 author / exactMatch）+ `BookFetchServlet` 的 `start/end/latest` 参数 + 4 种下载格式（epub / txt / html / pdf）
- [ ] 既有 SSE 进度推送的 `download-progress` 事件不丢
- [ ] CLAUDE.md 检查：所有改动都符合"核心层在 main / 新增层在 feat 分支"约定
- [ ] 联调后端的 commit message 体量符合 gitmoji 规范

### 7.2 联调脚本（可选）

写 1~2 个 shell 脚本放在 `scripts/`（如有此目录）：
- `scripts/dev-mock.sh`：启动 `mock=1` 的网页端
- `scripts/dev-real.sh`：启动 `mock=0` 并指向测试环境 base-url（**禁止指向生产**）

---

## 8 测试用例索引

> 对应方案 § 5 测试策略，每条括号内是建议测试类位置。**不强制全部写**，但 IncrementalAnchorResolver / chapter_no 计算 / RemoteBackendClient 的 mock 分支 这三块必须有。

### 8.1 单元测试（`src/test/java/com/pcdd/sonovel/...`）

- [ ] `core/IncrementalAnchorResolverTest`
  - 完整标题严格相等命中
  - 尾匹配（结尾有空格）正常 trim 后命中
  - 多处同名取最靠后
  - 未命中返 empty
  - 空 toc / null lastTitle 返 empty
  - **显式断言**：大小写差异不命中、全半角差异不命中、数字归一不命中（防回归）
- [ ] `repository/RemoteBackendClientTest`
  - mock 分支：`getBookInfo` / `reportChapters` / `ping` 都返预设值
  - `code=0` 抛 `RemoteBackendException` 且携 `msg`
  - 5xx 重试 2 次后失败抛异常
  - 超时按 `readTimeoutMs` 触发
- [ ] `web/servlet/AggregatedSearchServletTest`
  - `exactMatch=true` 且 author 空 → 400
  - `exactMatch=true` 且双匹配 → 过滤后只剩严格相等的
  - 不传 `exactMatch` → 走 `filterSort` 老行为
- [ ] `core/IncrementalDownloadServletTest` —— `chapter_no` 计算专项
  - 追加场景：K=100，下 5 章 → chapter_no 应为 101..105
  - 未命中分支：用户选 idx'=50，下到 idx=200 → chapter_no 仍从 K+1 起递增

### 8.2 集成测试

- [ ] mock 后台 + 端到端追加场景（搜 → 弹窗 → 下载 → 回推）
- [ ] mock 后台 + 反查未命中分支（含 toc 顶部 Top 3 相似度推荐）
- [ ] mock 后台 `rejected[]` 非空 → 任务标 `PARTIAL` + 重推流程
- [ ] mock 后台 5xx → 重试 2 次失败 → `FAILED` → 重推成功

### 8.3 回归

- [ ] CLI 聚合搜索行为不变
- [ ] `/book-fetch` 的 `start / end / latest` 参数仍工作
- [ ] 4 种下载格式产物正确（EPUB / TXT / HTML / PDF）

---

## 9 验收总清单（合并到 PR 前自查）

- [ ] 所有里程碑勾选完毕
- [ ] 单元测试 + 集成测试通过
- [ ] CLI 链路回归通过（**所有"非破坏性改造"原则的硬指标**）
- [ ] mock 模式下完整链路可跑（含 6 个分支场景）
- [ ] 真实后台联调 ≥ 1 本真书
- [ ] `bundle/config.ini` 新增的 `[remote-backend]` section 默认为空（mock 自动开启）
- [ ] 无硬编码外部域名 / token
- [ ] 文档（本文 + 方案 + 接口文档 + CLAUDE.md）已同步最新决策
- [ ] 三个月复盘节点已记入团队日历

---

## 10 附录 · 开发常见坑

| 坑 | 触发场景 | 规避 |
|---|---|---|
| `Crawler.crawl` 改动破坏 CLI | 改了既有 `crawl(bookUrl, toc)` 方法体 | 必须**重载**，原方法转发到新方法；不传 collector 时一行额外行为都没有 |
| `ReportCollector.collect` 线程安全 | 虚拟线程并发调用 | 用 `ConcurrentLinkedQueue` 而不是 `ArrayList`；写盘前确认 `chapter` 非 null |
| `chapter_no` 与源站 order 混淆 | reports 目录文件名用 order，但回推时 chapter_no 是后台序号 | `IncrementalDownloadServlet` 拿到 `snapshot()` 后**重写** chapter_no；`tasks.json` 同时记录 `sourceStartIdx..sourceEndIdx` 与 `chapterNoStart..chapterNoEnd` 二元组 |
| EPUB/PDF 格式下回推内容丢失 | 用户偏好 EPUB，Crawler 写盘后 html 中间文件被 CrawlerPostHandler 清理 | 一定走"两件套"：内存收集 + reports 副本，**不能**从落盘产物反读 |
| mock 模式误打到生产 | 配置文件忘记切环境 | 启动期日志打印 `[remote-backend] mode=mock|real, base-url=...`；测试连接按钮二次确认 |
| 跨域 / api-key 泄露 | 浏览器尝试直连 AI 后台 | 永远走服务端 Servlet 转发；浏览器 fetch 仅打到下载器自身 |
| SSE 连接断了进度丢 | 浏览器刷新或网络抖动 | 既有 `EventSource` 自动重连即可；进度本身是幂等推送，不必持久化 |
| `latestChapterNo=0`（新书无章节） | 后台书刚创建 | 反查直接走"未命中"分支让用户选锚点；chapter_no 从 1 起递增 |
| 章节 1MB 上限 | 网文偶有超长单章 | 回推前扫一遍：超限标记 `OVERSIZE_LOCAL` 状态不入推送批次，但本地仍保留产物 |
| `tasks.json` 并发写 | 多个回推/重推并发 | `TaskStateRepository` 所有写方法加 `synchronized`；本期不引入更复杂的锁 |
