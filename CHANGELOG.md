## v1.10.1 (2026-05-14)

### ✨ Features

- 网页端"主动下载并回推 AI 后台"链路 v2：弹窗整合为单步章节预览页（源站完整 toc + 起止章节输入 + 默认 K+1~K+500 + 锚点高亮）
- 单次下载/上报硬上限 500 章（前后端双校验）
- 回推章节按 batch（默认 100 章/批）分批 POST，避免大 body 触发 read-timeout 但后端已 upsert 的"半成功"状态
- 新增 `/toc-preview` 一把抓接口（书况 + 源站 toc + 锚点匹配）
- 新增独立日志 `logs/web-report {date}.log`，`taskId` 串行追踪回推全链路
- WebUI 默认开启（`[web] enabled=1`）、默认下载格式 `txt`

### 🐛 Bug Fixes

- 修复 Maven 资源过滤误伤前端 JS 模板字符串 `${id}`，弹窗 bookId 被打包时写死为 GAV 串
- 修复 ChapterRenderer 渲染 txt 时前置 title 导致上报到 AI 后台的章节内容首行重复标题
- 修复 `RemoteBackendClient` 异常 message 笼统"AI 后台连接失败"，现在会带 HTTP status + body 摘要 + cause class，UI 和日志都能直接定位根因
- 修复不支持的源站 URL 返回 Jetty 默认 HTML 500 页面，改为 400 JSON

### ♻️ Refactor

- `/incremental-download` 简化：`startOrder`/`endOrder` 必填，移除 `needAnchor`/`needConfirm` 多步流程
- 删除 `/remote-book-info`、`/source-toc`（被 `/toc-preview` 覆盖）
- `bundle/config.ini` 加 `report-batch-size`、安全注释（公开仓库禁止 commit 真实 `base-url`/`api-key`）

### 📝 Documentation

- 新增 `docs/Windows-11-打包指南.md`
