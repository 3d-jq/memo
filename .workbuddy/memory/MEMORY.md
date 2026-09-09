# Memo 项目长期约定（curated）

## 工作约定：UI/UX 忠于原项目，功能逻辑直接搬 RikkaHub（2026-09-09 用户明确）
- **UI/UX 必须 1:1 忠于 Memo 现有视觉**（即 Flutter 上游那套：思考图标 idea-01、thinkingSheen 呼吸高光、预览渐隐、卡片背景/圆角/图标轨、CardPress 触感等）。用户明确："我只是喜欢原项目的 UI 和 UX"。
- **功能/逻辑代码可以直接用 RikkaHub 的**：Memo 本身就从 RikkaHub 借鉴了很多，两边很多地方一致。遇到功能 bug 或要新增能力，优先移植 RikkaHub 对应实现（Compose 侧 `ChatMessageReasoning.kt` 等），而不是在 Memo 里另起一套或硬仿 Flutter 内部机制。
- RikkaHub 本地克隆：`D:\program\.rikkahub-ref`（AGPL-3.0，同 Memo 是兄弟 LLM 客户端；`ai` 模块对应 `core:llm`，app 模块对应 `app`）。
- 实际操作边界：视觉组件（图标/动画/排版/间距/配色/手势）保持 Memo 现状不动；可变的是底层状态机、计时、数据流、算法这类"功能代码"。
- 此约定覆盖 AGENTS.md 里"不要把 Flutter 内部机制用安卓重实现"的措辞——落地的判据是：视觉对齐原项目、逻辑可直采 RikkaHub。
- **已确认例外（2026-09-09 用户明确，"rikkhub怎么样就怎么样"）：联网搜索引用胶囊整体换 RikkaHub 实现**——圆形胶囊（CircleShape、tertiaryContainer 20% 底、域名文字 10sp 等宽 Thin 居中、width=长度×7sp、height=1em），prompt 让模型写 `[citation,domain](id)` 带域名元数据（原项目 `[cite:id]` 无元数据、只能显示数字，用户嫌不好看）。历史 `[cite:id]` 归一化后域名从 search_items 反查回退。其余 UI/UX 仍 1:1 原项目。

## 工程纪律（沿用用户长期要求，最高优先级）
- 所有改动都要有测试覆盖；`flutter test` / `./gradlew testDebugUnitTest` 全绿、0 warning 0 info 才能提交。
- 遗留问题（lint warning、deprecated、硬编码 magic number）当场解决，不拖。
- 绝不擅自改用户没提/没确认的逻辑；方案先讨论 → 用户点头 → 再动手。
- 同文件多 Edit 必须串行（防整文件写回互相覆盖）。
