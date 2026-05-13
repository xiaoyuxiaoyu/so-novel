# 网页端下载 AI 小说后台对接方案（一期：搜索精确化 + 增量下载回推）

> 提案人：石宇涛 | 起草日期：2026-05-12 | 最新修订：2026-05-13 (v0.2) | 状态：评审通过，进入实施
> 关联模块：网页端搜索 / 网页端下载 / 新增「AI 小说后台」对接层
> 关联讨论：用户原话——"网页端由客户搜索小说，然后下载后主动上报到 AI 小说后台"
> 配套接口契约：[网页端下载-AI小说后台对接接口文档.md](./网页端下载-AI小说后台对接接口文档.md)（v0.1）——以该文档为准，本方案 Part 2 § 2.2/2.3 已与之对齐
> 配套实施清单：[网页端下载-AI小说后台对接开发文档.md](./网页端下载-AI小说后台对接开发文档.md)（开发实施依据）

---

## v0.2 修订说明（2026-05-13）

本次实施前的最终澄清结论。**实施时以本节为准**，下方原方案段落里与本节冲突的部分已作废。

| 项 | 原方案 | v0.2 修订 | 影响段落 |
|---|---|---|---|
| **覆盖错章场景** | 用户在源站 toc 选早期锚点 → 上报 `chapter_no` 落回已有范围 → 触发后台 upsert 覆盖 | **本期不做**。无论命中或未命中，上报 `chapter_no` 一律从 `latest_chapter_no + 1` 开始递增。未命中分支用户选锚点 idx' 仅作"从源站第 idx'+1 章开始下"的下载起点，回推序号仍接 K+1。后台 upsert 语义保留作为幂等保障，前端不暴露覆盖能力 | § 4.2 / § 4.3 / § 5（决策点 C 暂不做） |
| **网页端二次过滤位置** | 服务端 `exactMatch` 过滤书名 + 前端 JS 再过滤一次"书名 + 作者" | **全部下沉到服务端**。`AggregatedSearchServlet` 在 `exactMatch=true` 时直接按"书名 trim 严格相等 **且** 作者 trim 严格相等"过滤，前端不重复过滤 | § 2.2 / § 2.4 |
| **下载长请求模型** | 方案未明确 `IncrementalDownloadServlet` 同步/异步语义 | **沿用既有 `/book-fetch` 模式**：fetch 同步 HTTP 挂连接到下载+回推全部结束才返回；进度走既有 `/download-progress` SSE 通道（新增 `report-progress` 事件类型推回推阶段） | § 2.2 |
| **回推章节内容来源** | 方案未明确从何处取章节正文回推 | **两件套**：① Crawler 改造为可接受可选的"回推收集器"，每章解析完同步落盘 + 累加到内存 List，本次任务完成后直接整体回推；② 同时把纯文本副本写一份到 `${workDir}/.so-novel/reports/{taskId}/{chapterNo}.txt`，与用户选的下载格式解耦，供"重新上报"按钮使用 | § 2.1 / § 2.2 |
| **新分支** | 方案假设 `feat/web-active-report` 已存在 | **待新建**。从 `main` 当前 commit 起新分支；首批提交含 CLAUDE.md + 本次改名后的文档 | § 4.3 |

修订原因详见同目录开发文档 § 0 与会议讨论记录（用户 2026-05-13 澄清轮次）。

---

---

## Part 1 · 决策摘要

### 1. 背景与目标

- **痛点**：现有任务式下载链路对 AI 小说后台运营者而言是「盲盒」——拿到结果时不知道来自哪个源站、质量如何、是否需要换源；运营者无法主动介入选源。
- **目标**：开放一条「网页端→用户主动选源→下载完成后定向回灌某本已存在书」的链路；运营者在 AI 小说后台看见每条上报的来源信息后，可以判断该源站是否值得继续使用。
- **本期范围**：仅做"下载器网页端"侧改造（搜索字段拆分 + 下载弹窗 + 增量计算 + 回推链路），AI 小说后台侧接口本期同步定义、由后台同学并行实现。

### 2. 方案一句话

将网页端搜索框拆为「书名 + 作者名」双输入并按完全匹配过滤；点击下载弹窗收集"AI 小说书籍 ID"，由服务端反查后台最后章节标题、在源站目录中反查索引、只下载之后的增量章节，下载完成后整体回推到 AI 小说后台。

### 3. 范围

**本次做**
- **代码组织**：在新分支 `feat/web-active-report` 上开发并独立维护；不与主线 `main`（任务拉取链路 + 机器信息上报）合并。两条分支同源共享解析器/源站规则，bug fix 与新源站规则需手动双向同步（详见 Part 2 § 4 分支策略）。
- 网页端搜索栏拆分为「书名 / 作者」双字段，**两者均必填**；用书名走聚合搜索，结果用「书名 + 作者均完全匹配」二次过滤
- 网页端点击「下载」改为弹窗，必填「AI 小说书籍 ID」，下载格式延用既有偏好
- 新增「AI 小说后台对接层」（HTTP 客户端 + 配置项），含三个调用：按 ID 查书况、回推增量章节、健康检查；**后台域名、API Key、超时等全部在 `bundle/config.ini` 新建 `[remote-backend]` section 配置**（请求头名为 `X-Agent-API-Key`，复用现有 download agent 同一把 Key），运营者可按部署环境（测试 / 预发 / 生产）切换
- 增量起点：按后台返回的"最后章节标题"在源站目录中反查索引；命中则从下一章开始下载，未命中时拉取源站完整目录、让用户在目录列表中点选锚点章节（从所选章节的下一章开始下载）
- 下载完成后整体回推增量章节到 AI 小说后台；回推失败不影响本地产物，留人工重试入口

