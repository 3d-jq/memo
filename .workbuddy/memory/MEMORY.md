# Memo 项目长期约定（curated）

## 工作约定：UI/UX 忠于原项目，功能逻辑直接搬 RikkaHub（2026-09-09 用户明确）
- **UI/UX 必须 1:1 忠于 Memo 现有视觉**（Flutter 上游那套：思考图标 idea-01、thinkingSheen 呼吸高光、预览渐隐、卡片背景/圆角/图标轨、CardPress 触感等）。用户："我只是喜欢原项目的 UI 和 UX"。
- **功能/逻辑代码可以直接用 RikkaHub 的**（`D:\program\.rikkahub-ref`，AGPL-3.0）：遇到功能 bug 或新增能力优先移植 RikkaHub 对应实现，不在 Memo 里另起一套。落地判据：视觉对齐原项目、逻辑可直采 RikkaHub。此约定覆盖 AGENTS.md 的措辞。
- **「忠于原项目」包含交互时序**（思考卡折叠时机 2026-09-10 教训）和**交互形态**（tip 浮泡 2026-09-09 教训），不只静态视觉。改 UI 前必须先读原项目组件源码全定义。
- **上游主题关键认知**：页面背景 = palette 卡色沉 4 tone（light），`cs.surface` 行卡在页面上「同色隐形」是有意设计（2026-09-14）——别凭直觉给行卡补卡片底/描边。
- **设置页分组标题＝主题色 `primary`**（用户 2026-09-14：「主题设置里面那个分类的字的颜色没有跟着主题走呀 rikkhub就可以呀」）：判据集中在 `SettingsUi.kt` 的 `settingsSectionHeaderColor(scheme)`，五个自绘标题共用（`SectionHeader`／主题页「自定义主题」／内存设置／搜索服务／记忆追踪）；照 RikkaHub `CardGroup.kt:157`（`LocalContentColor provides colorScheme.primary`）。原先一律写死 `onSurface@80%`，换主题时不动。只改颜色，字号/字重/间距不动。测试 `SettingsSectionHeaderColorTest`。

## 引用胶囊 / 思考卡：已偏离原版的现行规格（别按原版改回去）
- **联网搜索引用胶囊**（2026-09-10 一锤定音 + 缩小一档）：16dp 高圆胶囊、10sp、primary 16% 底、标签恒为数字序号（解不出显示 `?`）。落地 `core/ui/.../markdown/MarkdownRenderer.kt` + `app/.../search/SearchToolService.kt`。
  - **Compose 坑**：`PlaceholderVerticalAlign` 用 `AboveBaseline`（Compose `TextCenter` 对齐行盒中心，照抄 Flutter `TextCenter+translate` 会悬空）。`TextUnit` 不支持 `+`，宽度 dp 域算完再 `toSp()`。
- **引用来源筛选**（2026-09-12 用户拍板）：`extractCitationItems` 弃工具名白名单改**按结构判定**（JSON 且带 `items[]` 就收）；解不出序号**整段不画**（不渲染 `?`）。两道门：①结构 ②必须 JSON（纯文本结果静默丢弃）。取证坑：`message_part_rows.revision_id` → `message_rows.id`（`part_id` 是自增主键，按它分组全错）。
- **思考卡折叠时机 = 流式中**（2026-09-10）：6 个结束触发点（工具调用开始/正文首字/流结束/取消/出错/兜底），`finishLastOpenSegment` 幂等纯函数归口；handler 每轮新建。归口三处：`ReasoningSegment.kt` / `StreamChunkHandler.kt` / `ChatViewModel.kt`。

## Markdown 表格渲染 = `TextMeasurer` 实测列宽，绝不按字符数（2026-09-10 用户拍板）
- `core/ui/.../markdown/MarkdownRenderer.kt` 完整 GFM 表格渲染；列宽 TextMeasurer 实测（字符数权重曾把宽列压到 1/6）。不换第三方库（用户拍板）。
- 边框只画内部线（HorizontalDivider 行底边 + drawBehind 右边界）；单元格 `fillMaxWidth()` 防溢出。工具栏/行分页已移植（commit `ec6d629`）。
- **平台动作由 app 注入**（`core:ui` 无 activity.compose）：范式 `MarkdownTableActions`，null 即不画。测试 12+14+10 例。

