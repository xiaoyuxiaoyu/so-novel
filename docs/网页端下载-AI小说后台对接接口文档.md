# 网页端下载 AI 小说后台对接 API 接口文档

> 关联方案：[网页端下载-AI小说后台对接方案.md](./网页端下载-AI小说后台对接方案.md)
> 配套实施清单：[网页端下载-AI小说后台对接开发文档.md](./网页端下载-AI小说后台对接开发文档.md)
> 文档版本：v0.1（契约不变）| 起草日期：2026-05-12 | 最新修订：2026-05-13（仅文件名 / 链接同步）

## 概述

本文档描述「网页端下载器」与「AI 小说后台」之间的对接 HTTP 接口。AI 小说后台作为服务端，对外暴露以下三个接口供下载器调用：

| # | 接口 | 用途 |
|---|------|------|
| 1 | 查询书籍信息 | 下载器拿到用户填写的 `book_id` 后反查书况，用于校验 ID 真实性 + 拿"最后章节标题"作增量起点反查 + 弹窗里回显书名作者做二次确认 |
| 2 | 上报增量章节 | 下载完成后将增量章节整体回灌到 AI 后台对应书籍 |
| 3 | 健康检查 | 下载器联调期快速验证 `base-url` + `token` 是否配置正确，避免下完整本书才发现链路不通 |

调用方为下载器后端（Java + hutool `HttpRequest`），浏览器不直接调用本接口（鉴权 token 走配置文件，不能下发到前端）。

### 落地位置与现有 agent 路径的关系

**实现位置**：`admin/apps/book_collector/`，与现有 `agent_router.py` 同级。

- 新增：`admin/apps/book_collector/api/web_downloader_router.py`（prefix `/web-downloader`）
- 注册：在 `admin/apps/book_collector/api/router.py` 中以独立 API Key 鉴权方式（不走 JWT）挂载，与 `agent_router` / `crawl_agent_router` 并列
- 完整路径：`/api/book-collector/web-downloader/*`

**与现有 `agent/monitors/{id}/update` 的关系**：AI 小说后台已存在一条服务端派发任务驱动的续采链路（`/api/book-collector/agent/*`，由 `tools/download_agent/` 本地代理调用）。本期网页端下载器走**独立**的 `/web-downloader/*` 路径，**不复用** agent 路径：

| 维度 | 现有 `agent/monitors/{id}/update` | 本期 `web-downloader/report_chapters` |
|---|---|---|
| 触发方 | 服务端预派发 `bc_download_books` 任务，代理轮询拉取 | 网页端用户主动选源，不预派发 |
| 鉴权 Key | `DOWNLOAD_AGENT_API_KEY` | **共用 `DOWNLOAD_AGENT_API_KEY`**（一期不分发独立 Key） |
| `book_id` 来源 | 代理回写时直接用任务派发的 ID | 用户在弹窗内手填，需先调本文档接口 1 反查校验 |
| 来源元数据 | 任务派发时已带 `so_novel_source`，回写无需重传 | 必传 `client_meta.source_name/source_url/app_version` |
| 章节去重 key | `(book_id, source_chapter_no)` + offset 映射 | `(book_id, chapter_no)` 直接 upsert，`source_chapter_no` 写 NULL |
| 换源支持 | 一本书一个源（任务表 `so_novel_url` 锁定）| **支持换源**：每次上报源可不同，去重不依赖源站序号 |

Service 层**不复用** `save_download_result`：现有方法按 `source_chapter_no` 去重 + offset 映射，与本接口"按目标 chapter_no upsert + 支持换源"语义冲突。建议在 `DownloadService` 新增独立方法 `save_web_report(book_id, chapters, client_meta)`，复用现有文件写入路径 + `bc_download_chapters` 表 + 完本检测 / 计数等下游逻辑。

> 续采路径（`/agent/monitors/*`）后续下线规划见 [续采功能下线规划.md](./续采功能下线规划.md)；本接口设计已为此预留兼容（`source_chapter_no` 写 NULL、不依赖 offset 映射），但下线时机不在本文档范围内。