**本次不做**
- AI 小说后台侧接口实现（由后台同学并行做，本方案给契约草案）
- CLI 端同等流程（CLI 仍走旧的任务拉取链路；主线 `main` 后续仅保留"拉取前 12 章供 AI 评分"的能力，**续采能力一律下放给本期新分支由用户主动触发下载上报**）
- 把两条分支合并为"一份代码两套模式由配置切换"（本期明确两分支独立维护，下迭代视使用反馈再评估是否合流）
- 一次下载多本书 / 批量回推 / 后台任务队列
- 源站质量评分、自动换源等运营辅助功能
- 鉴权体系重构（本期 `X-Agent-API-Key` 走配置文件 / 环境变量，复用现有 download agent 的同一把 Key；不做用户级登录）

### 4. 业务规则与流程

#### 4.1 规则清单变更

聚合搜索现有"猜意图相似度排序 + 0.3 阈值过滤"。本期不删除它（CLI 还在用），仅在网页端搜索路径**追加**一道精确过滤；既有规则在网页端路径上的影响如下：

| 规则名 | 当前行为 | 严重度 | 本期处置 | 理由 |
|---|---|---|---|---|
| 关键词意图判定（书名 vs 作者） | 按短/中/长关键词不同权重猜「用户输入的是书名还是作者」 | info | **保留（仅 CLI 用）**；网页端绕过：直接以「书名」作为查询字段 | 网页端已拆双字段，无需再猜意图 |
| 相似度 ≥ 0.3 过滤 | 过滤掉与关键词相似度 < 0.3 的结果，空则降级返回 > 0 的全集 | info | **保留**：作为聚合搜索内部预处理 | 不影响后续精确过滤，且能减少传输量 |
| 相似度降序排序 | 按相似度降序排，平手按作者/书名字典序 | info | **保留** | 精确过滤后剩余结果仍按相似度排，体验稳定 |
| —— 新增 —— | | | | |
| 书名完全匹配（网页端） | 用户必填书名；与结果 `bookName` 去空格后**完全相等**才保留 | filter | **新增** | 主过滤口径，网页端运营者明确知道想要的书名 |
| 作者完全匹配（网页端） | 用户**必填**作者；与结果 `author` 去空格后**完全相等**才保留；空值在前端校验阶段就拦截，不发起搜索 | filter | **新增** | 网页端面向运营者，运营者上报前已知作者；强制必填可彻底消除同名书误选风险 |

#### 4.2 判定逻辑

逐维度列出本期新增/关键判定：

- **书名精确匹配**：输入框值与结果书名同时去前后空格 → 严格 `equals` → 不一致即剔除。**不做**繁简归一化、大小写归一化、全半角归一化（避免规则膨胀，运营者若需要可再提）。
- **作者精确匹配**：作者输入**必填**，空值在前端校验阶段拦截（禁用搜索按钮 + 提示"请输入作者"）；服务端兜底再校验一次；非空后与结果作者去前后空格后严格 `equals`。
- **AI 小说书籍 ID 校验**：弹窗内必填，**类型为正整数**（与接口文档对齐：`book_id: int >= 1`）；前端只允许数字输入，服务端再校验一次；服务端调后台 `/get_book_info` 验真；查不到 → 弹窗内提示"未在后台找到该 ID" → 不发起下载。
- **章节标题保持原样不归一化（关键）**：接口文档 v0.1 明确——`latest_chapter_title` 返回**完整标题**（含"第 X 章"前缀，与后台 `bc_download_chapters.chapter_title` 入库格式一致），上报时 `chapters[].title` 同样**直接传源站原文**，不去前缀、不做数字归一。下载器侧反查与上报均**不引入任何标题清洗**：后台标题与源站标题本来就同源（后台早期数据也是从源站采来的），保持原文严格相等命中率最高；任何归一化反而会因下载器侧规则与后台侧 `chapter_push_service.clean_title` 不一致而拉低命中率。
- **增量起点反查**：取后台返回的 `latest_chapter_title`（完整带前缀）→ 在源站完整目录里**从尾向头**做严格字符串相等匹配（两侧均仅做 `trim`，不做大小写 / 全半角 / 数字归一）→ 命中位置在源站 toc 中的下标记为 idx，下载 idx+1..末尾；**多处命中**取最靠后那一处。
- **上报 `chapter_no` 计算（关键 — 与源站序号语义不同）**：上报体里的 `chapter_no` 是 **AI 后台 DB 内目标章节序号**，不是源站序号。计算规则：
  - **追加场景（最常见）**：取接口 1 返回的 `latest_chapter_no=K`，下载 N 章新增 → 依次赋 `K+1, K+2, ..., K+N`
  - **覆盖错章场景**：用户在 toc 选锚点 N'（N' < `latest_chapter_no`），表示从该章重拉 → 上报时 `chapter_no` 也从该锚点对应的目标序号开始递增；服务端按 `(book_id, chapter_no)` upsert，命中既有的会覆盖
  - **混合场景**：单次上报可同时含覆盖 + 追加，服务端逐条 upsert