## Compose 通用血泪（跨场景复用）
- **容器布局必须真机截图 + Robolectric 布局断言**（表格列宽返工 5 轮的教训）：`assembleDebug` + `adb install -r`（debug 包名 `com.psyche.memo.dev`）+ `screencap` 自检 + 布局断言（模板 `DrawerAndChatUiTest.kt`，`getUnclippedBoundsInRoot`）。
- **截图导出两道必过**：hardware bitmap 先 `asAndroidBitmap().copy(ARGB_8888)`；不透明底先 `drawColor(surface)`。半透明色调一律 `alphaBlend` 合成，别直接 `copy(alpha=)`。吞异常必须透出 `e.message`。
- **Kotlin daemon 崩溃后增量编译会报一堆不相干 Unresolved reference**——先 `git diff` 确认文件没坏，然后 `./gradlew --stop` + 删 `app/build/kotlinCaches` 重建，别慌着重写代码（2026-09-14）。
- **原版「布局前回调」读几何不能照搬**：Compose `LaunchedEffect` 协程体可能在本帧 layout 之后跑，`layoutInfo` 已是新几何 → 判据静默失效。换组合内等价状态表达（如 `following`），抽纯函数 + 单测（2026-09-13）。
- **KDoc 里别写 `/**`**：Kotlin 支持嵌套块注释，注释内出现 `/*`（如路径 `xxx/linux/**`）会开一个不闭合的嵌套注释 → `Unclosed comment` + 一堆不相关的 Unresolved reference（2026-09-15）。
- **组合期禁止查库**：`AppContainer.providerConfig()` 曾经每次「建 DAO + 查库 + 解 JSON」，是「打开界面就卡顿」的元凶；现已加 `ProviderConfigCache`，失效挂在 `PayloadEntityDao` 写 `provider_rows` 时。新写读配置的代码走容器方法，别再自己 new DAO 查。
- **`filesDir` 里 99% 的文件是沙箱 rootfs**（`workspaces/<助手 id>/linux`，真机 34k/34.6k）：任何遍历 filesDir 的新功能都要跳过它，否则「永远算不完」（2026-09-15 存储统计就是这么卡住的）。
- **同屏二级页必须拦系统返回**：一批「二级页」是同屏叠在宿主上的一层 composable（记忆提示词模板、记忆追踪详情、统计榜单全屏页、HTML 预览、日志文件页…），不是导航目的地 —— 自己的返回箭头好使，但系统返回会穿透 pop 掉宿主整条路由（表现为「返回跳回主设置」/「HTML 预览退出对话」）。判据：页面用 `MemoTopBar(onBack = { 本地状态 = null })` 就必须配一次 `OverlayBackHandler(onClose)`；Dialog / ModalBottomSheet 形态**不要**配。测试范式 `OverlayBackNavigationTest`（`createAndroidComposeRule<ComponentActivity>` + `onBackPressedDispatcher.onBackPressed()`）。
- **导航回调参数别给 `= {}` 默认值**：`home` 路由漏传 `onOpenWorkspaces` 时，默认空 lambda 把点击静默吞掉（用户点半天没反应）。去掉默认值后漏传直接编译不过。

## 输入栏按钮条件（chat_input_section.dart 规格，2026-09-14 移植完成）
- `supportsReasoning`/`showMcpButton` 门控：非推理模型 Brain 整颗不显示；无工具能力或无启用 MCP 时 Hammer 不显示。判定 `isReasoningModel`/`isToolModel`（override abilities 优先，否则 ModelRegistry 名称推断）在 HomeScreen.kt 纯函数区。
- `_enforceModelCapabilities`：能力不足自动清 thinkingBudget / mcpServerIds（LaunchedEffect）。
- 快捷短语为 0 → Zap 不显示；mcpActive = 选中且已连接；reasoningActive = budget != 0（null/-1 视为开）。
- 供应商详情页工具条外壳 = 内容自适应宽 + 选择模式按预算逐级关标签（删除→全选→检测）；**别 fillMaxWidth 强制满宽**（会撑破胶囊）。
- 测试：`InputBarButtonStateTest` + `InputBarCapabilityGateTest`。

## lint 任务有缓存：改动 app 模块后 lint 必须实跑一次（2026-09-12 血泪）
- `:app:lintDebug` 未改动命中 UP-TO-DATE，历史 lint error 会被缓存藏起来。门禁变红但报错文件没碰过 → 先怀疑缓存刚失效，不是自己改错。
- 现状：`:app:lintDebug` 有 30 warnings + 21 hints（既有），门禁只在 error 时失败。Compose 里读 StateFlow 一律 `collectAsState().value`（要 smart cast 别用 `by` 委托）。

## 远端备份（WebDAV/S3）移植要点（2026-09-12 沉淀）
- SigV4 百分号编码大写十六进制；签名串十六进制小写（方向相反）。签名 Host 省略默认端口；`host`/`content-length` 别手动塞 OkHttp 头但签名里必须有。
- XML 解析测试放 app 模块（Robolectric）——core:data 纯 JUnit 里 XmlPullParser 是 stub。流式上传签 `UNSIGNED-PAYLOAD`；错误文档用 `peekBody`。
- 远端配置键（`webdav_config_v1`/`s3_config_v1`）必须在 `SettingsKeyRegistry` preference 集合里才进备份。