---

## 通用规范

### 请求方式

- Method: `POST`
- Content-Type: `application/json`
- 基础路径: `/api/book-collector/web-downloader/`

### 接口认证

所有请求必须携带以下 Header：

| Header | 说明 |
|--------|------|
| `X-Agent-API-Key` | 共用 `DOWNLOAD_AGENT_API_KEY`（与现有 `agent_router` 同一把 Key，沿用 `agent_auth.require_agent_api_key` 鉴权依赖，配置在 `config/settings.py`）|

认证失败统一返回 401 状态码 + `code=0` 响应体。

### 统一响应格式

```json
{
    "code": 0,
    "status": "error",
    "msg": "错误信息",
    "data": {}
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | 0=失败，1=成功 |
| `status` | string | "error" 或 "success" |
| `msg` | string | 提示信息 |
| `data` | object | 返回数据，失败时为空对象 |

### 超时与重试

- 服务端单请求处理上限：30s（章节上报因含 OSS 写入可能较慢，建议下载器侧 read-timeout ≥ 30s，与方案 §2.5 一致）
- 服务端不对下载器做主动限流；下载器侧自行控制并发与重试节奏
- 5xx / 网络超时由下载器侧最多重试 2 次（指数退避），仍失败由下载器标"未回推"状态人工触发重推

### 幂等口径

章节上报接口按 **`(book_id, chapter_no)`** 二元组 upsert：

- 上报体里的 `chapter_no` 字段含义为**AI 后台 DB 内目标章节序号**（不是源站原始序号），由下载器侧根据接口 1 返回的 `latest_chapter_no` 计算：
  - **追加场景**：`latest_chapter_no = K` → 上报 `K+1, K+2, ..., K+N`
  - **覆盖错章场景**：用户在 AI 后台 UI 上看到第 M 章错了 → 上报 `chapter_no = M`
- 同 `(book_id, chapter_no)` 已存在 → **upsert 覆盖**（更新 `chapter_title / word_count / content_path / status`，并重置 quality 字段触发重审）
- 不存在 → 新增

下载器侧重试 / 用户手动重推 时直接整批重发即可，重复推送同 `chapter_no` 不会出错。

> **为什么不沿用现有 `source_chapter_no` 语义**：现有 `agent/monitors/{id}/update` 用 `(book_id, source_chapter_no)` 去重，前提是"一本书一个固定源站"。本期网页端方案允许用户**主动换源**（甚至不同次上报来自不同源站），跨源 upsert 会出现"Source B 第 50 章覆盖 Source A 第 50 章"但两者内容位置不同的污染。改用目标 chapter_no 后，去重 key 与源站序号解耦，换源不影响入库正确性。详细分析见方案讨论记录。
>
> **DB 列 `source_chapter_no` 写法**：网页端上报章节落库时 `source_chapter_no = NULL`，与现有"手动插入章节 / 重拆分新增章节"行为一致（`chapter_edit_service.py:192, 318`）。
>
> **防误填 `book_id` 污染防护**：服务端不做章节级 cross-check（避免与"覆盖错章"语义冲突）；由下载器侧弹窗调用接口 1 反查书名+作者并要求用户二次确认（详见方案 §5 风险表）承担此防护。

---

## 接口 1：查询书籍信息

### 用途

下载器在弹窗第二步「回显确认」时调用：
1. 验证用户填入的 `book_id` 是否存在
2. 拿 `book_name + author` 回显给用户做二次确认（防止填错 ID）
3. 拿 `latest_chapter_title + latest_chapter_no` 作为增量起点反查依据

### 请求

- URL: `POST /api/book-collector/web-downloader/get_book_info`

### 请求参数 (JSON Body)

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `book_id` | int | 是 | AI 小说后台书籍 ID |

### 校验规则

- `book_id` 必须为正整数
- `book_id` 对应的书籍必须存在且未被删除

### 成功响应

```json
{
    "code": 1,
    "status": "success",
    "msg": "查询成功",
    "data": {
        "book_id": 123,
        "book_name": "斗破苍穹",
        "author": "天蚕土豆",
        "latest_chapter_no": 1245,
        "latest_chapter_title": "第 1245 章 异界之旅",
        "updated_at": "2026-05-10T03:15:22Z"
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `book_id` | int | AI 小说后台书籍 ID（回显） |
| `book_name` | string | 书名 |
| `author` | string | 作者名 |
| `latest_chapter_no` | int | 该书籍当前已入库章节的最大序号；尚无章节时返回 0 |
| `latest_chapter_title` | string | `latest_chapter_no` 对应章节的**完整标题**（含"第 X 章"等序号前缀，与 `bc_download_chapters.chapter_title` 入库格式一致）；尚无章节时返回空串 |
| `updated_at` | string | 最近一次章节入库时间，ISO8601 UTC；尚无章节时返回空串 |

> 说明：
> - `latest_chapter_title` 由下载器侧用作"在源站目录中反查起点"的关键字；下载器会先做严格相等匹配，未命中时拉源站完整目录让用户选锚点（详见方案 §4.2）。本接口返回的标题须**与入库时完全一致**（含"第 X 章"前缀、不做截断、不附加书名等），便于运营者人工识别 + 下载器在源站目录里做字符串匹配；任何归一化（去前缀 / 数字归一）都会显著拉低反查命中率。
> - `latest_chapter_no` 同时也是下载器侧**计算上报 `chapter_no` 的基准**：追加场景下，下载器从 `latest_chapter_no + 1` 开始为每个新章节赋目标序号。

### 失败响应示例

```json
{
    "code": 0,
    "status": "error",
    "msg": "书籍不存在",
    "data": {}
}
```

---

## 接口 2：上报增量章节

### 用途

下载完成后，下载器将本次增量章节整体回灌到 AI 小说后台对应书籍。

### 请求

- URL: `POST /api/book-collector/web-downloader/report_chapters`

### 请求参数 (JSON Body)

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `book_id` | int | 是 | AI 小说后台书籍 ID |
| `chapters` | array | 是 | 章节数组，不强制服务端分批（与现有 `agent/monitors/{id}/update` 一致） |
| `client_meta` | object | 是 | 下载器侧上下文，用于服务端审计 / 运营者排查源站质量 |

#### `chapters` 数组元素结构

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `chapter_no` | int | 是 | **AI 后台 DB 内目标章节序号**（不是源站序号），由下载器先调接口 1 拿 `latest_chapter_no` 再自行计算，必须 >= 1 |
| `title` | string | 是 | **完整章节标题**（含"第 X 章"等序号前缀），与 `bc_download_chapters.chapter_title` 现有数据格式保持一致 |
| `content` | string | 是 | 章节正文（纯文本或 HTML 文本），单章不超过 1 MB |

> **title 取值规则**：直接传源站抓到的完整标题原文，不做去前缀 / 去数字 / 规范化处理。例如源站原标题为"第 1246 章 归途"，则 `chapter_no=1246`（这是 AI 后台 DB 目标序号，与源站序号通常一致；详见下面 `chapter_no` 取值规则），`title="第 1246 章 归途"`。后续推送机翻平台时由 `chapter_push_service` 通过 `clean_title` 字段做去前缀处理，本接口不承担清洗职责。
>
> **chapter_no 取值规则**：
> - **追加场景**：上报前调接口 1 拿 `latest_chapter_no = K`，下载器侧本次共 N 章新增 → 依次传 `K+1, K+2, ..., K+N`
> - **覆盖错章场景**：用户在 AI 后台 UI 上看到第 M 章错了 → 传 `chapter_no = M`，服务端会 upsert 覆盖
> - **混合场景**：单次上报里**可同时**包含覆盖（chapter_no ≤ K）和追加（chapter_no > K），服务端按 (book_id, chapter_no) 逐条判定 insert / update

#### `client_meta` 结构

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `source_name` | string | 是 | 源站名称（如 `笔趣阁主站`、`某起点镜像`），由下载器侧源站规则 ID 映射而来 |
| `source_url` | string | 是 | 源站书籍详情页 URL，用于运营者排查源站质量 |
| `app_version` | string | 是 | 下载器版本号，便于排查"某版本下载器入库异常"的回滚 |

> **为什么不收 `downloader_user`**：本期下载器走配置文件 token 鉴权，没有真实用户登录系统；任何"用户标识"字段都是下载器自报，无服务端验证，作为审计追溯无效用。若后续接入用户登录再补该字段。

### 校验规则

- `book_id` 对应的书籍必须存在且未被删除
- `chapters` 数组不能为空；**服务端不强制单批上限**（与现有 `agent/monitors/{id}/update` 行为一致）；下载器侧在 UI 层做 500 章预警 / 2000 章拒绝（详见方案 §5）
- 每个章节的 `chapter_no`、`title`、`content` 均不能为空
- `chapter_no` 必须 >= 1
- 同一批次内 `chapter_no` 不允许重复（重复直接拒绝整批）
- **不要求章节连续**：允许批次首章 `chapter_no` 落在已有最大序号之前（用户主动覆盖早期错章场景）
- 单章 `content` 长度 ≤ 1 MB（按 UTF-8 字节数判定）
- `client_meta.source_name`、`client_meta.source_url`、`client_meta.app_version` 必填，缺失整批拒绝

### 处理逻辑

#### Upsert 规则（核心）

对批次内每个章节，按 `(book_id, chapter_no)` 二元组判定（`chapter_no` 即上报体里的目标章节序号）：

| 服务端状态 | 处理 | 计入 |
|-----------|------|------|
| 不存在 | 新增：写入章节文件 + DB INSERT（`source_chapter_no = NULL`）| `accepted_count` |
| 已存在 | 覆盖：更新 `chapter_title / word_count / content_path / status`，章节文件按现有 `content_path` 覆写；同步重置 `quality_level / quality_issues / checked_at = NULL` 触发重审 | `updated_count` |

> **不复用 `save_download_result`**：现有 `save_download_result(book_id, chapters)` 走的是 `source_chapter_no` 去重 + offset 映射（`download_service.py:401-447`），与本接口"按目标 chapter_no upsert"语义冲突。
>
> **实现建议**：在 `DownloadService` 新增 `save_web_report(book_id, chapters, client_meta)` 方法：
> - 不算 offset，不写 `source_chapter_no`
> - 按 `(book_id, chapter_no)` 直接 upsert，复用现有文件写入 + `bc_download_chapters` 表
> - 复用 `_check_finished` 完本检测、`_count_completed_chapters` 计数等下游逻辑
> - `client_meta` 走**结构化日志**记录（每次调用一行 `web_downloader_report` 日志，含 `source_name / source_url / app_version / accepted / updated / rejected / rejected_detail`），混在全局 `${LOG_DIR}/app.log`，沿用全局 30 天轮转策略；不引入新表；后续若运营要做"源站质量看板"再升级为独立日志 / 聚合表

#### 入库流程

- **先校验后入库**：所有参数校验全部通过后再开始文件写入 + DB 落库，避免脏数据
- 单批次内整体事务：批次内全部 `accepted` 章节要么全部成功提交，要么全部回滚
- `rejected` 章节不阻塞 `accepted` 入库（即冲突章节单独 reject，剩余正常入库）
- 章节内容写入 `${DOWNLOAD_STORAGE_DIR}/{book_id}/{chapter_no}.txt`（与现有 `agent` 上报同路径，共享 `bc_download_chapters` 表）
- `word_count` 由服务端根据 `content` 自动计算（与现有 agent 上报一致；下载器侧传不传都可以，传了以服务端计算结果为准）
- `client_meta` 字段（`source_name` / `source_url` / `app_version`）走**结构化日志**输出（`logger.info` 单行 `web_downloader_report book_id=... source=... source_url=... app_version=... accepted=... updated=... rejected=... rejected_detail=...`），写入 `${LOG_DIR}/app.log`，沿用全局 30 天轮转（`config/logging_config.py:19` 的 `backupCount=30`）；运营按源站维度统计时用 `grep web_downloader_report ${LOG_DIR}/app.log* | awk` 一条管线即可；不引入独立审计表（下载器无真实用户身份，写表价值不高）
- `bc_download_chapters.source_chapter_no` 列对本接口入库的章节**始终写 NULL**（与"手动插入章节"语义一致），避免与历史代理上报的 source_chapter_no 体系混淆

### 成功响应

```json
{
    "code": 1,
    "status": "success",
    "msg": "章节上报完成",
    "data": {
        "accepted_count": 48,
        "updated_count": 2,
        "rejected": []
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `accepted_count` | int | 本次新增入库的章节数（目标 `chapter_no` 此前不存在）|
| `updated_count` | int | 本次覆盖更新的章节数（目标 `chapter_no` 已存在 → upsert）；对应"用户选早期锚点覆盖错章"场景 |
| `rejected` | array | 因写入异常未入库的章节列表；空数组表示无失败 |
| `rejected[].chapter_no` | int | 失败章节的源站序号 |
| `rejected[].title` | string | 失败章节标题 |
| `rejected[].reason` | string | 失败原因码，见下方 |

#### `rejected[].reason` 取值

| 值 | 含义 |
|----|------|
| `content_empty` | 章节正文为空（理论上前置校验已拦，此处兜底） |
| `content_oversize` | 章节正文超过 1 MB |
| `storage_write_failed` | 章节文件写入本地存储失败（已自动重试 1 次仍失败） |

> 注：仅 `accepted_count + updated_count > 0` 即返回 `code=1`；批次中存在 `rejected` 不视为整体失败。下载器侧需自行解析 `rejected` 列表，与本地 `tasks.json` 状态做对账。`updated_count > 0` 时建议下载器侧在 UI 上提示"本次覆盖了 N 个已有章节"，便于运营者复核。

### 失败响应示例

```json
{
    "code": 0,
    "status": "error",
    "msg": "书籍不存在",
    "data": {}
}
```

---

## 接口 3：健康检查

### 用途

下载器联调期 / 启动期调用，快速验证 `base-url` 可达 + `X-Agent-API-Key` 有效。不依赖任何业务数据。

### 请求

- URL: `POST /api/book-collector/web-downloader/ping`

### 请求参数

无（空 body 即可，`{}` 也接受）。

### 成功响应

```json
{
    "code": 1,
    "status": "success",
    "msg": "ok",
    "data": {
        "server_time": "2026-05-12T08:30:00Z",
        "api_version": "v1"
    }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `server_time` | string | 服务端当前时间 ISO8601 UTC，便于下载器侧排查时钟漂移 |
| `api_version` | string | 当前接口契约版本号，本期为 `v1` |

### 失败响应示例

```json
{
    "code": 0,
    "status": "error",
    "msg": "认证失败",
    "data": {}
}
```

---

## 错误码说明

| 场景 | code | msg 示例 |
|------|------|----------|
| 认证失败（X-Agent-API-Key 缺失 / 无效） | 0 | "认证失败" |
| 参数缺失 | 0 | "book_id 不能为空" |
| 参数类型错误 | 0 | "book_id 必须为正整数" |
| 书籍不存在 / 已删除 | 0 | "书籍不存在" |
| 章节数为空 | 0 | "章节数组不能为空" |
| 单章内容超限 | 0 | "章节内容超过 1MB 上限" |
| 批次内序号重复 | 0 | "批次内 chapter_no 重复：1, 3" |
| client_meta 缺失 | 0 | "client_meta.source_name 不能为空" |
| 章节内容为空 | 0 | "章节内容不能为空" |
| 章节文件写入失败 | 0 | "章节文件写入失败，请稍后重试" |
| 服务端内部错误 | 0 | "服务端处理失败" |

---

## 调用示例

### 查询书籍信息

```bash
curl -X POST https://ai-novel.example.com/api/book-collector/web-downloader/get_book_info \
  -H "Content-Type: application/json" \
  -H "X-Agent-API-Key: 7dfd6c8d-e0e4-4bbd-9fb7-163c5bb4e069" \
  -d '{
    "book_id": 123
  }'
```

### 上报增量章节

```bash
curl -X POST https://ai-novel.example.com/api/book-collector/web-downloader/report_chapters \
  -H "Content-Type: application/json" \
  -H "X-Agent-API-Key: 7dfd6c8d-e0e4-4bbd-9fb7-163c5bb4e069" \
  -d '{
    "book_id": 123,
    "chapters": [
      {
        "chapter_no": 1246,
        "title": "第 1246 章 归途",
        "content": "章节正文..."
      },
      {
        "chapter_no": 1247,
        "title": "第 1247 章 故人",
        "content": "章节正文..."
      }
    ],
    "client_meta": {
      "source_name": "笔趣阁主站",
      "source_url": "https://www.bqg.com/book/12345/",
      "app_version": "web-1.4.0"
    }
  }'
```

### 健康检查

```bash
curl -X POST https://ai-novel.example.com/api/book-collector/web-downloader/ping \
  -H "Content-Type: application/json" \
  -H "X-Agent-API-Key: 7dfd6c8d-e0e4-4bbd-9fb7-163c5bb4e069" \
  -d '{}'
```

---

## 与方案的字段映射

为便于跨端核对，本文档接口字段与方案 [Part 2 §2.3](./网页端下载-AI小说后台对接方案.md) 的 DTO 契约草案对照如下：

| 方案 DTO 字段（camelCase 草案） | 本文档字段（snake_case 落地） |
|---|---|
| `RemoteBookInfo.id` | `book_id` |
| `RemoteBookInfo.bookName` | `book_name` |
| `RemoteBookInfo.author` | `author` |
| `RemoteBookInfo.latestChapterTitle` | `latest_chapter_title` |
| `RemoteBookInfo.latestChapterSeq` | `latest_chapter_no` |
| `RemoteBookInfo.updatedAt` | `updated_at` |
| `RemoteChapterPushItem.seq` | `chapters[].chapter_no`（语义对应：方案 `seq` 含义为源站序号，本期改为 AI 后台 DB 目标序号，避免换源场景下跨源 upsert 污染）|
| `RemoteChapterPushItem.title` | `chapters[].title` |
| `RemoteChapterPushItem.content` | `chapters[].content` |
| `RemotePushRequest.clientMeta.sourceName` | `client_meta.source_name` |
| `RemotePushRequest.clientMeta.sourceUrl` | `client_meta.source_url` |
| `RemotePushRequest.clientMeta.appVersion` | `client_meta.app_version` |
| 方案响应 `accepted` / `duplicated` / `rejected` | `accepted_count` / `updated_count` / `rejected`（语义对应：方案 `duplicated` 在本期落为 `updated_count`，含义从"跳过重复"变为"覆盖已有目标 chapter_no"）|

---

## 待决策事项

| # | 议题 | 备选 | 当前倾向 |
|---|------|------|---------|
| 1 | 单次上报是否要服务端硬上限 | 不限 / 加安全阈值（3000-5000） / 加严格阈值（50-100） | **不限**（与现有 `agent/monitors/{id}/update` 一致；UI 层 500/2000 预警拒绝由下载器侧承担） |
| 2 | 单章 content 大小上限 | 512 KB / 1 MB / 2 MB | **1 MB**（覆盖 95% 长章节）|
| 3 | `web_downloader_report` 日志保留策略 | 独立日志文件 90 天 / 混 app.log 沿用全局 30 天 / 拉高全局到 90 天 | **混在 `${LOG_DIR}/app.log` 沿用全局 30 天**（`config/logging_config.py:19` 的 `backupCount=30`）；不另建专用 logger。后续运营若要"源站质量看板"再升级为独立日志 / 聚合表 |