- **未命中 → 用户在源站目录里选锚点**：弹窗自动拉取源站完整目录并展示列表（章节序号 + 章节标题）；列表内**高亮相似度 Top 3 候选**（与后台最后章节标题相似度最高的三条）并提供搜索框；用户点选某章节作为"锚点"，下载从该章节的**下一章**开始（语义与命中分支统一为 N+1）。
- **锚点范围**：用户可选择目录里的**任意章节**作为锚点（不限制必须在"后台已有章节数"之后），由后台接口侧做幂等去重保障；好处是允许用户主动覆盖"后台某些早期章节内容错了/缺了"的场景。
- **目录拉取失败兜底**：拉取超时 / 解析失败 → 弹窗内提示"无法获取源站目录"，提供两个降级出口：「全量下载」或「中止」（保留原兜底）。
- **空增量判定**：idx+1 > 源站章节数 → 后台已是最新，提示"无新增章节"并终止下载，不发起回推。
- **单章内容上限**：单章正文 ≤ **1 MB**（UTF-8 字节数，与接口文档对齐）。下载完成后回推前先扫一遍：超过 1 MB 的章节标记为本地"超限未推"，其余正常回推；超限章节不阻塞批次，但在 UI 列出供运营者人工处理。
- **回推体量限制**：服务端不强制单批上限，**阈值由下载器 UI 承担**——单次回推增量章节数 > 阈值（默认 500）→ 提示"增量过大，建议先在后台对齐章节数后再下"，由用户确认是否继续；超过 2000 直接拒绝，避免一次回推压垮后台。
- **回推响应处理**：
  - 成功（`code=1`）：解析 `accepted_count` / `updated_count` / `rejected`；`updated_count > 0` 时 UI 提示"本次覆盖了 N 个已有章节"（覆盖错章场景的明确反馈）；`rejected` 非空时在「已下书籍」列表标"部分失败"并展开失败章节明细。
  - 失败（`code=0` / 超时 / 5xx）：客户端最多重试 2 次（指数退避 2s/5s）→ 仍失败则将整次任务标记"已下载未回推"，在网页端「已下书籍」列表显示"重新上报"按钮，本期人工触发重推，本地产物始终保留。
- **健康检查使用时机**：下载器启动期可选调一次 `/ping`，失败时在 WebUI 顶部条幅提示"AI 后台连接异常，请检查 `[remote-backend] base-url / api-key`（请求头 `X-Agent-API-Key`）"，但**不阻塞下载器启动**（避免离线开发不便）；WebUI 设置面板提供"测试连接"按钮主动调用。

#### 4.3 流程编排

```
用户在网页端                下载器后端                          AI 小说后台
   │ 输入书名(必)+作者(必)   │                                        │
   ├────── 搜索 ──────────►│                                        │
   │                       │ 聚合搜索（按书名）→ 相似度过滤排序     │
   │                       │ → 网页端再做"书名+作者均完全匹配"过滤  │
   │ 选源 → 点"下载"        │                                        │
   │ 弹窗输入 AI 小说 ID    │                                        │
   ├────── 提交 ──────────►│                                        │
   │                       ├──── /get_book_info (book_id) ─────────►│
   │                       │◄── latest_chapter_no=K + 完整标题 ──────┤
   │                       │ 解析源站目录 → 完整标题严格相等反查 idx │
   │                       │ 命中? ── 是 ──► 下载源站 idx+1..末尾    │
   │                       │           否 ↓                          │
   │◄── 返回完整 toc(含 Top3 高亮) ──────┤                          │
   │ 用户在列表里选锚点 idx'              │                          │
   ├────── 提交锚点 ──────►│ 下载源站 idx'+1 .. 末尾                 │
   │                       │ （toc 抓取失败 → 弹窗降级"全量/中止"）  │
   │                       │ 失败 → 沿用既有 Crawler 重试策略        │
   │                       │ 成功 ↓                                  │
   │                       │ 计算 chapter_no（追加=K+1..K+N /        │
   │                       │ 覆盖=用户锚点对应目标序号）             │
   │                       ├── /report_chapters(book_id, [...]) ────►│
   │                       │◄── accepted/updated/rejected ───────────┤
   │                       │ 失败/超时 → 重试 2 次 → "未回推"        │
   │◄── 任务完成提示(含覆盖N章/失败章节) ─┤                          │
```

