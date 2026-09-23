# Changelog

本文件记录 Memo（Kotlin / Jetpack Compose 原生版）的显著变更。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；版本号对应
`app/build.gradle.kts` 的 `versionName`（`versionCode` 单调递增）。

## [1.0.14] — 2026-09-23

本轮主线：**照 DeepSeek 开源 harness（`deepseek-harness`）的做法提升「任务完成稳定性」与「工程化」**——
不是抄代码，而是抄它的制度：每个事实只有一个所有者、顺序带、唯一渲染路径、fail loud、
机械可检的不变式（详见 `memo-android/docs/ENGINEERING_HARNESS.md`）。

### 任务完成稳定性（提示词装配）

- 系统提示词装配收成**唯一出口** `assembleSystemPrompt()`，并引入**顺序带**：
  `persona 0 < 工具纪律 100 < 记忆规则 110 < 搜索 120 < 技能 130 < 工作区 140 < 用户注入 900`。
  此前有两处各自 `joinToString("\n\n")`（两条渲染路径），顺序靠代码先后隐式决定。
- 未登记的 section 来源**直接抛错**（misconfiguration fails loud），不再静默通过。
- 「可用变量」清单**单一所有者**：唯一来源是 `PromptTransformer.supportedKeys()`，
  助手编辑页只读 `PromptVariableCatalog`，两边一致性由测试双向钉住。
- **未知变量不再静默**：`{cur_dtae}` 这类手滑会记一条告警（此前会原样发给模型而无人知晓）。
- **日志必须等于请求**：修掉一处真漂移 —— 请求按带位排序、日志却按加入顺序，导致排查时被误导；
  现在渲染与日志共用同一份规范化 `orderedPromptParts()`，由不变式测试守护。

### 生成中提示（对齐 RikkaHub）

- **流式等待提示对齐到助手气泡并加底板**（PORTING §5.50）：此前它作为列表末尾独立一行，现在挂在
  助手气泡内、与内容同宽，不再「浮在列表外」。
- **生成中指示器 1:1 照 RikkaHub**（PORTING §5.51）：没有底板、没有文字扫光，只有一枚 28dp 图标在动
  （`MemoLoadingIndicator`）；自动重试倒计时仍显示文字。
- **生成中提示加「形态开关」**（PORTING §5.52，显示设置 → 渲染 →「提示样式」）：
  应用图标（出厂）/ 文字扫光两种形态可切换；文字扫光那套字号/颜色/提示词设置**不再影响聊天底部**。
- **技能调用引导加硬 + 新增「工具纪律」系统提示块**（PORTING §5.53，`provider/ToolRules.kt`）：
  「只有工具成功返回才算做了」——针对「大模型说做了、其实没做」。

### 工具执行

- 新增工具执行骨架 `ToolRunner` + `ToolResults`（`provider/tool/ToolExecution.kt`）：
  - **统一 deadline**（策略集中一处；`render_mermaid` 35s ＞ 其内部 25s，避免双重超时），
    超时回 `tool_timeout` 而不是挂死整轮对话；
  - **抛错 ⇒ isError 不吞**：任何异常归一成 `tool_crashed`，`CancellationException` 透传；
  - **结果有上限**（24k 字符）且**标明截断**，不许把截断当完整结果；
  - **规范结果值**：成功 `{type, status:"ok", tool, …}`、失败 `{type:"tool_error", status:"error", error, message, tool}`。
- 随之退休了「图已进对话、**别跟用户说失败**」这类散文叮嘱 —— 形状本身带 `status` 之后不需要叮嘱。

### 修复

- **侧边栏不显示对话时间/日期分组**：同一个偏好键存在两种存储形态（裸布尔 `true`/`false` 与 `"1"`/`"0"`），
  抽屉只认后者，于是老键读成关。新增 `DisplayPrefs` 作为布尔偏好的**唯一入口**（读两种、写 `"1"`/`"0"`），
  统一了抽屉、显示设置、`ChatViewModel` 三处读数。
- 图片查看器：保存/分享的提示改用**项目自己的 toast**（查看器是独立 Dialog 窗口，
  根部挂的提示会被盖住；此前一度退化为系统 Toast）。

### 新增

- 显示设置 →「聊天项显示」→ **显示助手头像**（`display_show_assistant_avatar_v1`，默认关）。
  **本项目新增**（上游只有头像选择器，没有消息头像开关）；关闭时完全维持上游行为。
- 可视化工具：`render_visual` 补齐 **10 种图表**（新增横向条形、环形、漏斗、仪表盘、热力图）
  与 `kind="svg"` 手写兜底；**新增独立的 Mermaid 工具** `render_mermaid`
  （内置 mermaid.js + 离屏 WebView 渲染，覆盖流程图/时序图/状态图/ER/类图/甘特/思维导图/时间线等）。
- SVG 产物现在可正常**保存到相册/分享**（先栅格化成 PNG）并计入存储页「图片」档。

### 工程化

- 新增 `memo-android/docs/ENGINEERING_HARNESS.md`：可执行的工程条款（单一所有者 / fail loud /
  证据匹配被改的面 / 不变式+反例 / 教训入门禁 / 任务记录制度）。
- 新增 `memo-android/tools/invariant_checks.sh`：机械门禁（工具描述禁硬编码颜色、导航回调禁
  `= {}` 默认值、禁一行空 catch、禁系统 Toast、换行/空白），`--self-test` 为每条规则喂反例自证。
- 门禁上线即修掉两处真问题：工具描述里写死的主题色（改为主题在请求时注入）、
  3 个日志文件里 18 处空 catch（补上「吞了什么」）。

### 说明

- 本轮**未**包含：长任务 handle（job id / cancel / readOutput）、工具卡投影的纯函数约束、
  以及把其余本地工具逐个改造成规范出参 —— 留待后续批次。

---

## [1.0.13] — 2026-09-18

- 可视化工具：新增 `render_visual`（结构化图表 + 手写 SVG）、`render_mermaid`（Mermaid）；
  主题注入、消毒管线（脚本/外链/体积/布局盒校验）、保存分享与存储页归类。

---

<!-- English summary for the release notes -->

### English summary (v1.0.14)

This release borrows the *engineering discipline* of DeepSeek's open-source agent harness
(`deepseek-harness`) rather than its code: one owner per fact, ordered prompt bands, a single
render path, fail-loud misconfiguration, and mechanically checkable invariants.

- **Prompt assembly**: single render path with explicit order bands; unknown placeholders are
  reported; the context log is now guaranteed to equal the request payload.
- **Tool execution**: unified deadline, exception normalisation (`isError`, cancellation
  passes through), a 24k result cap that always marks truncation, and a canonical
  `status`/`error` result shape — prose coaching of the model is gone.
- **Fixes**: sidebar date-group headers were silently off (boolean preferences had two
  storage encodings; now a single `DisplayPrefs` entry point); the image viewer uses the
  in-app toast again.
- **New**: a "show assistant avatar" switch under Display → Chat item display; five extra
  chart kinds; a standalone Mermaid tool; SVG artefacts can be saved/shared as PNG.
- **Engineering**: `docs/ENGINEERING_HARNESS.md` + `tools/invariant_checks.sh`
  (a mechanically checkable gate with self-tests that prove each rule rejects a bad input).