## 主题色「没跟主题走」的排查顺序（2026-09-17 血泪）
- 用户说某处「没跟着主题色」时，**先查遗留的白色 lerp**：`SettingsUi.kt` 的 `SettingsSectionCard` 与 `surfaceCardColorCompat()` 曾是 `lerp(surface, Color.White, 0.96f/0.10f)`（浅色下=死白），影响工具描述卡片、供应商「管理」下卡片、日志 tab 条、11 处输入框底色 —— 现已统一为主题语义卡色 `LocalSemanticColors.surfaceCard`（+ `semantic.hairline`）。**新写卡片容器一律走 `SectionCard` / `SettingsSectionCard` / `semantic.surfaceCard`，别再抄 lerp**。
- 分组标题/分区标题一律 `settingsSectionHeaderColor(scheme)`（= primary）：日志页 `DetailSectionCard`、供应商详情「配置」tab 的「管理」标题已补上；**表单字段标签（`onSurface@80%`）不动**。
- 例外：`ProviderSheets.kt` 的 `Color.White` 是**二维码白底**（扫码需要），别改。

## 助手消息里的媒体（图片/视频）渲染位置（2026-09-17 用户拍板）
- 媒体 part 现在是投影里的 **`AssistantBlock.Media` 块**（连续媒体合并成一块），按 part 顺序渲染 → `[正文][工具卡][图/视频][正文]`；**不再**整条消息的图组挂在气泡上方（那是旧行为，用户「怎么在上面了」）。用户侧附件仍挂气泡上方。
- 生成类工具（`generate_image`/`generate_video`）的产物并进**同一条**助手消息（`generatedMediaParts()` 纯函数 + `allParts += generatedParts`），**不**另开消息；视频与图片同一条路径。

## 可视化工具：一个 `render_visual`，SVG 路线保留（2026-09-18 用户拍板）
- **一个工具、一个 `kind` 枚举**：`bar`/`hbar`/`line`/`area`/`pie`/`donut`/`scatter`/`funnel`/`gauge`/`heatmap` + **`svg`（手写兜底）**。别再拆成两个工具（用户 2026-09-18「为什么不能一个工具渲染各种呀」），也**别再自己动手重构**——「结构化图改原生 Compose Canvas + `ChartPart`」的方案已论证，但用户当日说「现在可以了 那就先用这个吧 不改了」，**搁置**。
- 产物＝`<filesDir>/images/gen_*.svg`（`image/svg+xml`）→ 走已有图片通道：聊天里按 **SVG 自身比例**等比显示（`SvgAspect`）、点开全屏、导出、备份白拿。**不新增 part 类型**是要点。
- 两个必须记住的坑（详见 PORTING §5.24）：① 消毒重建文档时**自闭合标签别写重复**（XmlPullParser 会给空元素补 END_TAG）；② **AndroidSVG 1.4 不认 `orient="auto-start-reverse"`** → 解析抛异常 → 空白卡片。落盘前已用 AndroidSVG 真解析校验（失败回 tool_error 让模型改）。
- **通用教训**：「产物文件看起来没问题」≠「能渲染」——校验要走**渲染器自己的解析器**。排查顺序：先看 `files/images/` 有没有落盘（有 ⇒ 工具跑通、问题在渲染层），再拉文件验标记。
- 开关是**按助手**的（`assistant.localToolIds`，默认空表）；工具改名后旧勾不会迁移，要在助手编辑页重新打开。

## 生成服务（图片/视频，自研功能）的关键口径（2026-09-17 用户实测后定）
- **「测试连接」三态：可达 ≠ 连接失败**。探针只有 `GET {base}/models`，而生成类中转大多没这个接口（404/405）——那种情况必须落 `GenerationTestState.REACHABLE`（列表显示「可达」），**不许**再像老的两态实现那样写成 `lastTestOk=false` 标红「连接失败」（用户 2026-09-17「视频，图片 显示连接失败」的根因）。失败文案必须带**探测的 URL + 状态码 + 响应体截断**；新增服务时测出的结果要跟着保存。
- **➕ 面板的生成入口只做「选模型」**（绑当前助手），真正生成由模型调 `generate_image`/`generate_video` 工具完成。手动生成面板 `GenerationSheet` 已删（用户 2026-09-17 拍板），**勿加回**；选择规则走纯函数 `AssistantGenerationBinding.select` / `Assistant.generationBinding|withGenerationBinding`（+ 面板与助手编辑页共用，别在两处各写一遍）。
- **`:app:testReleaseUnitTest` 已关闭**（`app/build.gradle.kts` 的 `androidComponents`）：Compose UI 测试要的 `ui-test-manifest` 只能挂 debug，release 变体 65 例必红、是假警报。门禁口径 = `:app:testDebugUnitTest`（debug 全绿）+ lint 0 error + assembleDebug；`./gradlew test` 现在全绿。

## 设备验证纪律：不许猜坐标点击（2026-09-10 事故定规）
- 先 `adb shell uiautomator dump` 取 bounds 再点，或只装机让用户实测（用户 2026-09-12 明确「你装机就行 我来实测」）。装机后 `dumpsys package com.psyche.memo.dev | grep versionCode` 记版本。

## 工程纪律（用户长期要求，最高优先级）
- 所有改动都要有测试覆盖；全绿、0 warning 0 info 才提交；遗留问题当场解决。
- 绝不擅自改用户没提/没确认的逻辑；方案先讨论 → 用户点头 → 再动手。
- 同文件多 Edit 必须串行。
- PORTING.md 追加条目先查当前最大号 + 查重（曾撞号）。
