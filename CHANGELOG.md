# Changelog

Memo（Kotlin / Jetpack Compose 原生版）的显著变更。版本号对应 `app/build.gradle.kts` 的 `versionName`。

## [1.0.17] — 2026-09-24

### 修复

- **呼吸圆点位置**：不再额外加一层水平缩进 —— 圆点渲染在助手消息的内容列里，继承消息自身缩进，
  与气泡 / 正文**左对齐**（上一版双重缩进导致偏右）。
- **圆点颜色跟随主题色**：改用 `colorScheme.primary`（上一版用 `onSurface`，浅色主题下就是黑 ✗）。

### 改进

- **输出结束不再硬跳**：删掉「结束后明确到底一次」的瞬间 snap —— 结束后长高的操作行 / Token 统计 /
  思考卡收起，全部交给每帧指数收敛**连续吃掉**（丝滑 ✓，照 Agora：它也没有这一步）；宽限窗口 450ms → 800ms。
- **呼吸圆点与操作按钮共用同一条常驻尾行**（照 Agora 尾部 Box）：行不再随 `AnimatedVisibility` 折叠/展开，
  结束时没有任何高度跳变；圆点自带**渐显**（alpha 400ms，颜色跟随主题色 primary）。

## [1.0.16] — 2026-09-23

### 修复

- **流式输出跟不上底部**（圆点被顶到输入栏下面、结束也没到底、底下还能往上拉出空间）：跟随循环自己写的
  `dispatchRawDelta` 会把 `LazyListState.isScrollInProgress` 置真，而门控里又用了 `!isScrollInProgress`
  ⇒ 下一帧把自己关掉（自锁）。去掉这个门；用户在滑动/惯性时由「是否贴底」把关，程序化滚动期间照常追底。
- 输出结束也会**明确到底一次**（结束后尾部还会长高：操作行 / Token 统计 / 思考卡收起，平滑跟随可能还差一点）。

### 改进

- **呼吸圆点改成「长在助手消息尾部」**（照 Agora `AssistantMessageContent.kt:768`）：它跟着回复走，
  不再作为列表末尾独立项被贴底钉在输入栏上方。
- 圆点**渐显**：出现时 alpha 0→1（400ms FastOutSlowIn）+ scale 0.55→1，消失直接 snap（照 Agora
  `StreamingTailIndicator`）；恒定 24dp 槽位、只做绘制态 ⇒ 出现/消失都不推动会话布局。
- 跟随参数换成 Agora 那组（时间常数 0.055s、单帧速度上限 2800px/s、最小步长 2px）。

### 移除

- 列表末尾的独立「流式等待提示」项，以及「流式等待提示」那组设置（字号/颜色/提示词）——
  它们只服务已移除的文字扫光形态。

## [1.0.15] — 2026-09-23

### 修复

- **流式输出时等待提示上下抖动**：贴底原先由数据事件驱动（每个 chunk 贴一次），而尾部高度是异步落地的（Markdown 后台解析、代码块高度动画），两个节奏对不齐就会「贴一下、被顶高一行、再贴回来」。改成**由布局变化驱动、由「读者是否贴底」把门**（与 RikkaHub、deepseek-harness 同一做法），并修掉「到底」时少滚一行（哨兵下标漏算了流式提示项）。
- 聊天项显示里的开关点了不生效（如「显示助手头像」）：设置页是同屏叠层，宿主缓存的设置不刷新 —— 写入偏好现在会递增版本号，消费者以它作为缓存 key，改完立刻生效。
- **「显示助手头像」语义改为真正的显示/隐藏，且默认打开**：打开 ⇒ 显示助手头像（未设头像用首字母圆牌回退）；关闭 ⇒ 不画头像，回落上游「模型图标」开关。（旧实现「打开=强制、关闭=维持上游」导致默认关闭时仍显示头像。）
- 流式等待提示的观感换成**呼吸圆点**（11dp、颜色跟随主题、scale 0.55⇄1.30 @1s），并移除「提示样式」设置入口。
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