**五要素自检**：
- 触发方：网页端用户主动点"下载"
- 前置门槛：书名必填 + 作者必填 + AI 小说 ID 必填 + 后台能查到该 ID；未命中分支还要求"toc 拉取成功 + 用户选定锚点"
- 串行/并行：单本书内**全串行**（查书况 → [拉 toc → 用户选锚点] → 下载 → 回推）；多用户多本书在服务端层面共享既有下载并发池
- 失败兜底：查书况失败 → 不发起下载；toc 拉取失败 → 弹窗降级"全量/中止"；下载失败 → 沿用 Crawler 既有行为，**不**自动回推；回推失败 → 重试后挂"未回推"状态，本地产物保留
- 终态：任务成功（已回推）/ 已下载未回推（待人工重推）/ 已下载无需回推（无增量）/ 中止（ID 查不到 / toc 拉取失败且用户选中止 / 用户取消 / 下载失败）

### 5. 风险与决策点

| 风险 / 决策点 | 影响 | 推荐方案 |
|---|---|---|
| 后台 `latest_chapter_title` 与源站章节标题严格不等（书源后期改版章节命名 / 后台早期手工编辑 / 标点全半角差异） | 反查未命中 | 接口文档 v0.1 双方约定**两端均传 / 存完整原文不做归一化**；未命中走"拉源站 toc 让用户选锚点 + Top 3 相似度高亮"兜底（已在 § 4.2 / § 4.3 设计内）；上线后跟踪未命中率，若 > 10% 再与后台同学评估是否引入双侧统一的归一化策略，**禁止下载器单侧引入归一化**（会与后台 `clean_title` 不一致拉低命中率） |
| 源站目录抓取耗时大（5000+ 章 / 分页多 / 过 CF） | 弹窗 loading 时间长，用户以为卡死 | toc 抓取设独立 15s 超时；弹窗显示阶段化文案（"正在拉取源站目录…"）；失败明确提示并降级到"全量/中止" |
| 源站目录回传体量大（5000+ 章 ≈ 500KB） | 浏览器内存/渲染压力 | 服务端只回 `order + title`（剥掉 url），实测可压到 ~150KB；前端 toc 列表 > 500 项时启用简单分页 + 搜索框 |
| 同标题多处命中（书中确实有同名章节，如"第一章"被多卷复用） | 增量起点错位、可能把已有章节又下一遍 | **取最靠后一处**；服务端按 `(book_id, chapter_no)` upsert（接口文档已落地），跨次重推同 chapter_no 不会出错 |
| 用户填错 AI 小说 ID，把 A 书的章节灌进 B 书 | 数据污染、回滚成本高 | 后台「按 ID 查书」必须返回**书名 + 作者**，下载器弹窗在反查命中后**回显**给用户做二次确认（"目标书：《xxx》— 作者：yyy，确定上报到这本书？"），用户点"确认"才发起下载 |
| 回推中途下载器崩溃 / 用户关浏览器 | 本地有产物但后台不知道，下次重启没人主动重推 | 任务状态持久化到本地 JSON（沿用既有本地书籍目录）；启动时扫描"已下载未回推"任务，在网页端列表标红，提供"重新上报"按钮（手动触发） |
| AI 后台未上线时本期联调阻塞 | M2/M3 无法验收 | 本期内置一个 `mock-backend` 开关（配置开 → 调用本地 stub 模拟响应）；联调阶段切到真实地址 |
| 跨域 / 凭据 | 浏览器直连 AI 后台会有 CORS、api-key 暴露问题 | **后台对接走服务端**（下载器 Servlet → AI 后台），`base-url + api-key` 放 `config.ini` 的 `[remote-backend]`，浏览器只与下载器自身通信，永不接触 api-key |
| **多环境切换需求**（测试 / 预发 / 生产 AI 后台域名不同） | 写死域名会导致换环境必须重新打包 | 域名 / api-key 全部 `config.ini` 可配；运营者改 `config.ini` 重启服务即可切环境；WebUI 不暴露域名修改入口防误改 |
| 一次回推体量过大压垮后台 | 后台超时、客户端重试雪崩 | 单次增量 ≤ 500 章直接推；500 ~ 2000 章弹窗提示确认；> 2000 章拒绝并提示分多次或缩范围 |
| **分支并行维护成本**：源站规则会持续随站点变化更新，两分支若各改各的会产生冲突 / 误改 | 长期维护成本上升，可能出现"网页端能下、CLI 下不了"或反之的尴尬 | ① 约定核心层（解析器、源站规则、`Crawler`、`TocParser`）改动**只在 `main` 修，新分支 rebase / merge 同步过来**，新分支只新增网页端 + 上报相关代码；② 在仓库根建 `CLAUDE.md`（本期一并交付）说明分支职责，避免协作者搞混；③ 三个月节点复盘一次：若回灌链路稳定，评估是否合流为"一份代码两套模式" |

### 6. 工期与里程碑

| 里程碑 | 内容 | 估时 |
|---|---|---|
| M1 | AI 后台对接层（HTTP client + 配置项 + mock stub） | 1 天 |
| M2 | 网页端搜索栏拆双字段 + 书名/作者完全匹配过滤 | 半天 |
| M3 | 下载弹窗 + ID 校验 + 书况回显 + 增量起点反查（完整标题严格相等）+ 未命中时 toc 选锚点 + chapter_no 计算 | 2 天 |
| M4 | 回推链路 + 响应解析（accepted/updated/rejected）+ 失败重试 + "未回推/部分失败"状态持久化 + 重推入口 + 健康检查/测试连接 | 1.5 天 |
| M5 | 联调（真实后台地址）+ 回归（CLI 旧链路、原有下载格式） | 1 天 |

