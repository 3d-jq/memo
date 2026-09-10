# Memo 项目长期约定（curated）

## 工作约定：UI/UX 忠于原项目，功能逻辑直接搬 RikkaHub（2026-09-09 用户明确）
- **UI/UX 必须 1:1 忠于 Memo 现有视觉**（即 Flutter 上游那套：思考图标 idea-01、thinkingSheen 呼吸高光、预览渐隐、卡片背景/圆角/图标轨、CardPress 触感等）。用户明确："我只是喜欢原项目的 UI 和 UX"。
- **功能/逻辑代码可以直接用 RikkaHub 的**：Memo 本身就从 RikkaHub 借鉴了很多，两边很多地方一致。遇到功能 bug 或要新增能力，优先移植 RikkaHub 对应实现（Compose 侧 `ChatMessageReasoning.kt` 等），而不是在 Memo 里另起一套或硬仿 Flutter 内部机制。
- RikkaHub 本地克隆：`D:\program\.rikkahub-ref`（AGPL-3.0，同 Memo 是兄弟 LLM 客户端；`ai` 模块对应 `core:llm`，app 模块对应 `app`）。
- 实际操作边界：视觉组件（图标/动画/排版/间距/配色/手势）保持 Memo 现状不动；可变的是底层状态机、计时、数据流、算法这类"功能代码"。
- 此约定覆盖 AGENTS.md 里"不要把 Flutter 内部机制用安卓重实现"的措辞——落地的判据是：视觉对齐原项目、逻辑可直采 RikkaHub。
- **联网搜索引用胶囊：已撤销 RikkaHub 例外，回归原项目样式 + 整体缩小一档**（2026-09-10 用户明确"显示改成圆形、不显示链接、改成数字那种，就是原项目那种……不考虑兼容性，一锤定音"；随后实测又要求"小一点，有点影响阅读了，太显眼了"）。现行做法：**16dp 高**圆胶囊（圆角 8dp = 高度/2、最小宽 16dp、左右 3dp 内边距、左右各 2dp 外边距、**10sp** 常规字重、primary **16%** 底）、**标签恒为数字序号**（解不出显示 `?`）、prompt 回到原项目 `[cite:id]`。原项目本是 20dp/12sp/20% 底，缩小一档属**用户明确认可的偏离**，别改回去。历史 `[citation,domain](id)` 仍可解析但域名被丢弃。落地文件：`core/ui/.../markdown/MarkdownRenderer.kt`（`citationInlineContent` + `CITATION_BADGE_*` 常量 / `resolveCitationCapsule`）+ `app/.../search/SearchToolService.kt`（提示词）。
  - **Compose 专属坑（务必遵守）**：`PlaceholderVerticalAlign` 要用 **`AboveBaseline`**，**不能**照抄原项目的 `TextCenter + translate(0,-2)` —— Compose 的 `TextCenter` 对齐**行盒中心**（浮在 CJK 字形上方），叠加 -2dp 会让角标明显悬空（用户实测"和输出文字没有水平对齐"）。`AboveBaseline` + 下移 1dp 才视觉齐平。另：`TextUnit` 不支持 `+`，宽度要在 dp 域算完再 `toSp()`。
  - **教训：UI 细节以原项目为准，但用户实测反馈优先；跨框架移植对齐语义不能照搬数值（Flutter 默认基线 vs Compose TextCenter 语义不同）。把可调度量抽成命名常量，微调成本低。**

## 思考卡（reasoning segment）折叠时机 = 原项目规格（2026-09-10 完整移植）
- **折叠必须发生在流式过程中，不是等整轮回复结束**。曾在最终结束处统一折叠 → 用户实测"完成了不会折叠，要全部完成后才折叠，跟原项目不一样"，属**回归**。
- 原项目 `stream_controller.dart` 共 **6 个**「思考阶段结束」触发点：① L853 工具调用开始 ② L1232 正文开始到达 ③ L1280 流正常结束 ④ L1339 用户取消 ⑤ L1355 出错 ⑥ `finishReasoningIfNeeded` 兜底。每处三步：打 `finishedAt` → 若 `display_auto_collapse_thinking_v1` 开启则 `expanded = false`。新段初始 `expanded = !autoCollapse`。
- 归口实现（改这块只动这三处，别处别再折一遍）：
  - `core/data/.../model/ReasoningSegment.kt` — 纯函数 `finishLastOpenSegment(segments, now, autoCollapse)`（幂等；autoCollapse=false 只打戳）。
  - `core/llm/.../stream/StreamChunkHandler.kt` — 构造参数 `autoCollapse: () -> Boolean`（每次实时读）+ `onSegmentClosed`；`appendText` 首字到达 / 工具调用开始即 `closeOpenSegment()`。**每个 HTTP 轮次新建一个 handler**（每轮自带 Finish，复用会 block）。
  - `app/.../ChatViewModel.kt` — `roundHandler` + 局部 `roundUpdate()` 把流中折叠直推 UI；`stop()` 取消路径走 `closeOpenReasoningSegment`。
