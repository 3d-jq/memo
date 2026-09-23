# Changelog

Memo（Kotlin / Jetpack Compose 原生版）的显著变更。版本号对应 `app/build.gradle.kts` 的 `versionName`。

## [未发布]

### 修复

- 聊天项显示里的开关点了不生效（如「显示助手头像」）：显示设置是同屏叠层，宿主缓存的设置不刷新 —— 现在写入偏好会递增一个版本号，消费者以它作为缓存 key，改完立刻生效。
- 布尔偏好只认 `"1"`，早期写入的裸 `true`/`false` 会读成「关」：统一走 `DisplayPrefs` 唯一入口，两种存储形式都认。

## [1.0.14] — 2026-09-23

### 新增

- 「显示助手头像」开关（显示设置 → 聊天项显示；本项目新增，上游只有头像选择器，默认关）。
- 生成中提示「形态开关」：应用图标 / 文字扫光，可切换。
- 图表补齐 10 种：新增横向条形、环形、漏斗、仪表盘、热力图。
- 独立的 Mermaid 工具：流程图 / 时序图 / 状态图 / ER / 类图 / 甘特 / 思维导图 / 时间线等。
- `CHANGELOG.md`。

### 修复

- 侧边栏不显示对话日期分组（同一个偏好键存在两种存储形式，抽屉只认其中一种）。
- 图片查看器保存 / 分享的提示看不见（查看器是独立对话框窗口，改在框内显示项目自己的提示）。
- SVG 产物点「保存」没反应、也不计入存储页图片：保存 / 分享前先栅格化成 PNG，存储页认 `.svg`。

### 改进

- 系统提示词装配收成唯一出口并引入顺序带；未知变量不再静默；上下文日志与实际请求保证一致。
- 工具执行统一骨架：统一超时、异常归一、结果长度上限、规范结果状态。
- 生成中指示器照 RikkaHub 一比一；流式等待提示对齐到助手气泡。
- 工程化：新增 `docs/ENGINEERING_HARNESS.md` 与 `tools/invariant_checks.sh`（7 条机械门禁，含反例自检）。

## [1.0.13] — 2026-09-18

### 新增

- 可视化工具 `render_visual`：10 类结构化图表 + 手写 SVG 兜底，按主题取色，含安全消毒管线。

### 修复

- 手写 SVG 渲染不出来（自闭合标签被写重复、AndroidSVG 不认 `orient="auto-start-reverse"`）。
- 保存 / 分享 SVG 时相册里是坏图。

---

<details>
<summary>English summary</summary>

- **1.0.14** — Prompt assembly with a single render path and explicit order bands; canonical tool
  results (timeout, error normalisation, size cap); sidebar date grouping fixed; new "show assistant
  avatar" switch; five extra chart kinds; standalone Mermaid tool; PNG export for SVG artefacts.
- **1.0.13** — `render_visual` (charts + hand-written SVG) with theme-aware colours and a sanitising
  pipeline; fixes for hand-written SVG failing to render.

</details>