**总计**：~6 天（含联调），不含后台同学接口开发时间。

---

## Part 2 · 设计细节

### 1. 现状分析

为什么不能直接在现有链路上扩展：

- **搜索入口**：`AggregatedSearchServlet` 只接 `kw` 一个参数；`SearchResultsHandler.filterSort` 内部用"短/中/长关键词权重 + 相似度阈值"猜书名还是作者，**与网页端"用户已经显式说这是书名"的语义冲突**。若直接改 `filterSort`，CLI 端原有交互（CLI 单输入框、猜意图很合理）会被破坏。
- **下载入口**：前端 `handleDownloadToServer` 直接 `fetch('/book-fetch?url=...')`，**完全没有弹窗** / 中间态；想加"ID 输入 + 书况回显二次确认"必须重写前端这段，但 `BookFetchServlet` 已支持 `start/latest` 参数，**后端切片能力够用**，只需多传起点即可。
- **上报**：`ClientReportRepository` 只上报机器信息（user/host/mac/os/ip/appVersion）；**没有任何"章节内容外发"的客户端**，也没有任何外部书籍 ID 的概念。本期是新链路从 0 搭，不是改造。

结论：搜索/下载/上报三段都需要**非破坏性新增**，避免动 CLI 既有行为；只有 `BookFetchServlet` 可直接复用切片能力。

### 2. 方案设计

#### 2.1 数据模型

- 不新增数据库表（项目目前无 DB，状态用配置 / 本地 JSON）。
- 新增 DTO（位于 `model/remote/`）；**Java 字段名用驼峰，JSON 序列化层（建议 `@JsonProperty` 或 Jackson `PropertyNamingStrategies.SNAKE_CASE`）落到 snake_case 与接口文档一致**：
  - `RemoteBookInfo`：`bookId(int) / bookName / author / latestChapterNo(int) / latestChapterTitle / updatedAt`
  - `RemoteChapterPushItem`：`chapterNo(int) / title / content` —— **`chapterNo` 是 AI 后台 DB 内目标序号，不是源站序号**（语义比方案最初版变更，详见 § 4.2 与接口文档 § "幂等口径"）
  - `RemoteClientMeta`：`sourceName / sourceUrl / appVersion` —— 三项均必填，缺失会被服务端整批拒绝；**本期不收 `downloader_user`**（下载器无真实用户登录系统，自报字段无审计价值，接口文档明确）
  - `RemotePushRequest`：`bookId / chapters / clientMeta`
  - `RemotePushResponse`：`acceptedCount(int) / updatedCount(int) / rejected: List<{chapterNo, title, reason}>`
  - `RemotePingResponse`：`serverTime / apiVersion`
  - `LocalTaskState`（持久化到 `${workDir}/.so-novel/tasks.json`）：`taskId / bookId / sourceId / bookUrl / sourceStartIdx / sourceEndIdx / chapterNoStart / chapterNoEnd / status: DOWNLOADED_NOT_PUSHED|PUSHED|PARTIAL|FAILED / rejected: List<RejectedChapter> / createdAt`

#### 2.2 服务 / 接口

- **下载器内部新增 Servlet**（不动既有 Servlet）：
  - `RemoteBookInfoServlet`：入参 `bookId`，转发后台查书；返回前端 `RemoteBookInfo`
  - `SourceTocServlet`：入参 `url`（源站详情页），调用 `TocParser.parseAll` 拿全本目录；返回精简版 `List<{order, title}>`（**剥掉 chapter url 减体量**）。前端在"未命中"分支调用以渲染锚点选择列表。
  - `IncrementalDownloadServlet`：入参 `bookId / sourceId / bookUrl / startOrder（可选） / format / language / concurrency`；内部串联"查书况 → 反查 toc 命中则自动算 N，未命中走前端传入的 `startOrder` → 切片下载 → 回推"
  - `RepushServlet`：入参 `taskId`，重试一个标"未回推"的任务
- **既有 Servlet 改动**：
  - `AggregatedSearchServlet`：新增可选 `author` 参数；新增 `exactMatch=true` 时跳过 `filterSort` 改走"书名相等过滤"。CLI 不传该参数，行为不变。
- **新增服务层**：
  - `RemoteBackendClient`（HTTP client，基于 hutool `HttpRequest`）：`getBookInfo(bookId)` / `reportChapters(req)` / `ping()`；统一 `X-Agent-API-Key` header / 30s read-timeout / 重试策略；统一解析 `{code,status,msg,data}` 响应壳，`code=0` 抛领域异常携带 `msg`
  - `IncrementalAnchorResolver`：`resolve(List<Chapter> toc, String lastTitle) → Optional<Integer>`；两侧 `trim` 后从尾向头严格相等匹配，返回 toc 下标；**不做任何标题清洗 / 归一化**（与接口文档约定一致）
  - `TaskStateRepository`：读写 `tasks.json`，提供 `markDownloaded` / `markPushed` / `markPartial(rejected)` / `markFailed` / `listPending`