- **教训（推广）：「UI/UX 忠于原项目」不只含静态视觉，也含交互时序**——什么时候折、什么时候转，都是规格的一部分，不许简化成"结束时统一处理"。

## Markdown 表格渲染 = `TextMeasurer` 实测列宽，绝不按字符数（2026-09-10 用户拍板）
- **现状**：`core/ui/.../markdown/MarkdownRenderer.kt` 已有完整 GFM 表格渲染（表头 13sp w600 / 正文 13.5sp / `height 1.42` / padding 10×9 / 表头 primary 底 / `outlineVariant` 边框 / r12 圆角卡片；≥4 列固定列宽 + 横向滚动，对齐原项目 `_compactColumnWidth` L3522）。
- **列宽必须是 `TextMeasurer` 实测**（对齐原项目 `FlexColumnWidth`）。曾按字符数算权重 → `"排名"`(2 字) vs `"2 小时 45 分钟"`(9 字符) 差 6 倍 → 第一列被压到 1/6 宽、整列裁掉，用户明确不满："没有什么好用的库来渲染这个表格吗 你自己手写 问题太大了"。
- **不换第三方库**（用户已拍板）：mikepenz 等库要求 JetBrains `ASTNode`，与现有 commonmark AST 不兼容（换库=重写整条解析链 + 迁引用胶囊），且库也是等宽列、不如原项目。
- **边框只画内部线**（对齐 `TableBorder(horizontalInside/verticalInside)`）：`HorizontalDivider` 画行底边（最后一行不画）+ 单元格 `drawBehind` 画右边界（最后一列不画）；外框交给圆角卡片。**别用 `Column.border()` 画外框**（滚动容器里会画出一条贯穿全高的多余竖线）。
- 单元格 `Text` 必须 `Modifier.fillMaxWidth()`：长「拉丁+全角标点」串（`RikkaHub、`）无宽度约束时会溢出表格右边界。对齐用 `textAlign`，外层 Box 别再留 `contentAlignment`。
- **工具栏与行分页已移植**（2026-09-10，commit `ec6d629`）：38dp 条（`headerBg` 底 / 底边 `outlineVariant` α0.20暗·0.28亮 / 0.6dp hairline）+ 左「表格」标签 12sp·w600·α0.80 + 右三钮（15dp·minSize32·padding7·α0.68，各裹 M3 `TooltipBox + PlainTooltip`）：复制（tap=markdown / 长按=为图片）· 保存图片（`Pictures/Memo`）· 导出 CSV（SAF `CreateDocument("text/csv")`，`{stem}_{iso}.csv`）。行分页首屏 **40**、每次 **+100**。序列化在 `MarkdownTableText`（`toCsv`/`toMarkdown`/`csvCell`，逐行对齐 `_rowsToCsv`/`_rowsToMarkdown`/`_csvCell` L4014-4075）。
- **平台动作必须由 app 注入**：`core:ui` 无 `activity.compose`、无 app 依赖 → 用不了 `rememberLauncherForActivityResult`、也拿不到 `IosIconButton`（在 app 模块）。范式：`MarkdownTableActions`（app 侧 `MarkdownTableActions.kt` 实现剪贴板/SAF/MediaStore，**null 即不画该按钮**），经 `MarkdownText(tableActions=...)` 沿 `MarkdownBody`/`MarkdownNode` 递归链透传。
- **测试**：`core:ui` `MarkdownTableTest` 12 例（解析层）+ `MarkdownTableTextTest` 14 例（序列化）+ `app` `MarkdownTableLayoutTest` 10 例 Robolectric **布局/widget** 断言（锁「内容绝不越过视口右边界」+ 工具栏接线）。

