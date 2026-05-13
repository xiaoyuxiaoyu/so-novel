# CLAUDE.md — so-novel 项目协作说明

> 本文件面向后续与本仓库协作的人（含 LLM 助手）。**改代码前请先读完本文件**，避免动错分支。

---

## 1. 仓库分支拓扑（最重要）

本仓库**长期维护两条业务分支**，两者面向的使用场景不同，请勿随意合并。

| 分支 | 职责 | 入口 | 对 AI 小说后台的上报内容 | 续采能力 |
|---|---|---|---|---|
| `main` | 既有"拉取任务 → 下载 → 上报"链路；后续仅保留**前 12 章试读**用于 AI 后台对该书的整体评分 | CLI + 任务拉取（定时轮询后台任务） | 客户端机器信息 + 前 12 章样本 | **不承担**（后续会从 `main` 移除续采能力） |
| `feat/web-active-report` | 网页端由运营者主动搜索源站 → 选源下载 → 回灌到 AI 后台指定书；承担**全部续采** | 网页端 WebUI（浏览器） | 增量章节内容 + 来源源站元数据 | **承担**：用户主动触发，按"AI 小说书籍 ID"精确回推 |

### 1.1 为什么不合并成一份代码

近期的方案讨论里明确了：**先两条分支独立维护**，验证新链路稳定后再视情况合流。详见 `docs/网页端AI小说后台对接-方案.md` § Part 2 § 4.3。

### 1.2 续采能力的归属

> ⚠️ 这是最容易搞混的一点：

- **以前**：续采是 CLI 任务拉取链路的一部分（定时下后续章节并整体回推）
- **以后**：续采**只属于 `feat/web-active-report`**，由运营者在网页端选目标书、选源站、点下载触发；`main` 不再做续采，避免两条链路对同一本书重复上报

---

## 2. 改代码的约束（强制）

### 2.1 哪些代码改在哪条分支

| 代码区域 | 改在哪 | 原因 |
|---|---|---|
| 源站规则 `src/main/resources/rule/*.json` | **`main`** | 共享核心；新分支 merge 同步 |
| 解析器 `src/main/java/com/pcdd/sonovel/parse/` | **`main`** | 共享核心 |
| 爬虫 `core/Crawler` / `TocParser` / `CoverUpdater` 等 | **`main`** | 共享核心 |
| `model/` 下既有 DTO（`Chapter` / `SearchResult` / `Rule` / `AppConfig` 等） | **`main`** | 共享核心 |
| CLI 入口 / 既有任务拉取流程 / `ClientReportRepository` | **`main`** | 旧链路所在 |
| 网页端 `static/index.html` + `static/index.css` | **`feat/web-active-report`** | 新链路 UI |
| 新增 Servlet（`RemoteBookInfoServlet` / `SourceTocServlet` / `IncrementalDownloadServlet` / `RepushServlet`） | **`feat/web-active-report`** | 新链路 |
| 新增类（`RemoteBackendClient` / `IncrementalAnchorResolver` / `TaskStateRepository` / `RemoteBookInfo` 等 DTO） | **`feat/web-active-report`** | 新链路 |
| 既有 `AggregatedSearchServlet` 的新增可选参数（`author` / `exactMatch`） | **`feat/web-active-report`** | 必须保持 CLI 不传时行为完全等价 |
| `bundle/config.ini` 新增 `[remote-backend]` section（base-url / token / 超时 / mock / 回推阈值） | **`feat/web-active-report`** | 新链路配置；AI 后台域名 / token 必须可配，禁止硬编码 |

### 2.2 同步规则

- **核心层禁止在新分支单独改**：若不得不在新分支调试核心层（例如某源站新规则适配），改完后**必须**在 `main` 提相同 PR，再让新分支 merge 过来；不允许"只在新分支改 / 只在 main 改"。
- **每周一次定时 merge**：`main → feat/web-active-report`，保证新分支的源站规则与解析器与 `main` 不落后超过一周。
- **紧急源站修复实时合**：线上有源站挂了等不及定时同步时，`main` 修完立刻 merge 到新分支。

### 2.3 CLI 兼容兜底

`feat/web-active-report` 上对既有 Servlet 的改动**必须**保留 CLI 旧行为：

- `AggregatedSearchServlet` 新增的 `author` / `exactMatch` 都是**可选**入参；CLI 不传时走老逻辑（`SearchResultsHandler.filterSort` 猜意图相似度排序）
- `BookFetchServlet` 已有的 `start / end / latest` 不动
- `/book-fetch` `/search/aggregated` 路由始终保留

---

## 3. 配套设计文档索引

| 文档 | 内容 |
|---|---|
| `docs/网页端下载-AI小说后台对接方案.md` | `feat/web-active-report` 的完整开发方案（决策摘要 + 设计细节；含 2026-05-13 v0.2 修订摘要） |
| `docs/网页端下载-AI小说后台对接接口文档.md` | AI 小说后台侧 HTTP 接口契约 v0.1（下载器对接以此为准） |
| `docs/网页端下载-AI小说后台对接开发文档.md` | `feat/web-active-report` 实施清单：M0~M5 里程碑 TODO + 关键模块代码骨架 + 测试用例索引（开发实施依据） |
| `docs/榜单监控书籍采集开发计划.md` | 旧的拉取链路相关历史方案，供 `main` 分支演进参考 |
| `docs/Windows-11-打包指南.md` | 打包说明，两分支通用 |

---

## 4. 给 LLM 助手的额外提示

- **判断分支再动手**：任何代码改动前先 `git branch --show-current` 看清当前分支；如果用户在 `main` 上让你改网页端弹窗 / 上报逻辑 → **先反问**是不是切到 `feat/web-active-report`，不要直接改。
- **遇到"续采" / "增量下载" / "回推 AI 后台" 等词**：默认这是 `feat/web-active-report` 的事，不是 `main`。
- **遇到"任务拉取" / "前 12 章试读" / "客户端机器信息上报"**：这是 `main` 的事。
- **既有 Servlet 想加参数**：先确认改动会不会破坏 CLI / 老调用方；新增参数必须默认值兼容老行为。
- **任何外部域名 / API 地址 / token 都禁止硬编码**：一律走 `bundle/config.ini` 对应 section（如 AI 小说后台走 `[remote-backend]`），方便运营者按环境切换。
- 中文项目，commit message 用 gitmoji 前缀（参考最近的 `:bug:` `:sparkles:` `:recycle:`）。

---

## 5. 复盘节点

新链路上线后 3 个月做一次复盘，评估：

- 网页端用户主动续采的覆盖率是否够（是否替代了 CLI 续采）
- 两分支核心层冲突频率（一周几次？）
- 是否值得合流为"一份代码 + 配置切换"

复盘结论会更新到本文件的 § 1.1。