#### 2.3 异步任务 / 外部接口

- 下载流程仍走 `Crawler` 既有同步流程，不改异步模型。
- 回推采用同步调用（用户全本下完才回推；read-timeout 30s 与服务端处理上限匹配）+ 失败重试 2 次（间隔 2s / 5s）。
- **AI 后台接口契约（以 [接口文档 v0.1](./网页端AI小说后台对接-接口文档.md) 为准；以下仅列对下载器侧的关键点，避免与接口文档双写漂移）**：
  - 基础路径 `/api/book-collector/web-downloader/`，全部 POST + JSON body
  - 鉴权头 `X-Agent-API-Key`（共用 `DOWNLOAD_AGENT_API_KEY`，配置在下载器 `[remote-backend] api-key`）
  - 响应统一壳 `{code, status, msg, data}`，`code=1` 成功；下载器侧封装为 `RemoteApiResult<T>`，`code=0` 抛 `RemoteBackendException` 携 `msg`
  - 三个接口：
    - `POST /get_book_info` body `{book_id}` → `data: RemoteBookInfo`；用于弹窗第二步回显
    - `POST /report_chapters` body `{book_id, chapters, client_meta}` → `data: RemotePushResponse`；用于全本下完后整体回推
    - `POST /ping` body `{}` → `data: {server_time, api_version}`；用于启动期 / 设置页"测试连接"
  - 幂等口径：服务端按 `(book_id, chapter_no)` upsert（已落地，对应原待决策 D）；下载器侧重复调用安全

#### 2.4 前端

- `index.html` 搜索栏：拆为「书名（必填）」「作者（必填）」两输入框；任一为空时搜索按钮置灰 + 提示；搜索按钮逻辑改为 `aggregatedSearch(bookName, author)`，拿到结果后在 JS 侧做"书名完全相等 **且** 作者完全相等（两者皆已去前后空格）"二次过滤再渲染。`AggregatedSearchServlet` 服务端兜底校验 `author` 必填，缺失返 400。
- 下载按钮：移除"格式下拉直接下载"的分支；改为统一弹窗，多状态步进：
  - **第一步（输入）**：表单字段 AI 小说书籍 ID（**正整数必填**，输入框 `type=number min=1`，非整数 / ≤0 时按钮置灰）/ 下载格式（沿用现有偏好默认值）
  - **第二步（回显确认）**：调下载器自身 `/remote-book-info?bookId=...`（服务端转 `/get_book_info`）后回显「目标书：《xxx》— 作者：yyy — 后台已有 K 章，最后章节：《完整章节标题，含"第 X 章"前缀》」并附"确认下载"按钮
  - **第三步分支 A（命中）**：服务端反查锚点命中 → 直接发起增量下载（计算 `chapter_no` 从 `K+1` 起），无需用户再交互
  - **第三步分支 B（未命中）**：弹窗内切换为"锚点选择"视图——调 `/source-toc?url=...` 拿到全本目录 → 列表展示 `序号 + 标题`，顶部固定一栏"建议候选 Top 3"（按与后台 `latest_chapter_title` 的相似度降序，相似度算 trim 后的字符串距离），下方为完整目录 + 搜索框；列表 > 500 项时启用简单分页。用户点选某行 → 弹窗显示"将从源站《第 idx'+1 章》开始下载，对应后台 chapter_no=用户锚点+1" 二次确认 → 提交带 `startOrder=idx'+1` 的下载请求
  - **第三步分支 C（toc 抓取失败）**：弹窗内提示并提供两按钮"全量下载 / 中止"
- **设置面板**：新增「AI 后台连接」分组，展示当前 `base-url`（来自 `config.ini`，**只读不可编辑**）+ "测试连接"按钮（调 `/ping`）→ 成功显示绿色 OK + `api_version` + `server_time` 与本地时差；失败显示红色错误信息
- 「已下书籍」列表：增加状态列（已上报 / 部分失败 / 未上报 / 上报失败）；"部分失败"行点击可展开 `rejected` 章节明细；"未上报 / 部分失败 / 上报失败"行尾增加"重新上报"按钮，调 `/repush?taskId=...`

#### 2.5 配置

在项目实际配置文件 `bundle/config.ini` 新增 `[remote-backend]` section（与现有 `[global]` `[download]` `[source]` `[crawl]` `[web]` `[cookie]` `[proxy]` 同级）；运营者可按部署环境（测试 / 预发 / 生产）切换：