## Compose 截图导出（`GraphicsLayer`）：两道必过（2026-09-10 血泪）
- **API 位置**（ui-graphics 1.8.3）：`rememberGraphicsLayer()` 在 `androidx.compose.ui.graphics`；类型 `androidx.compose.ui.graphics.layer.GraphicsLayer`。录制是 **`DrawScope` 的扩展**（非 `GraphicsLayer` 成员）：`layer.record(IntSize) { this@drawContent.drawContent() }`；回放 `drawLayer(layer)` 在 **`androidx.compose.ui.graphics.layer`** 包（**不是** `.drawscope`）。`toImageBitmap()` 是 **suspend 成员**（无顶层扩展可 import）。接口默认方法的参数名解析不到 → 报 `No parameter with name 'x'` 时改**位置参数**。
- **必过一：硬件位图**。`toImageBitmap()` 返回 **hardware bitmap**，软件 `Canvas` 拒画（`Software rendering doesn't support hardware bitmaps`）→ 先 `asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)`。
- **必过二：不透明底**。截下的图层是**半透明**的（卡片靠页面 `surface` 透底），直接 `compress()` 写文件透明像素变**黑** → 「上亮下暗」。先 `canvas.drawColor(主题 surface)` 再 `drawBitmap`。原项目 L3269 已记 *"Capture must be opaque"*。
- **半透明色调一律 `alphaBlend` 合成**：原项目 `headerFill = Color.alphaBlend(primary@α, surface)`（暗 0.15/0.04、亮 0.07/0.015）。**别直接 `background(primary.copy(alpha=…))`**——透出页面背景后发灰发浑，深色模式（`primary` 是浅色）尤其明显。工具：`com.psyche.memo.ui.theme.alphaBlend(fg, fgAlpha: Double, bg)`。
- **吞异常 = UI 不可诊断**：平台 IO 的 `catch` 必须把 `e.message` 透出到提示（原样只显示 `保存失败: png`，改成真实消息后才一眼看到 hardware bitmap 报错）。
- **别在子组件里重复读 `isSystemInDarkTheme()`**：深色判定由调用方传入透传（`TableRowView` 曾自读，忽略主题覆盖后与外层 `isDark` 打架）。

## Compose 表格/容器布局：必须真机截图 + 布局断言（2026-09-10 血泪）
- 表格列宽返工 **5 轮**（`IntrinsicSize`+weight 错乱 → 显式均分裁列 → 按字符数权重裁列 → 长串溢界），每轮"代码看着对、真机错乱"。
- 结论：**Compose 容器类布局不能只靠读代码判断**。改完必须 ① `assembleDebug` + `adb install -r`（debug 包名 `com.psyche.memo.dev`！）② `adb shell screencap` 读图自检 ③ 把不变量写成 Robolectric 布局断言（模板：`app/src/test/.../DrawerAndChatUiTest.kt`，`@RunWith(RobolectricTestRunner)` + `createComposeRule` + `getUnclippedBoundsInRoot`）。
- 常见坑：`Modifier.weight()` 在 `IntrinsicSize` 约束下不可靠；`Text` 无宽度约束会按 ink 宽度溢出；emoji 走更高 fallback 字体撑高行（用 `LineHeightStyle(trim=Trim.Both)`）。

## 工程纪律（沿用用户长期要求，最高优先级）
- 所有改动都要有测试覆盖；`flutter test` / `./gradlew testDebugUnitTest` 全绿、0 warning 0 info 才能提交。
- 遗留问题（lint warning、deprecated、硬编码 magic number）当场解决，不拖。
- 绝不擅自改用户没提/没确认的逻辑；方案先讨论 → 用户点头 → 再动手。
- 同文件多 Edit 必须串行（防整文件写回互相覆盖）。

## 提示词（tip）交互约定（2026-09-09 用户两次纠正后确认）
- 设置行 tip ≠ 裸副标题。原项目 `_iosSwitchRow`：`subtitle` 裸排（12sp@56%）；`tip` 是 MemoryTipIcon（28dp BadgeInfo 16sp@45%）+ Flutter Tooltip **浮动气泡**（tap 触发、preferBelow、maxWidth 280、点别处收起），M3 对应 `TooltipBox + PlainTooltip` + tap→state.show()（isPersistent=true）。
- **教训**：交互形态（浮泡/行内展开/跳转）也属 UI/UX 1:1 范围——改 UI 交互前必须先读原项目组件源码（widget 全定义），不许拿代码库已有的简化版（如 MemoryUi.kt 的行内展开）当参考替代原项目。