```ini
[remote-backend]
# AI 小说后台域名（含 scheme，结尾不带斜杠）；查书况与回推章节均走此地址
# 留空时自动走 mock 本地 stub，便于离线开发与联调前的回归
# 实际请求路径为 ${base-url}/api/book-collector/web-downloader/{get_book_info|report_chapters|ping}
base-url =
# AI 小说后台接入 API Key；走真实链路时必填
# 接口文档要求请求头 X-Agent-API-Key 携带此值（与现有 download agent 共用同一把 DOWNLOAD_AGENT_API_KEY）
api-key =
# 连接超时（毫秒），默认 5000
connect-timeout-ms = 5000
# 读取超时（毫秒），默认 30000；与服务端单请求 30s 上限匹配；toc 抓取另有独立 15000ms 超时，不受此影响
read-timeout-ms = 30000
# 1 = 强制 mock；0 = 走真实 base-url；留空且 base-url 为空时自动 mock
mock =
# 单次回推章节数 > 该阈值时弹窗二次确认，默认 500（服务端不强制上限，由下载器 UI 承担）
push-batch-warn-threshold = 500
# 单次回推章节数 > 该阈值时直接拒绝，默认 2000
push-batch-reject-threshold = 2000
# 启动期是否调用 /ping 检测；失败仅提示不阻塞启动，默认 1
startup-ping = 1
```

**配置加载约定**：
- 解析器复用项目既有 `AppConfigLoader` 风格新增 `RemoteBackendConfig` 模型；启动期一次性读入，运行时不热刷新。
- `base-url` 与 `api-key` 视为敏感配置：`mock=0` 但 `base-url` 或 `api-key` 任一为空 → 启动期日志报错并终止启动（避免裸跑往未配置的地址打请求）。
- 切换环境由运营者修改本地 `config.ini` 完成，**不在 WebUI 暴露域名修改入口**（防止误改后台地址造成跨环境污染）；WebUI 设置面板只能查看 `base-url` 与"测试连接"按钮。

### 3. 影响面清单

| 类别 | 项目 | 变更类型 |
|---|---|---|
| 配置 | `bundle/config.ini` 新增 `[remote-backend]` section（base-url / api-key / 超时 / mock / 回推阈值 / startup-ping，共 8 项） | 新增 |
| 类 | `core/RemoteBackendConfig` + `AppConfigLoader` 扩展加载新 section | 新增 / 改造 |
| 类 | `model/remote/RemoteBookInfo` `RemoteChapterPushItem` `RemoteClientMeta` `RemotePushRequest` `RemotePushResponse`（含 `rejected[]`）`RemotePingResponse` `RemoteApiResult<T>` `LocalTaskState` | 新增 |
| 类 | `repository/RemoteBackendClient`（`getBookInfo` / `reportChapters` / `ping`） | 新增 |
| 类 | `repository/TaskStateRepository` | 新增 |
| 类 | `core/IncrementalAnchorResolver` | 新增 |
| 异常 | `RemoteBackendException`（`code=0` 时携 msg 抛出） | 新增 |
| Servlet | `web/servlet/RemoteBookInfoServlet` `SourceTocServlet` `IncrementalDownloadServlet` `RepushServlet` | 新增 |
| Servlet | `web/servlet/AggregatedSearchServlet` | 新增可选 `author` `exactMatch` 入参（兼容） |
| 前端 | `static/index.html` 搜索栏、下载弹窗、本地书籍状态列 | 改造 |
| 前端 | `static/index.css` 弹窗样式 | 新增样式块 |
| 文档 | `README.md` 网页端使用说明、配置说明 | 增补 |
| 注册 | 主路由注册新增 Servlet（搜对应 `launch` 包） | 新增 |

### 4. 迁移、兼容与分支策略

#### 4.1 历史数据与旧 API
- 本地 `tasks.json` 不存在时按空集合初始化；既有本地已下书籍不动，不会被本期流程二次回推。
- `/book-fetch` `/search/aggregated` 全部**保留**，CLI 与第三方调用方不受影响；`/search/aggregated` 仅新增可选参数（CLI 不传则行为不变）。

#### 4.2 灰度与回滚
- 默认 `[remote-backend] mock=`（空）+ `base-url=`（空）→ 自动走 mock；运营者在 `config.ini` 显式填好 `base-url + api-key + mock=0` 才走真实链路。
- 回滚：禁用新增 Servlet 路由 + 前端回退到老 `index.html`（保留一份 `index.legacy.html` 一周）；新增类全是新建文件，删除即可干净回滚。

#### 4.3 分支策略（本期最关键的协作约定）

**分支拓扑**

| 分支 | 职责 | 入口 | 上报内容 |
|---|---|---|---|
| `main` | 既有"拉取任务→下载→上报"链路；后续仅保留**前 12 章试读**用于 AI 评分；不再承担"续采" | CLI + 任务拉取 | 仅机器信息 + 前 12 章样本 |
| `feat/web-active-report`（本期新建） | 网页端用户主动搜索 → 下载 → 回灌到 AI 后台指定书；承担**全部续采** | 网页端 UI | 增量章节 + 来源元数据 |

**协作约定**
- **核心层只在 `main` 改**：源站规则文件（`src/main/resources/rule/*.json`）、解析器（`parse/`）、爬虫（`core/Crawler`、`TocParser`）属共享核心；任何改动**先在 `main` 提 PR**，新分支定期 `git merge main`（或 rebase）拉同步，**禁止**在新分支单独改核心层。
- **新分支只新增、不修改既有公共代码**：网页层、上报层、新 Servlet、新 DTO 一律放新分支；既有 `AggregatedSearchServlet` 新增的"可选 `author / exactMatch` 参数"必须做到 CLI 不受影响（默认走老逻辑）。
- **同步频率**：每周一次定时 merge `main` → `feat/web-active-report`；遇紧急源站修复（线上挂了）实时合并。
- **CLAUDE.md 落地**：仓库根新建 `CLAUDE.md`，对协作者 / LLM 说明上面这个分支拓扑与约定，避免误改。
- **复盘节点**：上线后三个月做一次复盘，评估是否合流为"一份代码两套模式由配置切换"——只有当新链路稳定且核心层冲突频繁时才合流；否则继续独立维护。

### 5. 测试策略

- **单元**：
  - `IncrementalAnchorResolver`：完整标题严格相等（仅两侧 trim）、尾匹配、多处同名匹配（取最后）、未命中、空 toc、最后一章已是最新；**显式断言不做任何归一化**
  - 网页端 JS 过滤函数：书名/作者均严格相等（带前后空格自动 trim）、任一为空时搜索按钮置灰、繁简不归一化的反例
  - 服务端 `AggregatedSearchServlet`：缺失 `author` 入参时返 400（CLI 不传 `exactMatch` 时绕过该校验保持兼容）
  - 相似度 Top 3 计算：标题完全相等的应排首位；后台标题与源站标题有微小差异时 Top 3 能稳定包含正解（用真实样本数据，覆盖标点 / 空格 / 个别字差异）
  - `RemoteBackendClient`：重试次数、超时、5xx 处理、`code=0` 抛 `RemoteBackendException`、mock 模式分支
  - `chapter_no` 计算：追加场景 `K+1..K+N` 正确递增；覆盖错章场景从锚点 chapter_no 起递增；混合场景前半覆盖后半追加
- **集成**：
  - mock 后台模式跑完一条完整链路：搜索 → 双字段过滤 → 弹窗 → 查书况回显 → 反查命中 → 下载 → 回推 `accepted=N updated=0`
  - 反查未命中分支：弹 toc → 选锚点 → 从 idx'+1 开始下载 → 回推
  - 覆盖错章分支：用户选早期锚点（idx' < `latest_chapter_no` 对应 idx）→ 上报 `chapter_no` 落在已有范围 → 回推 `updated_count > 0` → UI 提示"本次覆盖了 N 个已有章节"
  - 混合场景：单批次同时含 `chapter_no <= K` 与 `> K`，服务端响应 `accepted + updated` 均 > 0
  - `rejected` 处理：mock 后台对部分章节返 `content_oversize` → UI 状态标"部分失败"+ 展开 rejected 明细 → 重推按钮只重发失败章节
  - 健康检查：`/ping` 在配置正确时返 200，错 `X-Agent-API-Key` 返 401 → 设置面板红色提示
  - toc 抓取失败分支：弹窗降级 → 用户选全量 / 中止
  - 回推失败分支：mock 后台返 503 → 重试 2 次 → 标"未回推" → 重推按钮成功
  - 同标题多处命中分支
- **回归**：
  - CLI 聚合搜索行为不变（不传 `author` `exactMatch`）
  - `/book-fetch` 旧参数 `start/end/latest` 仍工作
  - 既有 SSE 进度推送、下载格式、语言切换无回退
- **不做 E2E 自动化**：本期 UI 改动小、靠手动 + 集成测试覆盖；E2E 框架引入是另一坨工作量。

### 6. 待决策事项

| # | 决策点 | 候选项 | 倾向 |
|---|---|---|---|
| A | 锚点反查未命中时的默认行为 | 全量下载 / 中止 / 拉 toc 让用户选锚点 | **拉 toc 让用户选锚点**（已写入 4.2 / 4.3） |
| B | 重推任务是否在下载器启动时自动尝试一次 | 自动 / 仅手动 | 仅手动（避免启动期外部依赖） |
| C | 网页端"作者"字段是否做模糊匹配开关 | 严格相等 / 提供"包含"开关 | 本期严格相等，下迭代看反馈 |
| D | ~~后台回推接口的幂等口径~~ | ~~按 seq / 按 title / 按 seq+title~~ | ✅ **已闭合**：接口文档落地为 `(book_id, chapter_no)` upsert（chapter_no 是后台目标序号） |
| E | 单次回推上限是否做成配置项 | 写死 500/2000 / 配置项 | 配置项（见 2.5） |
| F | 是否在弹窗里显示"该源站近 7 天该书的下载成功率" | 做 / 不做 | **不做**（下迭代再说，需要后台另开端点） |
| G | ~~章节标题归一化正则的初版范围~~ | ~~"章/卷/集/回/节/话"等~~ | ✅ **已闭合**：接口文档 v0.1 明确不做归一化，下载器侧也保持原文严格相等比对，本项作废 |
| H | `RemotePushItem.content` 是否传 HTML | 仅纯文本 / 仅 HTML / 由下载格式决定 | **统一纯文本上报**（接口文档允许 HTML 但本期不暴露），后台 word_count 计算更稳 |
