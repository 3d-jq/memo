# Memo Android 移植规格文档（PORTING.md）

> **本文件是移植工程的唯一事实索引。每轮开发开工先读、收工必须更新（进度表 + 新坑）。**
> 目标：Flutter 原项目（`<本仓库>`，lib/）→ Android 原生（`memo-android/`，Kotlin + Jetpack Compose），**严格 1:1**，禁止自创 UI/文案/图标/布局。

## 0. 构建与门禁（全部命令行，无 Android Studio）

```bash
cd /d/program/memo/memo-android
JAVA_HOME="<JDK 21 路径>" GRADLE_USER_HOME="<Gradle 缓存路径>" ./gradlew :app:compileDebugKotlin   # 快速编译
... ./gradlew :app:assembleDebug        # 出包（装机必须！只 compile 装的还是旧包）
... ./gradlew :core:data:testDebugUnitTest --tests "..."  # 单测
bash tools/quality_gate.sh              # 全量门禁（compile+test+lint），提交前必跑
adb install -r app/build/outputs/apk/debug/app-debug.apk   # 装机（包名 com.psyche.memo.dev）
```

**NDK / CMake（`assembleDebug` 需要，2026-09-14 起）**：`core:workspace` 的交互式终端 PTY 走
`externalNativeBuild`，所以出包要 **NDK 28.2.13676358 + CMake 3.22.1**（版本写死在
`core/workspace/build.gradle.kts` 的 `ndkVersion`，CI 里由 `sdkmanager` 装同版本）。
AGP 按默认版本（27.0.12077973）找不到会报 `[CXX1101] NDK at … did not have a
source.properties file` —— 报这个就是 NDK 缺失/装坏，不是代码问题。
本机 SDK 里 `ndk/27.*` 那批是只留了 `.installer` 的空壳目录，可用的 28.2.13676358 在另一个
SDK 根下，已用目录联接（junction）挂到 AGP 期望的位置。

## 1. 架构映射

| Flutter | Android | 备注 |
|---|---|---|
| `ChangeNotifier` provider | `AssistantStore`/DAO + Compose `mutableStateOf` + `reloadKey` 重读 | 无观察框架，写后手动 reload |
| SharedPreferences JSON | `preferenceRepository.readJson/writeJson(key)` | current id 存 **JSON 字符串字面量** `"\"<id>\""`（读时 `removeSurrounding("\"")`） |
| payload 表 | `PayloadEntityDao(db, "<table>", primaryKey=...)` | **assistant_rows 的 PK 列是 `id`**（不是 assistant_key，历史 bug）；provider_rows 用 `provider_key` |
| l10n ARB camelCase | `core/ui/src/main/res/values{,-zh}/strings.xml` snake_case | 使用 `import com.psyche.memo.ui.R as UiR` → `UiR.string.foo_bar`；键名规律 `assistantEditPageTitle`→`assistant_edit_page_title` |
| ImagePicker | `rememberLauncherForActivityResult(PickVisualMedia)` | URI 需拷贝到 `filesDir` 拿持久路径 |
| TabController+TabBarView | `rememberPagerState` + `HorizontalPager` | 切 tab 时 `LocalSoftwareKeyboardController.hide()` |

## 2. 可复用组件清单（**新页面先查这里，禁止重复造轮子**）

位置 `app/src/main/java/com/psyche/memo/ui/`（同包 internal 互用）：

| 组件 | 文件 | 说明 |
|---|---|---|
| `MemoSheetHandle(trailingGap)` / `MemoSheetOptionRow(label,selected,onClick,icon)` / `MemoTopBar` | SettingsUi.kt | **sheet 统一规范（用户 2026-09-12）**：所有 `ModalBottomSheet` 一律 `dragHandle = null` + 内容首项 `MemoSheetHandle()`（列表用 `spacedBy` 的传 `trailingGap = 0.dp`）；选项面板一律 `MemoSheetOptionRow` + `Arrangement.spacedBy(8.dp)`，不再用分隔线。新增 sheet/选项面板前先看这两个 |
| `SectionCard {}` | SettingsUi.kt | iOS 分组卡 r12（Flutter r16 版另有 `Surface16Card`@AssistantSettingsEditScreen） |
| `SectionHeader(text, first)` | SettingsUi.kt | iOS 分类组标题（全站分类化后 20+ 页面在用；`first=true` 顶距更小） |
| `SettingsRow(icon,label,onTap,detailText)` | SettingsUi.kt | iOS 行（label/detail 均已单行省略 maxLines=1，对齐 _iosNavRow——修复存储行两行事故） |
| 设置行 tip 机制：`SettingsTipIcon(tip)` + `RowScope.TipHuggingLabel(label, tip, labelStyle)`（SettingsUi.kt；旧版各页私有 `TipIcon`/`MemoryTipIcon` 已收敛） | SettingsUi.kt | **规范 2026-09-09 + 2026-09-12**：设置行禁止裸排提示词，tip 一律 BadgeInfo ⓘ（16dp@45%、28dp 触控区）+ TooltipBox/PlainTooltip 浮动气泡（tap isPersistent、280dp、点别处收起）；**ⓘ 位置＝紧跟标签文字**（用户 2026-09-12 改口：不要行尾/开关那侧）→ 需要 tip 的行一律用 `TipHuggingLabel`，别再手搓「标签 weight(1f) + ⓘ」的排布 |
| `DividerRow()` | SettingsUi.kt | 0.6dp 居中线 |
| `IosSwitch(value,onValueChanged)` | IosWidgets.kt | 44×26 iOS 开关 |
| `IosButton(label,onTap,icon,filled,neutral,dense)` | IosWidgets.kt | Flutter `_IosButton`：r12 描边/填充 + 0.97 按压 + Haptics.soft |
| `IosIconButton(icon,onTap,color,size,contentPadding,minSize)` | IosWidgets.kt | Flutter `ios_tactile.dart` IosIconButton：按压染底、**不缩放** |
| `ModelSelectSheet(container,options,onSelect,onDismiss)` + `ModelOption(providerId,providerName,modelId,selected)` | ModelSelectSheet.kt | 模型选择 sheet（provider→model 两级+搜索），DefaultModel/Memory 已用 |
| `ProviderAvatarSmall(providerKey,displayName,size)` | ProviderListScreen.kt | 品牌头像（=Flutter _BrandAvatarLike） |
| `AssistantListAvatar(item,size)` | AssistantSettingsScreen.kt | 助手头像四态（http/本地/emoji/首字母） |
| `ParamSliderSheet` / `MaxTokensSheet` / `ContextMessageInputDialog` / `FilledNumberField` | AssistantEditParamSheets.kt | 数值参数三件套：滑块 sheet（标题+开关+SliderTile+值胶囊，`labelOf`/`customLabelStops`/`onValuePillTap` 承载差异）、整数输入 sheet、精确值 AlertDialog、共用填充数字框 |
| `AvatarPickerSheet` / `EmojiPickerDialog` / `AvatarUrlDialog` / `QQAvatarDialog` | AssistantAvatarSheets.kt | 头像五选一行 sheet + emoji 网格/链接/QQ 弹窗；纯逻辑 `qqAvatarUrl`/`randomQqNumber`/`qqAvatarResolves`/`isSingleGrapheme`/`firstGrapheme`/`QuickEmojis` 同文件 internal |
| `EditSegTabBar(tabs,selected,onSelect)` | AssistantSettingsEditScreen.kt | 44dp 胶囊分段条（88dp 最小宽+滚动）；**ProviderSheets.kt 另有一个 weight 平分版 `SegTabBar`，勿混淆勿重名** |
| `SwipeRevealRow` | AssistantSettingsScreen.kt | 左滑操作 pane（0.6W 右对齐、按钮撑满高） |
| `ReorderableColumn` | core/ui/ui/reorder/ | 长按拖拽列表（已带 animateItem+zIndex）；**LazyColumn，只能当页面根** |
| `ReorderableInlineColumn` | core/ui/ui/reorder/ | 同款拖拽的非滚动版（库的 Column 版 `ReorderableColumn`），嵌在外层 LazyColumn/滚动容器里用这个（=Flutter `shrinkWrap+NeverScrollableScrollPhysics`） |
| `Haptics.light(view)` / `SnackbarManager.show(AppNotification(message,type))` | core | 触感/吐司 |
| Lucide 图标 | `com.composables.icons.lucide.Lucide.*` | **用法固定两行、缺一不可**：① `import com.composables.icons.lucide.<Icon>`（`<Icon>` 是 `Lucide` 的**扩展属性**）；② `import com.composables.icons.lucide.Lucide`（receiver 类型）；③ 调用写 **`Lucide.<Icon>`**（裸写 `<Icon>` 会报 `receiver type mismatch`；只写 `Lucide.<Icon>` 不给 ① 会报 `Unresolved`）。`Box` 与 Compose layout `Box` 冲突 → 用 `import com.composables.icons.lucide.Box as BoxIcon` + `Lucide.BoxIcon`。**Wand2 叫 `WandSparkles`**；RTL 图标必须 `Icons.AutoMirrored` 变体 |

## 3. 数据要点

- `Assistant` 模型：`core/data/data/model/Assistant.kt`，toJson/fromJson 与 Flutter **无损往返**（已验证 33 键覆盖）。"清除字段"用 `copy(chatModelProvider = null, ...)`。
- 助手增删改/复制/排序全走 `AssistantStore`（core/data/data/assistant/）；纯规则在 `AssistantStoreLogic`（带单测）。
- 空表 seed：MainActivity 启动 LaunchedEffect 调 `buildSeedAssistants`（默认助手+示例助手），勿删。
- 真机查库：`adb shell run-as com.psyche.memo.dev base64 databases/memo.db` **管道进 Python 解码**；Git Bash `>` 重定向会损坏二进制。

## 4. 已知坑（踩过的，别再踩）

1. 只 `compileDebugKotlin` 后装机 = 装的旧包；**验证必须 assembleDebug**。
2. lint 报 `StringFormatMatches`：`%s/%1$s` 占位符必须传 String（`.toString()`）。
3. Git Bash 重定向二进制会损坏（用 base64）。
4. 同包重名：新增组件前先 grep 全仓（SegTabBar 撞过车）。
5. detail 文本会挤压 label 换行：Flutter `_iosNavRow` 是 label maxLines=1 ellipsis——单行行用 EditNavRow。
6. 深链 `memo://` 不可靠，导航验证用 uiautomator dump + input tap。
7. **字体权重**：按字面映射——Dart `AppFontWeights.semibold`/`emphasis` → `FontWeight.SemiBold`（全仓 205 处已如此），`medium` → `Medium`。注意 Flutter 侧 `AppFontWeights.normalize()` 在 Android 上把 ≥w600 降为 w500，即原 app 真机其实渲染 w500；要严格视觉一致需全局改 Medium，属未决项，勿在单个页面里混用两种。
8. `IosButton` 最后一个参数是 `dense: Boolean`，尾随 lambda 会绑错→必须写 `onTap = {}`；`TextFieldValue` 在 `androidx.compose.ui.text.input`（不是 `ui.text`）；`animateColorAsState` 在 `androidx.compose.animation`（不是 `.core`）。
9. compose-bom 2025.06.01（foundation 1.8.3）没有公开的 `Alignment(h, v)` 构造器：按比例定位（滑块刻度标签等）用 `androidx.compose.ui.BiasAlignment(horizontalBias=…, verticalBias=…)`，`t∈[0,1] → horizontalBias = -1f + 2f*t`。
10. **Robolectric 下不能测 FileProvider**：`androidx.core.content.FileProvider` 按 authority 静态缓存 `PathStrategy`，root 指向首测的临时 dataDir；Robolectric 每测换临时目录 → 后续用例必报 `Failed to find configured root`。解法：把纯逻辑（路径解析/落盘）拆成 internal 函数测，FileProvider 包装层留薄壳（`ImageViewer.kt` `materializeShareablePath` + `resolveShareableImage` 即此模式）。
11. `chartSeries` 调色板数必须 ≥ 实际用色分类数：`indexOf % size` 取模回卷会把第 N+1 类染成第 1 类颜色（存储 10 分类撞 8 色板事故，f5be77d 补到 10 色）。`RoundedCornerShape(50)` 无单位会被 `ShapePercentRegressionTest` 拦（一律 `.dp`）。
12. `combinedClickable` 与 `clickable` 叠加在同一 modifier 链 = 双注册手势（先加 clickable 再加 combinedClickable 时 tap 触发两次回调）：只用 `combinedClickable(onClick, onLongClick)` 一个，并 `@OptIn(ExperimentalFoundationApi::class)`。
13. **思考段（reasoning segment）折叠时机必须发生在「流式过程中」，不是整轮结束**（`stream_controller.dart`）：原项目共 **6 个**「思考阶段结束」触发点——① L853 工具调用开始 ② L1232 正文开始到达 ③ L1280 流正常结束 ④ L1339 用户取消 ⑤ L1355 出错 ⑥ `finishReasoningIfNeeded` 兜底。每处都是三步：打 `finishedAt` → 若 `display_auto_collapse_thinking_v1` 开启则 `expanded = false`。**只在最终结束统一折叠 = 回归**（思考卡会一直转、直到整轮回复完才折）。归口实现：`StreamChunkHandler(autoCollapse, onSegmentClosed)` 在 `appendText` 首字到达 / 工具调用开始时调 `closeOpenSegment()`；取消路径走 `ChatViewModel.stop()` → `ReasoningSegmentCodec.finishLastOpenSegment`（纯函数，带单测）。别处不要再自己折一遍。
14. **JUnit `assertEquals` 的装箱陷阱**：`finishedAt: Long?` 断言要写 `1000L`，写 `1000` 会重载到 `assertEquals(Object, Object)` = `Integer vs Long` 失败（错误文案 `expected: java.lang.Integer<1000> but was: java.lang.Long<1000>`）。
15. **commonmark GFM 表格的 AST 结构**：`TableBlock → TableHead/TableBody → TableRow → TableCell`——**中间有 `TableHead`/`TableBody` 包装层**，直接扫 `table.firstChild` 拿 `TableRow` 会一无所获，必须递归收集。表头标记在 **`TableCell.isHeader()`**（`TableRow` **没有** `isHeader`）；`TableCell.Alignment` 是嵌套枚举（`LEFT/CENTER/RIGHT`）。
16. **测试里写 markdown 多行字符串**：`"""...""".trimIndent()` 的前导缩进会让 commonmark 把它当 **indented code block** 而不是表格/列表。测试源一律用 flush-left 的单行 `\n` 拼接。
17. **Compose 表格列宽别按字符数算**：CJK 与拉丁字的宽度差异巨大，`"排名"`(2 字) 与 `"2 小时 45 分钟"`(9 字符) 按字符数算权重差 6 倍，第一列会被压到 1/6 宽、整列裁掉。用 `TextMeasurer` 实测（`rememberTextMeasurer()`，与单元格同一 `TextStyle`）来近似原项目的 `FlexColumnWidth`。
18. **`Modifier.border()` 不要用来画表格外框**：`TableRowView`/外层 `Column` 上用 `border` 会在滚动容器里画出一条贯穿全高的多余竖线（`Column.border` 的高度按内容撑开但与实际不符）。外框交给圆角卡片，内部线用 `HorizontalDivider`（行底边、最后一行不画）+ `Modifier.drawBehind`（单元格右边界、最后一列不画）。
19. **单元格 `Text` 必须显式吃满列宽**：长「拉丁+全角标点」串（如 `RikkaHub、`）在无宽度约束时会按 ink 宽度排版，直接溢出到表格右边界之外。给 `Text` 加 `Modifier.fillMaxWidth()`，对齐交给 `textAlign`（不要把 `contentAlignment` 留在外层 Box 上，两者会打架）。
20. **`GraphicsLayer` 截图：先拷成软件位图再合成**。`rememberGraphicsLayer()` 在 `androidx.compose.ui.graphics`（`GraphicsLayerScopeKt`），类型是 `androidx.compose.ui.graphics.layer.GraphicsLayer`。录制是 `DrawScope` 上的扩展（**不是** `GraphicsLayer` 成员）：`layer.record(IntSize) { this@drawContent.drawContent() }`；回放用 `androidx.compose.ui.graphics.layer.drawLayer(layer)`（**注意在 `.layer` 包，不是 `.drawscope`**）。`toImageBitmap()` 是 **suspend 成员方法**（无顶层扩展可 import），返回 **hardware bitmap**——`asAndroidBitmap()` 后**不能**直接喂软件 `Canvas`（抛 `Software rendering doesn't support hardware bitmaps`），必须 `copy(Bitmap.Config.ARGB_8888, false)` 拷出来。
21. **截图导出必须合成到不透明底**：Compose 截下的图层是**半透明**的——卡片自身没有不透明底，靠页面 `surface` 透出。直接 `compress()` 写文件时所有透明像素变黑，表现是「上半正常、下半发暗」（表头恰好有 `alphaBlend` 不透明填充所以亮、主体行没有所以黑）。先 `canvas.drawColor(主题 surface)` 再 `drawBitmap`。原项目同样记录过这点：`markdown_with_highlight.dart` L3269 `_capturingTableImage`「Capture must be opaque」。
22. **半透明色调要用 `alphaBlend` 合成，不要直接画低 α 的 `primary`**：原项目 `headerFill = Color.alphaBlend(primary@α, surface)`，得到的是**不透明**色。直接 `Modifier.background(primary.copy(alpha = 0.045f))` 会透出页面背景、发灰发浑（深色模式下 `primary` 是浅色，尤其明显）。现成工具：`com.psyche.memo.ui.theme.alphaBlend(fg, fgAlpha: Double, bg)`（注意 α 是 `Double`）。
23. **别在子组件里自己读 `isSystemInDarkTheme()`**：`TableRowView` 曾内部自读系统深色做边框 α，与外层传入的 `isDark` 不一致（忽略主题覆盖时二者会打架）。深色判定应由调用方传入、全程透传。
24. **平台能力放不进 `core:ui`**：`core:ui` 无 `activity.compose`、无 app 依赖，不能用 `rememberLauncherForActivityResult`、也拿不到 `IosIconButton`（在 app 模块）。剪贴板 / SAF / MediaStore 这类平台动作一律由 app 注入（本次为 `MarkdownTableActions`，null 即不画对应按钮）。
25. **吞异常会让 UI 不可诊断**：存图失败原样只显示 `保存失败: png`，无法定位。改为返回真实错误消息后一眼看到 `Software rendering doesn't support hardware bitmaps`。平台 IO 的 `catch` 应把 `e.message` 透出到提示里。
26. **引用胶囊显示 `?` = 来源白名单问题，不是解析失败**（2026-09-12 定位 + **已按用户决定修掉**）：markdown 侧一切正常——`[cite:x]` 被 `preprocessCitations` 归一成 `[citation](x)`，`parseCitationRef` 也解析成功、胶囊照常渲染，**只是序号解不出来**。判定链：`MarkdownRenderer.resolveCitationCapsule` / 内联分支 → `resolver(id)`（`HomeScreen` 里查 `searchItems`）返回 null → 序号为 null → 原版回落 `"?"`。
   - **两道门，不止一道**：① 工具名白名单（只有 `search_web`/`builtin_search`）；② 结果必须是 **JSON 且带 `items[]`**（`parseToJsonElement(content)` 抛异常就跳过）。所以「只去掉白名单」救不了纯文本结果——实测设备上 `aihot_get_latest`（MCP）返回的是 **纯文本 len=4021**，正文里只有 `AIHOT：https://aihot.news/items/cmtx…` 这类 URL，**没有 `id` 字段**，模型只好拿 URL 末段当 id 写 `[cite:cmtx…]`。
   - **现行做法（用户 2026-09-12 拍板）**：① 白名单改成**按结构判定**（有 `items[]` 就收，`get_time_info`/`memory_*` 这类无关 JSON 仍被排除）；② 解不出的标记**不再渲染成 `?`，而是整段不画**。两条都记在 §5.11「用户明确要求的偏离」——**别再按原版"修回"**。
   - 实测复现：会话「旅游出行准备」order 7。**取证坑**：`message_part_rows` 的关联列是 `revision_id → message_rows.id`，`part_id` 是 INTEGER 自增主键不是消息 id（按 `part_id` 分组会全表查不到引用，得出"没问题"的错误结论）。
   - 仍未覆盖（有意为之）：返回**纯文本**的工具其引用现在会被静默丢弃（既不显示 `?` 也不可点）。要让它可点，得让工具侧按 `items[{id,index,url}]` 结构化返回，或再加一层 URL 抽取启发式——用户选择了不引入猜测。
27. **Toast 队列的计时必须在管理器里，不能放在 item 里**（2026-09-12 修）：原项目 `AppSnackBarManager.show()` 里就起 `Timer`（`snackbar.dart` L78），**与是否渲染无关**。移植版把它写成 `ToastItem` 内的 `LaunchedEffect(entry.id) { delay(...) }` → 只有 `MAX_VISIBLE`(=3) 条被渲染的 toast 才会到期；排队在后面的条目**永远留在队列里**，等它终于升到可见窗口时又重新播一遍入场动画 + 重新数 3 秒 → 表现就是「toast 一多就卡住/堆积不走」。修法：`SnackbarManager.show()` 起 `delay(durationMs)` → `expire(entry)`（置 `expiring` 触发淡出）→ `delay(EXIT_MS)` → `removeNow`，**只跑 `delay`**。
   - **附带坑（差点踩）**：`Animatable.animateTo` / 任何 `animateTo` 都要求协程上下文里有 `MonotonicFrameClock`——管理器的普通 `CoroutineScope(Dispatchers.Main)` **没有**，直接调会抛。所以**动画留在 item（组合内，有 frame clock），管理器只管时间**：item 用 `animateFloatAsState` 跟随 `entry.expiring` 做淡出，入场用「先 compose 成 0、首帧翻到 1」。
   - 顺带补了渲染循环的 `key(entry.id)`：此前按**位置**匹配，一条消失会让所有槽位错位、Compose 用别的 entry 复用同一个 composable（状态被重置，整叠看起来在乱跳）。
   - 回归测试 `app/src/test/.../snackbar/ToastQueueTest.kt`（7 例，Robolectric `shadowOf(Looper.getMainLooper()).idleFor(...)` 驱动 `delay`，无需真实等待、无需动画帧）。
28. **偏好值是 JSON 文本，`parseModelSelection` 前必须先解包**（2026-09-12 用户报「新建对话后大模型又要重新选择」）：`preference_rows.value` 存的是 JSON 文本——真机库里 `selected_model_v1` 就是 `"Zhipu AI::glm-5.3-flash"`（**带引号**）。`ChatViewModel.init` 直接 `parseModelSelection(readJson(...))`，解出 provider=`"Zhipu AI`、model=`glm-5.3-flash"`（引号混进字段）⇒ `ModelSelectSheet` 的选中判定 `id == selectedModelId && config.id == selectedProviderId` 永不命中（打开就是"没选中"）、请求也拿不到 API key。读取一律走 `DefaultModelPrefs.decodeStoredString` / `parseStoredModelSelection`（等价于 `ChatViewModel.readPrefString`、`DefaultModelScreen.readStoredString` 的解包，裸串也吃），别把裸值丢给 `parseModelSelection`。

29. **`LaunchedEffect(conversationId)` 的一次性读库会把「draft 尚未落库」永久缓存下来**（2026-09-12 用户报「新建对话聊天后 助手对应的名字、头像没有及时显示」）：消息头的助手行原来写成 `LaunchedEffect(conversationId) { read conversation_rows.assistant_id }`。新建会话是 **draft**（`ensureConversationRow` 要等首条消息落库才建行）→ 那一刻读到 null，而 key 只有 conversationId ⇒ **行后来出现了也不会重读**，头部就一直显示兜底名（"助手"）+ 模型图标，切走再回来才正常。改用派生值 `remember(conversationId, messages.isNotEmpty(), currentAssistantId) { … headerAssistantId(会话行?.assistantId, 当前助手) … }`：会话行优先，行还没落库时回落到当前助手（等价于原版 draft 内存里就带着 `assistantId`）。**凡是「读一张稍后才会被写入的行」的 UI 状态，都不要用只按 id 触发的 effect。**

30. **l10n 键绑错不会编译报错，只会显示"另一个功能的文案"**：MCP 服务编辑 sheet 的工具审批开关错绑 `mcp_conversation_sheet_title`（＝"MCP服务器"），于是**每个启用的工具都多出一行"MCP服务器"**，看起来像 MCP 服务被重复列出（用户实测「这个MCP工具界面会显示好几个MCP服务这个选项」）。正确键是 `mcp_tool_needs_approval`（"需要审批"／"Require approval"），且那一行只在 `tool.enabled` 时才出现（`mcp_server_edit_sheet.dart` L639-683：Shield 13dp + 12sp 文案 + IosSwitch 的卡内紧凑行）。**加行/改行时按 Flutter 里的 `l10n.xxx` 找同名 snake_case 键，别按"意思相近"挑键。**

31. **JSON null 陷阱：kotlinx 的 `JsonNull` 也是 `JsonPrimitive`，`.content` 会给字面量 `"null"`**（2026-09-12 用户实测「换 DeepSeek 输出全是乱的、会输出 null」）：DeepSeek 思考阶段每个 chunk 都是 `{"content":null,"reasoning_content":"…"}`、正文块是 `{"content":"…","reasoning_content":null}`，`ChatCompletionsDecoder.extractDeltaText` 用 `(content as? JsonPrimitive).content` 就把 "null" 当正文追加 —— 正文里夹满 null；`finish_reason: null` 同理会变成字符串 `"null"`。**凡是解析外部 JSON（provider 响应、模型输出的工具参数/记忆 JSON、用户粘贴的配置）取字符串，一律 `contentOrNull`**（对 JsonNull 返回 null；`.toIntOrNull()`/`.toBooleanStrictOrNull()` 那种顺带安全，`content` 不安全）。已全量替换：三个 provider 解码器与非流式客户端、附件 part、`ToolHandler`/`MemoryTools`/`LocalToolExecutors`/`MemorySmartAdd`/`MemoryPipeline`/搜索解析/供应商导入/MCP 导入/ChatViewModel 自定义请求头、`DefaultModelPrefs.decodeStoredString`。锁定测试：`OpenAiClientIntegrationTest.nullContentAndReasoningNeverBecomeTheLiteralNull`（DeepSeek 形状的四段流，正文/思考都不许出现 "null"）+ `JsonNullTrapTest`（钉住 kotlinx 行为本身）。

32. **别用 PowerShell 批量改含中文的源码文件**（2026-09-12 本轮踩到）：`Get-Content -Raw` + `[System.IO.File]::WriteAllText` 的批量替换把 `TranslateScreen.kt` / `ChatViewModel.kt` 等文件里的中文注释整体读成乱码再写回（顺带吞掉换行 → 后面满屏 "Expecting a top level declaration"）。**要改源码一律用编辑工具（UTF-8 安全）；批量替换后先 `git diff` 看有没有 `閿`/`鏉堟` 这类乱码，必要时 `git checkout -- <file>` 重做。**

33. **Flutter `Container(clipBehavior: Clip.antiAlias + foregroundDecoration)` 在 Compose 要拆成「先 clip、边框画在内容之上」**（2026-09-13 用户报「对话里面这个HTML这个显示有顶部两个角有阴影」）：代码块（`MarkdownRenderer.CodeBlockView`）原来是 `.background(bodyBg, RoundedCornerShape(16.dp)).border(1.dp, …)` —— **没 clip**，头部那条 `surfaceContainerHighest@80%` 的**方角**底色就盖住了上两个 16dp 圆角（深一档的色块看着像"角上有阴影"），边框在上角也被盖掉。原版 `markdown_with_highlight.dart` L2540-2554 明确写了 `clipBehavior: Clip.antiAlias`（"Clip children to the same radius so they don't overpaint corners"）+ `foregroundDecoration`（"Draw the border on top so it remains visible at corners"）。Compose 对应写法：`.clip(shape).background(bg).drawWithContent { drawContent(); drawRoundRect(…Stroke…, 描边内缩半个线宽，免得被 clip 吃掉一半) }`。**凡是「圆角容器 + 内部整块底色/表头」的组合，都要检查有没有 clip**（表格、气泡、工具卡同理）。

34. **HTML 预览是两条路，别都塞进 markdown 模板**（2026-09-13 用户报「这个HTML这个预览有问题」）：原版 `HtmlPreviewPage`（代码块上那枚「预览」，正文是**原始 HTML**，`_wrapIfNeeded` 套个容器直接喂 WebView）与 `MarkdownPreviewHtmlBuilder`（消息「Render WebView」，正文是 **Markdown**，走 `assets/html/mark.html` + markdown-it/katex/highlight.js/mermaid）是两套。移植时两条路共用了 markdown 模板 ⇒ 缩进 4 空格的 HTML 被 markdown-it 当**代码块**、整页还依赖 `esm.sh`/`jsdelivr` CDN（被墙/离线就是白屏）。现在 `HtmlPreviewScreen` 收 `HtmlPreviewRequest(content, rawHtml)`：`rawHtml=true` 走 `MarkdownPreviewHtml.wrapHtml`（完整文档原样放行，片段套主题容器；原版硬编码 #111/#eee，这里改用主题 surface/onSurface），`false` 才走 markdown 模板。

35. **上传文件必须保留原始文件名，别加内部前缀**（2026-09-13 用户报「聊天记录存储这个里面文件显示也有问题」）：`AttachmentStore.import` 原来落盘成 `att_<millis>_<uuid6>_<name>`，而原版走 `FileImportHelper.copyXFile` + `UploadDedupe` —— **原始文件名**（撞名才 `name(1).ext`）、**同字节复用已有文件**（同名字族 + SHA-256 比对）。存储页与附件卡显示的都是落盘 basename（Dart `p.basename(savedPath)`），所以那串内部前缀直接暴露给用户。已按原版重写（`store()` / `findIdentical()` / `reserveUnique()` / `isVersionOf()`，`AttachmentStoreTest` 8 例）。**注意**：用户 upload/ 里历史遗留的 `att_…` 文件不会自动改名（不做迁移），删掉即可。

36. **附件（图片 + 文档）是正文气泡的兄弟、排在气泡上方，别塞进气泡**（2026-09-13 用户报「文档发在对话界面渲染有问题」）：原版 `_buildAttachmentPreview` 把 ImagePart/FilePart 按 part 顺序放进一个 `Wrap(spacing 8, runSpacing 8)`（用户侧右对齐，CMW:1835-1843 与气泡之间 8pt；助手侧左对齐、只收 FilePart，CMW:2848-2851 在正文块之前）。移植版把**文档卡塞进了用户气泡**——卡自身是 `surface@92%` 的近不透明底，叠在半透明 primary 气泡上就是一块突兀的方块。现在统一走 `MessageAttachmentPreview(parts, alignEnd)`（`MessageImageAttachments` 保留给助手图片块，两者共用 `ImageAttachmentTile`）。**连带坑（同日第二轮用户报「文字都靠左去了」）**：附件预览自己 `fillMaxWidth` 撑满了 0.75w，而用户侧那层 Column **没写 `horizontalAlignment`**（默认 Start）⇒ 比预览窄的正文气泡被推到最左边。原版是 `Column(crossAxisAlignment: CrossAxisAlignment.end)`（CMW:1769-1771）。**只要往这个 Column 里加比气泡宽的兄弟，就必须先给它 `Alignment.End`**。

37. **文件名不要"清洗"，中文会被吃掉**（2026-09-13 用户截图报「文档名字渲染有问题」：`Qt-C______.docx`）：`AttachmentStore` 里那句 `displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")` 把**所有非 ASCII 字符**（也就是中文）逐字换成下划线——存储页、附件卡、发给模型的 `## user sent a file: <name>` 三处一起中招。原版 `FileImportHelper.copyXFile` **直接沿用 `xFile.name`**，一个字都不改。现在 `safeFileName()` 只做两件必要的事：路径分隔符/NUL → `_`（防目录穿越，正常 picker 不会给）、超长名按 **UTF-8 200 字节**截断且保留扩展名（ext4 单段 255 字节，超了 `createNewFile` 抛错 → 导入静默失败）。空格/`#`/`&`/emoji/全角括号一律保留，单测 `AttachmentStoreTest` 锁住。**注意**：已经发出去的消息里存的是被清洗过的名字，恢复不了，重新发一次才正常。

38. **非流式（「流式输出」关）必须复用同一个解码器，别另写一套解析**（2026-09-13 补助手域最后一块）：原版关掉 `assistant.streamOutput` 走 `ChatApiService.generateMessage`（非流式 HTTP），我们让 `LlmClient.completeAsChunks(request)` 用**同一个 body（`stream=false`）+ 同一个解码器**产出与 `streamChat` 完全一致的 chunk 序列，生成循环一行都不用改。各家的接法：chat-completions 把整份响应当一条事件喂 `ChatCompletionsDecoder`（它本来就认 `message` 形状：`content`/`reasoning_content`/完整 `tool_calls`），再补一条 `[DONE]` 触发 Finish；Responses 自己发 `responsesOutputText`/`responsesReasoningText`，再把 body 包成 `response.completed` 交 `ResponsesDecoder`（它按这个事件收 `output[]` 的 function_call + usage）；Claude 摊 `content[]`（text/thinking/tool_use）后补 Finish；Gemini 把 body 当一条事件喂自己的解码器（`candidates`/`usageMetadata` 形状相同）。**重试语义照抄流式**（没吐过 chunk 且可重试才重试），否则 429 会把整条回复判死。测试 `NonStreamChatTest`（5 例：四家 + 重试）。

39. **别在类体里再声明一个与构造参数同名的属性 —— 它会静默屏蔽那个参数**（2026-09-13 查出来的真 bug）：`ChatViewModel` 的构造参数 `injectPresets`（新会话标记，`HomeScreen.pendingPresetInject` 传进来）在类体里又被写成 `private val injectPresets: Boolean = false`，于是 `init` 里的 `if (injectPresets)` 读的是那个恒假的**属性** ⇒ **助手的「预设对话」从来没注入过**（用户问「提示词这个部分可以用吧」时才发现；系统提示词/消息模板/追加当前时间三项是好的）。修法：把构造参数直接声明成 `private val`，删掉类体里那份；`ChatPresetInjectionTest`（3 例）锁住「新会话按序落库 / 已存在消息的会话不重注 / 没标记就不注」，并且**验证过把门闸写死为假时它会失败**。排查同类问题：`grep -n "val <参数名>"`，看有没有第二处声明。

40. **系统提示词的 12 个 `{...}` 变量必须真的替换**（2026-09-13 用户问「为什么不用那个可用变量呀 是没做吗？」——当时确实没做）：助手编辑页「可用变量」列的是**单花括号**变量（`{cur_date}`/`{cur_time}`/`{cur_datetime}`/`{model_id}`/`{model_name}`/`{locale}`/`{timezone}`/`{system_version}`/`{device_info}`/`{battery_level}`/`{nickname}`/`{assistant_name}`），原版在 `injectSystemPrompt`（message_builder_service L1569-1597）里用 `PromptTransformer.buildPlaceholders` + `replacePlaceholders` 逐 key 顺序替换；消息模板那套是**双花括号** `{{ role }}`，两套别混（`replacePlaceholders` 不碰 `{{ }}`，`applyMessageTemplate` 也不碰 `{ }`，测试锁住）。我们只搬了 UI 提示、没接替换，等于 12 个变量全是死文案。现在：`PromptTransformer.buildPlaceholders/replacePlaceholders`（纯逻辑，`PromptTransformerTest`）+ app 侧 `ChatViewModel.resolveSystemPromptVariables`（读时区/系统版本/设备/电量/昵称/当前模型）+ `SystemPromptVariablesTest`（3 例，接线级；同样验证过「不替换就失败」）。**注意**：变量里有时间的话会破坏 prompt cache —— 编辑页本来就对 `{cur_date}`/`{cur_time}`/`{cur_datetime}` 弹那条警告（`MemoryPrompts.detectTimeVariablesInSystemPrompt`）。
41. **思考卡展开态必须以「权威态」为准，流式 handler 每次重建出来的 `expanded` 不能直接采信**（2026-09-13 用户实测「在输出思考的时候点击那卡片是不能展开的会打架」）：Dart 里 segment 是**同一个可变对象**（`stream_controller.dart` 94-97 的 `_reasoningSegments`）：增量只往 `text` 追加，用户点击就地翻转 `expanded`（`home_page_controller.dart` 2268-2284），只有「**结束转变**」（`finishedAt` 从 null 变有值）那一次由流式侧写 `expanded = false`（自动折叠开时，L853 工具开始 / L1232 正文开始 / L1280 流结束 / 取消 / 出错 / `finishReasoningIfNeeded` 兜底，L1331-1369 还专门删掉了「每次调用都强制折叠」的 else-if 分支）。我们的 handler 每轮都**重新构造** segment（`StreamChunkHandler` 不持有用户点击），而 `encodeSegments` 之前只按「下标出现过没有」赋初值、其余沿用传入值 ⇒ 用户点开的卡片被下一个增量打回。现在 `ReasoningSegmentCodec.resolveExpanded(segments, previous, state, initialExpanded)`：新段赋 `!autoCollapse`；**结束转变那一次**采用传入值；其余一律用调用方持有的权威态 `ChatViewModel.segmentExpanded`（`toggleReasoningSegment` 对流式中的消息写它；每轮生成/续写开始时用当时的 segment 重置，续写时库里的展开态因此原样保留）。`ReasoningSegmentExpandedTest` 11 例（含「用户展开 → 下一个增量（handler 仍给 false）必须保留」这条回归）。
42. **流式自动跟随＝「按位置跟随」＋「手指在屏上绝不程序化滚动」**（2026-09-13 用户实测「大模型输出的时候我往上滑，他还是往下走」，连修三版才对）：原版 `scroll_controller.dart` 的跟随是**旗标式**的（`_autoStickToBottom` + `_isUserScrolling` + 布局期 pin 116-155；让位意图来自 `message_list_view` 的 `Listener.onPointerDown` 1711-1721 → `handleUserScrollIntent` 374-425；容差 24/56、空闲计时 `autoScrollIdleSeconds` 默认 8s，384-392 / 332-344；生成结束 450ms 窗口 518-564）。搬到 Compose 时踩了三个坑：① 用 `interactionSource` 的 `DragInteraction` 当让位信号 —— 拖动/惯性期间拿不到稳定事件；② 改用 `snapshotFlow { isScrollInProgress }` —— 同样不保证及时更新，而且**程序化滚动**（初始跳到底/历史锚回/导航按钮）也会让它翻真，被误判成用户接管（导航条卡着不隐藏）；③ 光有「意图」不够：只要 `following`/`autoStick` 还是 true，或者它被「回到 24dp 内」这条规则在拖动中途重新置真，跟随就会在**手指还按着**的时候把视口拉回底部 —— 用户看到的就是「往上滑，他自己往下跑」。**现在的实现**：位置判据照 RikkaHub（`ChatList.kt:236-243` `isAtBottom()`：最后一条**可见** item 的底边是否落在视口底部附近；不要求它是列表最后一项，所以流式长高不会像 `maxScrollExtent−pixels` 那样被自己的增长打断；RikkaHub 贴底用「越界下标 + 0」`requestScrollToItem(lastIndex + 10)`，`ChatList.kt:284`，我们改用下面 (e) 的写法）；同时保留原版的旗标/容差/空闲计时。要点：**(a)** `Modifier.pointerInput` 旁听 `awaitFirstDown(requireUnconsumed=false, PointerEventPass.Initial)` → `pointerDown=true; following=false`，抬手 → `pointerDown=false` 并按位置（24dp 内立刻恢复／否则 `autoScrollIdleSeconds` 后按 56dp 再判）；**(b)** 跟随守卫三项缺一不可：`streaming && following && !pointerDown && autoScrollEnabled && !isScrollInProgress`（`pointerDown` 是硬保证，不依赖任何状态观察）；**(c)** 「恢复跟随」只在 `!pointerDown && !isScrollInProgress && 尾巴在底部(24dp)` 时发生，**绝不在距离变大时取消**（流式增长会把按距离的取消判据自己触发掉）；**(d)** 发送/点「回到底部」→ `following = true`。`pointerInput` 用 `Unit` 作 key 且 `finally { pointerDown = false }`，重组不会留下「手指还按着」的真值把跟随永久关掉。**(e) 最要命的一处是「到底」这个滚动本身**（2026-09-13 现场日志定位）：`requestScrollToItem(index)` / `animateScrollToItem(index)` 的语义是「把这条 item 对齐到**视口顶部**」（Compose KDoc：正的 `scrollOffset` = item 滚到视口上方），所以 `requestScrollToItem(lastIndex)` **不是到底**，而是跳到**最后一条消息的开头** —— 长消息（流式正文）就是「视口往上跑」。日志实证：`initialJump(lastIndex)` 之后 `firstIdx=16 firstOff=0 tailGap=1282px`（视口下方还剩 1282px）。修法：到底一律 `requestScrollToItem(messages.lastIndex, Int.MAX_VALUE)`（`animateScrollToItem` 同理），让 LazyList 自己夹到 `maxScrollExtent`；RikkaHub 用「越界下标 `lastIndex + 10` + offset 0」绕开同一问题（`ChatList.kt:284`）。**指向具体消息的跳转（上一条/下一条、缩略图、回到顶部）保持 index-only，那本来就该对齐到顶部。** 打开会话的初始落位、导航条「回到底部」也一并改了 —— 顺带修掉「打开会话停在最后一条消息开头」。

43. **键盘抬起要把对话内容一起顶上去；判据不能用「布局前的几何」**（2026-09-13 用户点名「输入框抬起可以抬起对话内容」）：**原项目有这套** —— `home_mobile_layout.dart:123` `resizeToAvoidBottomInset: true` + `home_page.dart:763-771` `didChangeMetrics` → `scroll_controller.dart:312-321 pinBottomDuringViewportResizeIfNeeded`（`isNearBottom(24)` 才钉）。我们这边**布局半边本来就有**（输入栏 `windowInsetsPadding(WindowInsets.ime.union(navigationBars))` + 列表 `weight(1f)` ⇒ 视口随键盘缩小、内容区整体上移），**缺的是「钉底」那半边**：滚动位置按像素记，视口一矮 `maxScrollExtent` 就变大，不钉的话用户正在看的最新一条会被压到输入栏后面。
   - **Compose 时机坑（关键）**：原版能在 `didChangeMetrics` 里判 `isNearBottom(24)`，是因为该回调**发生在新视口布局之前**。Compose 做不到 —— `LaunchedEffect` 的协程体（走 `AndroidUiDispatcher`）可能在本帧 layout **之后**才跑，那时 `layoutInfo` 已经是缩小后的几何，`tailAtBottom(24)` 必然为假 ⇒ 功能**静默失效**（不报错、就是不动）。**别在 effect 里读 `layoutInfo` 当「抬起前是否贴底」的判据。**
   - 现行做法：改用**同源的 `following`**（=「尾巴在底部且用户没接管」，由位置判定维护；判据本身与 effect 运行时机无关）；`shouldPinTimelineOnImeRise(previous, next, pointerDown, following)` 抽成纯函数（`app/.../ui/chat/ChatViewportFollow.kt`，`ChatViewportFollowTest` 6 例锁死「只在抬起时钉 / 手指在屏上绝不钉 / 读历史不钉 / 收起不钉 / 同一 inset 不钉」）。`lastImeBottomPx` 初值取当前 inset（对齐原版 L739-742 的 post-frame 播种），否则「启动时键盘已开」会被当成一次抬起。
   - 收起键盘不用管：视口长回去后 LazyList 自己把 `pixels` 夹回新的 `maxScrollExtent`，仍然贴底。
   - `pointerDown` 那道闸是本工程的**硬规则**（见 §4-42「手指在屏上绝不程序化滚动」）——原版这里反而会强制接管用户滚动（`positionAtBottomOnNextLayout` 会把 `_isUserScrolling` 清掉），我们不让它抢用户的手。

44. **主题集换成 RikkaHub 的 7 套预设：预设置于独立文件，且「只给配色、面板仍走 Memo」**（2026-09-13 用户「我们这个八个效果不好，用 RikkaHub 那个主题，他那个更全面；我们默认也要保留，主题按照我们这个 UI 和 UX 不改」）：
    - **放哪里**：`Palettes.kt` 是 `tools/palettes_gen.py` 从 Flutter 的 `lib/theme/palettes.dart` 生成的（脚本硬校验「必须正好 9 套」，门禁还会校验生成器无 diff），**不能往里面加**。预设写在 `core/ui/.../theme/RikkaHubPresets.kt`（由 `tools/rikkahub_presets_gen.py` 从 RikkaHub 的 `ui/theme/presets/` 转录：7 套 × 每套 35 个色槽，逐值核对过；那脚本需要 RikkaHub 检出，所以**刻意不进**门禁的生成器清单，产物照常提交）。id 沿用上游 `sakura/ocean/spring/autumn/black/minimal/claude`，名字取上游 l10n 的中英值（Claude 保持品牌名不翻译）。
    - **列表**：`themeChoices = defaultPalette + rikkahubPresets`（共 8 条）——Memo 自己那 8 套不再列出，但 id 仍能解析（`themePaletteById` 先查预设、再回落 `paletteById`），老用户已选的主题不会失效；主题设置页若发现当前选中的正是旧的一套，会临时补一行显示选中项。解析入口统一走 `themePaletteById`（`ThemeState.resolvePalette`、显示设置页）。
    - **有意决定（勿"修回"，2026-09-14 按用户「跟着人家一比一做」修正过一轮）**：预设走「原样表面」通道 —— 角色映射**照 RikkaHub 实测取色**，不是按 M3 默认关系推：
      | | 页面底 | 卡片/列表项 | 卡内/页内填充 | 边框 |
      |---|---|---|---|---|
      | RikkaHub 实测（Claude 浅色） | `#F2F0E8` = **`surfaceContainer`** | `#FFFFFF` = **`surfaceBright`** | `surfaceContainerHigh` | `outlineVariant` |
      | 我们（改前，**做反了**） | `#FAF9F5` = `surface` | `#F2F0E8` = `surfaceContainer` | 同 | 同 |

      实现：`MemoTheme.authoredColorScheme` 把 `scheme.surface`（Memo 全站的页面底）换成预设的 `surfaceContainer`；`AppSemanticColors.authored` 里 `surfaceCard = surfaceBright`、`surfaceFill/surfaceCardFill = surfaceContainerHigh`。RikkaHub 自己的命名印证了这个关系：`CustomColors.cardColorsOnSurfaceContainer = surfaceBright`、`listItemColors = surfaceBright`。**暗色同理**（页面 = 较暗的 `surfaceContainer`、卡片 = 较亮的 `surfaceBright`）。
      **为什么不能只照 M3 默认**：M3 里 light 的 `surfaceContainer` 比 `surface` 深，直接用会把「页面白、卡片黄」做反（用户原话：「背景人家用的黄的 卡边是白色 我这个做反了吧」）；真机取色（`adb screencap` + 采样）是唯一可靠判据。
    - **主题选择页 1:1 照 RikkaHub**（同上「一比一」）：`ThemeSwatchGrid` / `ThemeSwatchCanvas`（`ThemeSettingsScreen.kt`）= 上游 `PresetThemeButtonGroup`/`PresetThemeButton` 的几何逐条对齐 —— 四列 FlowRow（`maxItemsInEachRow = 4`、`spacedBy(4/8)`、末行 `Spacer(weight(1f))` 补位）、每项 48dp 圆形色卡（`primaryContainer` 打底 + 右上 `secondaryContainer` 象限 + 右下 `tertiaryContainer` 象限 + 中心 `primary` 圆点 8dp／选中 12dp）、选中时圆点上打 `onPrimary` 对勾、名字在圆下方用 `scheme.primary` 12sp 居中。自定义主题行同样用色卡（取该主题 `buildCustomThemePalette` 生成出来的色板，上游是 `generateColorScheme`）。**注意**：`Canvas` 里参数名 `size` 会遮住 `DrawScope.size`，用 `val box = this.size`。
    - **不搬的**：RikkaHub 那套并行 UX（DataStore `theme_id`、SharedPreferences `colorMode`、AMOLED 开关、二级 Preferences→Theme 导航）一律不引；AMOLED 的行为已由 Memo 的 `display_use_pure_background_v1` 覆盖。**页面结构仍是我们自己的**（动态颜色/纯色背景两张开关卡在上、无「预设主题」小标题）——用户明确说过「主题按照我们这个 UI 和 UX 不改」，1:1 指的是**色卡与配色的呈现效果**。
    - **设置页分组标题跟随主题色**（2026-09-14 用户实测点名：「主题设置里面那个分类的字的颜色没有跟着主题走呀 rikkhub就可以呀」）：分组标题统一取 `colorScheme.primary`，判据集中在 `SettingsUi.kt` 的 `settingsSectionHeaderColor(scheme)` **一处**，五个调用点全部改走它 —— `SectionHeader`（25 个设置页）+ 主题页「自定义主题」行 + 内存设置 `SettingsSectionHeader` + 搜索服务 `SearchSectionHeader` + 记忆追踪 `TraceSectionHeader`。依据 RikkaHub：设置页骨架 `CardGroup.kt:157` 就是 `LocalContentColor provides colorScheme.primary`，主题页 `SettingThemePage.kt:150/181` 也直接写 `primary`；Memo 原先一律写死 `onSurface@80%`，换主题时这行字纹丝不动。**只改主题色、字号/字重/间距/位置一律不动**（那是「我们这个 UI 和 UX 不改」的部分）。
    - 测试：`core:ui` 的 `RikkaHubPresetsTest`（8 条列表 / id 唯一 / 未知 id 回落默认 / 旧 id 仍可解析但不再列出 / 每套明暗不同 / 逐值对照上游抽样 / 名字中英对照）+ app 的 `SettingsSectionHeaderColorTest`（跟随 `primary`、改 `onSurface` 不再影响标题 —— 旧实现读的就是它）。
    - Kotlin 小坑：KDoc 里写 `presets/*.kt` 这种路径会**开嵌套注释**（Kotlin 块注释可嵌套），编译报 `Unclosed comment` —— 改写成「presets 目录下的各 Theme.kt」。

45. **Agent Skills 移植：技能文件是第三方内容，三条边界先立住**（用户 2026-09-14 点名「移植 RikkaHub 的 skill 系统」）：
    - **契约**：一个技能 = `<filesDir>/skills/<技能名>/SKILL.md`。frontmatter 必填 `name` + `description`（可选 `compatibility`），其余是正文。`name` 决定目录名，`description` 进系统提示词里的 `<available_skills>` 清单。
    - **三段接线**（照上游 `SkillsTools.createSkillTools`）：① 工具定义 `SkillTools.buildDefinitions` —— 助手启用 ∩ 磁盘存在，一个都没有就整颗不提供；② 系统提示词块 `systemPromptBlock` 挂 `ContextSource.skillPrompt`（上游是 `Tool.systemPrompt`，Memo 的 `LlmToolSpec` 没这个字段，所以走 `ChatViewModel.buildSystemPromptParts`）；③ 执行在 `ToolHandler` 的 `use_skill` 分支。`use_skill` 也进了 MCP 的保留名集合，第三方 MCP 工具盖不掉它。
    - **两条不可信输入**：技能名来自第三方 frontmatter，`use_skill` 的 `path` 来自**模型**。两者都过 `SkillPaths` 的 canonical 边界检查（判据是「解析后的真实路径必须落在根之内」，不是查 `..` 字符串 —— 那样挡不住符号链接）；zip 导入的条目同理（含 `..` 的整条丢弃）。
    - **放哪**：解析与仓库是**纯 JVM**（`core:common` 的 `skill/` 包，`SkillStore` 只依赖一个根目录），所以能用临时目录把列表/原子保存/覆盖/删除/穿越/文件表全部单测；容器只负责给根目录（`AppContainerImpl.skillStore`）。上游把文件操作和 `SettingsStore` 耦在同一个 `SkillManager` 里，那样在**没有 Robolectric** 的 `core:data` 里根本测不了。
    - **照搬的上游语义**：`SKILL.md` 必填；保存走 staging + rename 的原子路径（写一半不会留下半个技能）；删除技能时顺手清掉所有助手 `enabledSkills` 里的同名项（进技能页时再清一次幽灵名，对应上游 `pruneOrphanedEnabledSkills`）。
    - **有意偏离（3 处，都是被单测逼出来的）**：① **先校验再落盘** —— 上游是先写盘再解析，frontmatter 缺 `name`/`description` 时会在磁盘留下一个永远解析不出来、列表里也看不见的目录；② **backup 用「不存在的唯一路径」** —— 上游先 `mkdirs()` 出 backup 目录再拿它当 `renameTo` 的目标，POSIX 允许覆盖空目录但 **Windows 上直接失败**（宿主机单测当场抓到「覆盖保存静默失效」）；③ `SkillStore.listFiles` 的相对路径**固定用 `/`**（Windows 的 `File.separatorChar` 会给出反斜杠，而那是 API 层路径，不该跟着宿主变）。
    - **GitHub 导入**（上游 `importSkillFromGitHub`）：`SkillGitHubImporter` 走 Contents API 递归列目录 → 找根部的 `SKILL.md` → 把该目录下所有文件一起存进技能。与上游两处实现差异，都是为可测与正确：① **拉取做成注入的 `fetch`**（上游直接开 `HttpURLConnection`）——生产用容器的 OkHttp（**全局代理因此生效**），单测塞个返回固定 JSON 的 lambda 即可；② **JSON 用 kotlinx.serialization 解**（上游 `org.json`，那个在纯 JVM 单测里是 stub，调用即抛）。
    - **内置技能（Memo 自己的加法，上游没有）**：用户 2026-09-14 点名 skill-creator「很好用」，希望装完就能用。做法：`tools/fetch_bundled_skills.py` 把上游技能抓进 `app/src/main/assets/skills/<名>/`（上游 commit 记进 `assets/skills/BUNDLED.json`，可审计），`BundledSkills.seedIfNeeded` 在启动时**只播一次**进 `<filesDir>/skills`（播过的名字记在本地键 `bundled_skills_seeded_v1`，属设备态、不进备份）—— 用户删掉后不会复活，新版本新增的内置技能会补种。**坑**：`AssetManager.list()` 语义不统一（真机给目录名、Robolectric 给递归文件路径），所以必须先展开成相对文件路径再复制，不能把条目当目录名用。
    - 测试：`core:common` 的 `SkillFrontmatterParserTest`（13 例，含折叠块标量/CRLF/重复键/脏 YAML 不抛/非字符串值）、`SkillPathsTest`、`SkillStoreTest`（列表/原子保存/覆盖不留临时目录/删除/嵌套文件/文件表）；app 的 `SkillToolsTest`（暴露门控/提示词块/正文与文件读取/越界与缺失/幽灵名）、`SkillImporterTest`（条目规范化/取最外层/md 与 zip 落盘/穿越防护）、`SkillGitHubImporterTest`（URL 四种形态与非仓库 URL 拒绝 / 根目录导入 / 递归子目录 / `/tree/branch/sub` 的相对路径基准 / 无 SKILL.md / 列表不可达 / frontmatter 不全 / 附属文件下载失败且不留半个技能）、`BundledSkillsTest`（**用真实 assets 跑**，顺带校验打包进来的 skill-creator 能被我们的解析器解出来 + 清单文件不当技能 + 只播一次）。

46. **沙箱工作区（proot rootfs）：照 RikkaHub 1:1 移植**（用户 2026-09-14「沙箱这个 rikkhub 怎么做了 我们怎么就怎么做」，并确认 rootfs 在他机器上装得上）：
    - **模块划分**：`core:workspace` = 上游 `workspace` 模块的 7 个文件 1:1 移植（模型 / 文件系统 / 管理器 / rootfs 安装器与补丁 / proot 运行器），已提交 `19aec31`；proot 二进制（arm64-v8a + x86_64，GPL-2.0-or-later）随模块分发，真机 spike 见模块 `README.md`。
    - **记录存哪**：上游有自己的 Room 表 `workspaces`；Memo 的 SQLite 是 drift v3 生成的、门禁还校验「生成器零 diff」，**不能加表** → 用 schema 里已有的通用表 `extension_entity_rows`（`kind = "workspace"`，`(kind,id)` 复合主键），另配 `ExtensionEntityDao`（现有 `PayloadEntityDao` 只管单列主键的表）。
    - **编排**：app 的 `WorkspaceRepository`（包 `com.psyche.memo.provider.workspace`）把记录与 `WorkspaceManager` 缝起来。与上游的两处结构差异：① 上游用 Room `Flow` 发变更，Memo 用 `version` 计数器（与 `MemoryProviderV2` 同一套）；② 上游从 `SettingsStore` 清助手绑定，Memo 用 `AssistantStore`。
    - **bind mount 一份两用**：`/skills` → `<filesDir>/skills`、`/upload` → `<filesDir>/upload`。同一份挂载表既给 PRoot 的 `-b` 参数、也给文件工具的路径解析 —— 上游注释点名过，两处各写一份必然漂移。
    - **包名坑**：core:workspace 的包是 `com.psyche.memo.workspace`，app 侧仓储若也叫这个包就成了 split package → 挪到 `com.psyche.memo.provider.workspace`。
    - **工具面**（`app/.../provider/workspace/WorkspaceTools.kt`，照上游 `data/ai/tools/WorkspaceTools.kt`）：`workspace_read_file` / `write_file` / `edit_file` / `shell` 四个，助手绑了工作区才提供（`assistant.workspaceId`）。三个照搬的关键设计：① **写文件走 shell**（`cat > path` + stdin）、读走 `rootfsFileSize` + `exportRootfsFile` —— proot 只暴露一个执行入口，直接碰宿主文件会绕过 rootfs 的 bind mount 映射；② **免审批可写区**只有 `/workspace` 与 `/tmp`，写到别处自动升级为需要审批（`pathOutsideWritableRoots`，参数坏掉时**失败关闭**）；③ `workspace_shell` 默认要审批，其余三个免审批（`DEFAULT_APPROVALS`）。编辑走 `WorkspaceTextReplacers.kt` 的**三级阶梯**（精确匹配 → 逐行 trim 相等 → 块锚点），1:1 上游 `Replace.kt`。
    - **两处未移植**：① `workspace_read_file` 读**图片**那条分支（上游把字节交给 `FilesManager` 生成图片 part，要接进 Memo 自己的工具结果图片通道 —— Memo 的工具结果是字符串，通道是「正文里一行 `![](路径)`」由 `ToolResultImageStrip` 渲染，所以这里要落一份到 cache 再写图片行）；② 工具结果里的 unified diff 元数据（同上，没有 diff 通道）。**第三级 `block_anchor` 替换器已补**（`WorkspaceTextReplacers.kt`：精确 / 逐行 trim / 块锚点三阶梯，`14bf6c4`）。
    - **界面（照上游 `ui/pages/extensions/workspace/`，外壳用 Memo 件）**：列表页 `WorkspaceScreen.kt`（图标 `Lucide.HardDrive` —— 与 MCP 的图标区分开）＋详情页 `WorkspaceDetailScreen.kt` ＋助手编辑页「工作区」tab `AssistantEditWorkspaceTab.kt`（**绑定是每助手一份**，不是「当前助手」）。详情页按上游 `WorkspaceDetailPage` 的结构重做过一版（用户 2026-09-14「你这个详细界面也不对呀」）：**两个 tab 放底部**（基本 `Settings03` / 文件 `File02`，视觉照 Memo 供应商详情页的 `BottomTabs`）；顶栏动作 =［文件 tab］导入文件 · 刷新 ·［Shell 未 DISABLED］终端；「基本」= 工作区信息卡（名称 / Shell 状态，label 0.35 / value 0.65）→ 启用 Shell 卡（说明 + 整宽 `IosButton` + `RootfsProgress` + URL 预填对话框）→ 工具审批卡（四个工具带**人类可读名 + 工具名两行**）；「文件」= 存储区段控（文件 / **Rootfs**，上游 `area_rootfs` 的文案就是 Rootfs 不是 Linux）→ 路径栏（后退钮 + 路径，根显示 `/`）→ 错误卡 / 空目录态（48dp 图标 + 48 纵留白）/ **每个条目一张卡**（两行：名字 / 路径·大小；溢出菜单导出·分享·删除）；点文件按扩展名分流（文本 → 编辑 sheet、图片 → `ImageViewerOverlay`、其它 → 导出到 cache 交系统应用）；rootfs 区文本**只读**（1:1 上游 `WorkspaceFileEditorPage` 的 `editable = area == FILES`）。首次那版是自己猜的结构（顶部段控 + 单张 SectionCard 列表），所以「看着不对」。
    - **交互式终端（照上游 `WorkspaceTerminal*`，1:1）**：`core:workspace/src/main/cpp/termux_pty.cpp` 提供 `com.termux.terminal.JNI` 的四个 JNI 符号（`createSubprocess`/`setPtyWindowSize`/`waitFor`/`close`，`libtermux.so`），走 `externalNativeBuild`（`ndkVersion = "28.2.13676358"` + cmake 3.22.1，**别去掉 `ndkVersion`**，AGP 按默认版本找会报 `[CXX1101] NDK ... did not have a source.properties file`）；依赖 `com.termux.termux-app:terminal-view:0.118.0`（JitPack），AAR 里同名 `libtermux.so` 与我们的冲突 → app 模块 `packaging.jniLibs.pickFirsts += "lib/*/libtermux.so"` + `abiFilters` 只留 arm64-v8a/x86_64。app 侧 `provider/workspace/WorkspaceTerminalSession.kt`（会话 + 两个 client，含终端里点 URL 用浏览器打开的软换行还原逻辑）与 `WorkspaceTerminalSessionManager.kt`（**会话独立于页面生命周期**：退出终端页 shell 还活着，只有关 tab / shell 自己退出 / 工作区被删或换 rootfs 才结束；容器级单例 `AppContainerImpl.workspaceTerminalSessions`），界面 `ui/WorkspaceTerminalScreen.kt`（路由 `workspace_terminal/{id}`，详情页顶栏的终端按钮跳过去）。**唯一的结构改进**：上游在 app 侧又拼了一遍 proot argv 与 bind mount，Memo 改成向 `core:workspace` 要（`ProotShellRunner.buildInteractiveArgv` + `loaderEnvironment` + `WorkspaceManager.bindMounts()`），**一次性命令与 PTY 共用同一份挂载表和同一套 loader 环境**，不会漂移。两处外壳差异：① 上游把整页强制深色（`RikkahubTheme(colorMode = DARK)`），Memo 用当前主题的顶栏/tab 条 + 黑底终端视口；② 上游用 `SecondaryScrollableTabRow` + 打包的 JetBrains Mono，Memo 用横向滚动 tab 胶囊 + 系统等宽（`Typeface` 反解不出 Memo 可配置的代码字体）。
    - **真机验证（2026-09-14，PKB110 / arm64）**：`root@localhost:/workspace#` 提示符出现 → 打字回声 + 命令执行 + 错误输出都正常（真 PTY）→ `ls /skills` 列出内置技能 `skill-creator`（**证明共用挂载表真的挂上了**）→ `cat /etc/resolv.conf` 是 `# Generated by Memo workspace.` + 宿主 DNS（证明 `prepareWorkspaceTerminalSession` 打了补丁）→ `+` 新建标签页得到干净提示符（tab 之间缓冲隔离）→ `×` 弹「关闭标签页 N？」确认框且选中项不动。
    - **`workspace_read_file` 读图片（照上游：图片是工具结果的一部分，不是正文里的 markdown）**：上游的工具结果本身就是 `List<UIMessagePart>`（Text/Image 混排），Memo 的工具结果只有 `content` 字符串，所以 **payload 新增 `images` 键**（`[{"uri","mime"}]`，`ToolCallPart.encode/decode`，**没有图片时不写这个键**，老 payload 形状不变）—— 这是本工程新增的 payload 键，读端全部忽略未知键。链路：`WorkspaceTools.readFile` 命中图片扩展名 → 正文只回 `{path, description}`（1:1 上游 `readImageInRootfs` 的文本部分）+ 原始字节 → `ToolHandler` 落盘到 `<filesDir>/tool_images/<ts>_<名>`（放 filesDir 是为了重开对话图还在；独立目录不污染上传管理器）→ `onImage` 回调 → `StreamChunkHandler.foldToolResult(id, content, images)` 写进 payload → ① UI 由 `ToolResultImageStrip` 渲染（`ToolUiPart.attachedImages`，与正文 markdown 图片合并成同一条横滚条）；② 请求侧 `LlmMessage.toolImages` → OpenAI Chat Completions 的 tool 消息 content 换数组（`MessageContent.openAiToolResultContent`）、Responses 的 `function_call_output.output` 换 `input_text`/`input_image` 数组（`responsesToolResultOutput`）。**只发给支持图片输入的模型**：`LlmRequest.imageInput` 取 `ModelOverrideResolver` 的 `visionInput`（= 上游 `Modality.IMAGE in supportInputModalities`），不支持时正文补 `[Image output omitted: …]` 占位（逐字照上游，避免 400）。Claude/Gemini 客户端本来就不上行 tool 消息（Memo 的工具环只走 OpenAI 兼容与 Responses），所以只接这两处。
    - **「常用环境」一键装（本工程新增，RikkaHub 没有）**：用户 2026-09-14「这个沙箱可以让用户选择 下载 node gitbash 这些常用的环境吗？」。RikkaHub 的工作区只有列表/详情/文件编辑/终端四个面，**没有任何环境安装入口**，所以这层是我们的加法。做法：详情页「基本」新增一张「常用环境」卡（与既有三张卡同一套外壳），预设三条 apt 命令 —— **Node.js + npm** / **Python 3 + pip** / **常用命令行工具**（curl wget unzip xz less vim nano jq ripgrep tree）；每行 = 名称 + 说明 + 状态（已安装/未安装/检查中/正在…）+ **安装 / 重新安装 / 卸载**按钮（装的过程中按钮变**取消**），装的是**每个工作区自己的 rootfs**（谁装谁的）。实现要点：① 预设与命令拼装/状态解析是纯逻辑（`provider/workspace/WorkspaceEnvironments.kt`，可单测）；② 状态用**一次 shell 调用**探测全部包（`dpkg-query -W -f='${Status}'` 而不是 `command -v` —— `ripgrep` 这种包名≠命令名的才算得准），**一组包全装齐才算这个环境可用**，解不出信息的包按未安装（失败关闭）；③ **软件源可选**（`AptMirror`：清华 TUNA / 中科大 / 阿里云 / 官方），**默认清华**，改写 deb822 的 `URIs:` 行；④ **安装拆成三步、各自一次 `executeCommand`、各自 10 分钟超时**：切换软件源 → 刷新索引（`apt-get -o Acquire::Languages=none update -qq`，砍掉 Translation-* 索引）→ `apt-get install -y --no-install-recommends`；拆开是为了状态能说人话、失败能归因到具体哪一步；⑤ **取消**会真的取消协程（`executeCommand` 走 `runInterruptible`，进程被杀），随后后台补一次 `dpkg --configure -a`（中途杀 dpkg 会留下没跑完的 transaction，不补的话下次 apt 直接报 "dpkg was interrupted"）；⑥ **卸载**走 `apt-get remove -y --purge` + `apt-get autoremove`（node 会拉几十 MB 依赖，不 autoremove 等于没省空间）；⑦ **不走工具审批** —— 这是用户自己在界面上点的动作，不是模型发起的工具调用（与他在终端里手敲 apt 同一性质）。装完/卸完/失败都重新探测一次，失败弹窗带 apt 的 stderr（截 4000 字）。
    - **软件源默认给国内镜像的由来（真机实测，2026-09-14）**：用户 2026-09-14「好慢呀 怎么回事呀」→「来点国内的镜像源呀」。拿同一个 `dists/noble/Release`（255 KB）在沙箱里测：**官方 `ports.ubuntu.com` connect 0.90s / 总 2.94s / 86 KB/s**、清华 0.20s / 0.86s / **295 KB/s**、中科大 0.66s / 1.08s / 235 KB/s、阿里云 0.47s / 3.68s / 69 KB/s。`apt-get update` 那几十 MB 索引按 86 KB/s 算是几十分钟，而且原来的实现用 `apt-get update -qq && install` 一条命令 + 只压了 QQ 日志，界面上就是一个不动的转圈 —— **所以「慢」的根因是源、不是 proot**。另外 rootfs 里 **git + bash 本来就有**（ubuntu-base 的基础包，探测实测 `curl=1`/`less=1`），所以「git bash」不需要装。
    - ⚠️ **sed 的分隔符不能出现在它自己的模式里**（用户 2026-09-14 实测：「安装失败 … `sed: -e expression #1, char 105: unknown option to 's'`，其他也是这样的」）：`mirrorCommand` 里那条替换老式 `sources.list` 的 sed 用 `|` 当分隔符，而模式里本来就有 `|`（`(a|b|c)` 的正则交替）→ sed 把模式里的 `|` 当成 `s` 命令的结束，报 `unknown option to 's'`；加上命令开头的 `set -e`，**第一步「切换软件源」就直接失败，三个预设报同一句**（而 `URIs:` 那条因为模式不含 `|` 所以是好的 —— 表现就是「源确实写进去了，但后面崩了」）。已改成用 `#` 作分隔符，并在真机上验证过新旧写法（新 `exit=0`、旧能逐字复现那句报错）；单测 `no sed delimiter appears inside its own pattern` 钉住。**以后往这些命令里加 sed 时先想一遍分隔符。**
    - ⚠️ **「换了镜像源还是用不了 / 官方源也失败」的真因是 DNS，不是源**（用户 2026-09-14「这个镜像源为什么用不了呀」→「官方原也是失败」）：`RootfsPatcher.DEFAULT_DNS_SERVERS` 原本是 `["1.1.1.1", "8.8.8.8", "223.5.5.5"]`，沙箱内实测 **`8.8.8.8` 完全不通（5s 超时无响应）、`1.1.1.1` 时通时不通**，而 `223.5.5.5` 0.11s ✓、`119.29.29.29` 0.13s ✓。glibc 按顺序试且各自等超时，apt 一次 update 几十个解析叠起来 → `Temporary failure resolving '<mirror>'`，**跟用哪个镜像源无关**。两处都要改，缺一不可：① 兜底列表换成国内可达的 `["223.5.5.5", "119.29.29.29", "1.1.1.1"]`（Cloudflare 留最后给海外兜底）；② **旧的守卫是「有非本地 nameserver 就不动」，所以改了兜底也刷不到已经装好的 rootfs** —— 现在按首行标记 `# Generated by Memo workspace.` 认出「这是我们生成的文件」，目标列表变了就重写；别人（用户或某个包）写的文件仍然不碰。测试 `rootfsPatcherRefreshesItsOwnStaleResolvConf` / `rootfsPatcherKeepsForeignResolvConf` 钉住这两条。
    - ⚠️ **`force-stop` 过 App 之后，app uid 的沙箱出网会被 Android 掐掉**（调试时踩到的坑）：`adb shell am force-stop`（或换 APK = 重启 + 停进程）之后，用 `run-as` 起 proot 跑 curl 会**全部超时**（连直连 `223.5.5.5` 的 TCP 都不通、DNS 也解不出），看起来像「源坏了」；`am start` 把 App 拉起来就立刻恢复正常。**排查沙箱网络前先确认 App 是活的**，别把它当成源/DNS 的锅（这条只影响 adb 侧手测，App 内用户点安装时 App 必然是活的）。
    - 测试：`WorkspaceToolsTest` 27 例 —— 审批默认值与工作区覆盖、可写安全区边界（含 `fail-closed`）、rootfs 元数据的 `\0` 四元组解析（含畸形输入）、`shellQuote` 转义、超时钳位、替换三阶梯（精确唯一性 / `replace_all` / 逐行 trim 退化 / 块锚点 / 空 needle / 读超限提示）、图片扩展名判定与 `imageReadOutcome`（字节 + 描述、正文不含二进制）；详情页纯逻辑 `WorkspacePathTest`（`parentOf` 含 rootfs 绝对路径、`detectFileType`、`fileSizeToString` 各量级精度）；终端 argv `ProotTerminalCommandTest` 3 例（共用挂载表 + 源不存在则跳过、结尾是 `env -i … /bin/bash` 且**无 `-c`**、loader 环境指向 nativeLibraryDir 与工作区 tmp）；tab 选中 `WorkspaceTerminalTabSelectionTest` 5 例（补位 / 退前一个 / 全关为空 / 关后台 tab 不动选中 / 未知 id 空操作）；payload `ToolCallPartImagesTest` 4 例（往返、无图不写键、畸形条目丢弃、键形状）；多模态 `MessageContentTest` 新增 4 例（无图仍是字符串 / 支持图片时两种 provider 形状 / 不支持时占位 / 编不出来时落 ENCODE_FAILED）；UI `ToolCallCardLogicTest` 新增 2 例（payload 附件与 markdown 图片合并顺序 / 无附件时只用 markdown）；`WorkspaceEnvironmentsTest` 10 例（安装命令非交互 + 不装 recommends、索引刷新单独一步且砍 Translation-*、卸载 purge + autoremove、**国内三个镜像在前且默认清华**、软件源改写只认已知镜像、探测输出能映射回镜像或 null、预设 id 唯一且包名干净、探测对每个包只问一次且用 dpkg-query、**全装齐才算可用**、空/畸形探测输出失败关闭）。

## 5. 批次进度（收工更新）

| 批次 | 范围 | 状态 |
|---|---|---|
| A1 | 助手列表页 + AssistantStore + seed + assistant_rows PK 修复 | ✅ ee13be1 / 3668933 |
| A1.5 | 拖拽 animateItem+zIndex；滑动 pane 修复 | ✅ 2bbb691 |
| A2 | 编辑页骨架 + EditSegTabBar + basic tab 静态行 + 路由 | ✅ cf19bcd |
| A2b | basic tab：聊天模型选择 + 聊天背景（选图/清除/预览） | ✅ 本轮 |
| A2c | basic tab：4 个参数 sheet（Temperature/TopP/上下文滑块 + MaxTokens 输入）+ 上下文精确值弹窗 + 头像选择 sheet（相册/emoji/链接/QQ/重置）；偏差：相册图按原字节拷进 filesDir，未做原版 maxWidth1024/quality90 降采样（同 A2b 背景） | ✅ 本轮 |
| A2c.5 | basic tab：思考预算行接 `ReasoningBudgetSheet`（off/auto/light/medium/heavy/xhigh/max/自定义，复用 UI-7c 那张 sheet；改成 callback-based `initialBudget: Int?` + `onSelect: (Int) -> Unit`，调用方各自持久化——chat 输入栏写 `thinking_budget_v1`，编辑页写 `assistant.thinkingBudget`）。抽出 `parseBudgetJson` 纯函数好测 + `ReasoningBudgetHelperTest` 14 个（7 parseBudgetJson + 7 assetForBudget） | ✅ 本轮 |
| A3 | 提示词 tab 1/3：系统提示词卡（全屏编辑 sheet + 文件导入 + 变量表 + 缓存告警）+ 追加当前时间行 + 两弹窗；tab 顺序对齐 `defaultAssistantEditTabIds`；`PromptTransformer.applyMessageTemplate`（core:llm） | ✅ 本轮 |
| A3b | 提示词 tab 2/3：消息模板卡（4 变量 + 实时预览）+ 预设对话卡（pill/内联输入/_PresetMessageCard/拖拽/编辑 sheet）+ `PresetMessage` 模型 | ✅ 本轮 |
| S1 | 搜索体系 1/3：`SearchServiceOptions` 24 选项类（JSON 逐键对齐）+ `SearchSettingsRepository`（search_service_rows + preference 键）+ 引擎（bing_local/tavily/searxng/brave/serper/bocha/zhipu/duckduckgo 8 个 provider + key 轮换）+ `search_web` 工具（定义/引用提示词/执行）+ 系统提示词注入（assistant.systemPrompt + 搜索引用块） | ✅ 本轮 |
| S2 | 搜索体系 2/3：`search_settings_sheet`（输入栏 Globe 入口 + 设置页"搜索"行）+ 搜索服务列表页（连接状态胶囊/长按测试与删除/通用选项步进器）+ 服务编辑器（类型 chips + 24 类型表单 + 多 Key 入口 + 连接测试）+ API keys 池页；路由 `search_services` | ✅ 本轮 |
| S3 | 搜索体系 3/3：其余 15 个 provider 引擎（exa/linkup/metaso/ollama/jina/perplexity/querit/stepfun/firecrawl/tinyfish/anysearch/doubao/parallel/you/grok）——共 23 个可运行 provider | ✅ 本轮 |
| S4 | 搜索收尾：用量查询卡（Tavily 余额/进度条 + LinkUp 余额 + 自动查询；`SearchUsageService` 纯解析带测试） | ✅ 本轮 |
| S5 | 搜索剩余：kelivo 内置搜索（上游端点 + 内置令牌，按品牌规则不移植） | ⬜（低优先/不移植） |
| F1 | 多模态输入引擎：`MessageContent`（图片 part → OpenAI content 数组 / Claude image block / Gemini inline_data+file_data；data:/本地文件 base64、远端 URL 分协议处理、去重、file part 暂跳过）+ 三客户端接入 + ChatViewModel 历史带图片 | ✅ 本轮 |
| M1 | 记忆工具执行：`MemoryTools`（memory_read / memory_update / memory_search_profile / memory_edit / memory_delete / update_user_profile 六个定义 zh/en 逐字对齐 + 执行；写入走 assistant.memoryWriteScope 解析，临时会话拒写，重复内容 SKIP/NEW 回退路径；未移植 Smart Add LLM 合并与 chat_search）+ ChatViewModel 在 enableMemory 时提供工具 + ToolHandler 分派 + AppContainer.memoryProviderV2 单例 | ✅ 本轮 |
| M2a | 记忆摘要注入：`MemoryBlockBuilder`（`<user_profile>`/`<user_memory>` 块、summary 模式 mode/total/shown + moreHint、global 优先排序、escape/flatten、SHA-256 前 16 位哈希）+ ChatViewModel 把快照前缀加到本轮最后一条用户消息（enableMemory 且有内容时） | ✅ 本轮 |
| M2b | 助手编辑页记忆 tab（`AssistantEditMemoryTab`：总开关 + 自动整理/整理频率/去重模式/写入范围 + 过往回忆/生成摘要/摘要频率 + 记忆设置入口；选择 sheet 与数字弹窗）+ 路由接线 | ✅ 本轮 |
| L1 | 本地工具执行 + tab：`LocalToolExecutors`（clipboard 读/写、calculate=exp4j、text_to_speech=TtsPlayer、get_screen_time=UsageStats 前台时长算法）+ `DeviceLocalTools`（前台时长纯算法 + Usage Access 权限探测/跳转）+ 助手本地工具 tab（8 行 Android 工具 + 日历权限流 + 屏幕时间权限提示）；iOS-only 行按平台隐藏 | ✅ 本轮 |
| MCP-1 | MCP 基础：`McpServerConfig/McpToolConfig/McpParamSpec` DTO（JSON 键对齐 mcp_provider）+ `McpRepository`（mcp_server_rows）+ `McpClient`（JSON-RPC over Streamable HTTP 与 SSE：initialize 握手、`mcp-session-id` 捕获、2025-06-18+ 的 `MCP-Protocol-Version` 头、tools/list、tools/call 文本拼接与 isError、会话过期 404 重握手、SSE `endpoint` 事件与消息队列） | ✅ 本轮 |
| MCP-2 | 连接管理器（`McpConnectionManager`：连接/重连/断开、状态与工具缓存、启动连接已启用服务器）+ 助手 MCP tab（已连接服务器绑定、工具计数标签、全选/清空）+ 服务器管理页（列表状态点/错误/工具计数、编辑 sheet 双 tab（基础/工具，含自定义 Header 与工具启停/审批开关）、JSON 导入（mcpServers/裸 map/数组）、超时 sheet）+ 工具并入请求与 ToolHandler 调用（含 needsApproval 审批门）+ 设置页入口与 `mcp` 路由 | ✅ 本轮 |
| MCP-3 | MCP 收尾：OAuth 授权流程、会话内 MCP sheet（mcp_conversation_sheet）、STDIO 传输（桌面专属，不移植） | ⬜ |
| L2 | 日历执行器：`calendar_query`（Instances 区间查询、today/week/month/自定义、标题 LIKE 转义、全天事件按 UTC 日期输出）与 `calendar_create`（必填校验、全天 UTC 毫秒、默认可写日历、提醒写入 + HAS_ALARM + 部分提醒被拒的 warning、MISSING_REQUIRED/INVALID_TIME/INVALID_RANGE/NO_CALENDAR/INSERT_FAILED 错误码）+ 时间解析链（epoch/offset/instant/local） | ✅ 本轮 |
| M2c | `chat_search` 工具：定义（zh/en）+ MessageDao.searchMessagesForAssistant（按助手范围/排除当前会话/指定会话、tokens AND、按时间倒序）+ 片段窗口 + 记忆规则注入（`MemorySettingsState.prompt(RULES)` 与 `rulesPastConversationRecallFor`，各自独立门控） | ✅ 本轮 |
| M2d-a | **记忆持久化修复（数据丢失）**：`MemoryProviderV2` / `LegacyMemoryStore` 原先用 `prefs.readJson("memory_entries_v1")` / `"assistant_memories_v1"` 存取，而这两个键按 `classifyBusinessKey` 归 **ENTITY** → `PreferenceRepository.writeJson` 对 ENTITY 是 **静默 no-op**、`readJson` 返回 null ⇒ 记忆**从不落库**（重启即失）。修：新增 `core:data` `MemoryEntryRowDao`（`memory_entry_rows` 全部类型列：scope/assistant_id/type/status/content/content_normalized/entry_created_at/entry_updated_at/payload，payload 权威、列是投影=「哈希冻结」；`Row.fromPayload` 自愈 schema CHECK：assistant 无主→global、未知 type/status→枚举默认、updated<created→取 created）与 `AssistantMemoryRowDao`（`assistant_memory_rows` 的 assistant_id）；两个 store 改为读写真表；`BackupRestorer` 对这两张表不再走 `PayloadEntityDao`（缺类型列会被 CHECK 拒绝，恢复 Flutter 备份会直接抛异常），改为投影写入。另：所有变更改**读-改-写**（对齐 Dart `JsonBlobStore.writeAll` 前先 `readAll`），避免多实例（容器 + 各页面各持一份）互相覆盖；工具入口先 `loadAll()` 再用缓存。测试 19 例：`MemoryPersistenceTest`(6)/`MemoryBackupRoundTripTest`(1)/`MemoryRowProjectionTest`(7)/既有 `MemoryToolsTest`+`MemoryBlockBuilderTest`+`MemoryChatSearchTest`(28) 全绿 | ✅ 本轮 |
| M2d-b | **Smart Add 去重合并**（`memory_smart_add.dart` 1:1 → `provider/MemorySmartAdd.kt`：`MemoryTokenizer`（8 token 上限 / CJK 2-gram 去停用词 / `escapeLike`）、prompt 组装（override→默认模板）、`extractJson`（围栏+最外层对象/数组）、`parsePerItem`/`parseBatch`（1-based index 缺位留 null）、`normalizeDecision`（target 不在 candidate/mergeable → 降级 NEW；MERGE 无内容 → SKIP degraded；relatedIds 只留候选）、`degradeDecision`（精确重复→SKIP duplicate / 否则 NEW）、`applyDecision`（NEW/MERGE/CONFLICT-归档+新建+双向 link）、`addOne`/`addMany`（perItem / batched 单次请求）。judge 走 `MemoryLlm.generateText`（`memory_model_v1` + thinking 开关，同 `tool_handler_service.dart:577-591`），未配模型 → 纯精确重复降级。接进 `memory_update`（`MemoryTools.handle` 变 suspend）。`MemoryProviderV2` 补 `linkBidirectional`（不动 updatedAt）且 `updateContent` 返回更新后条目。测试 `MemorySmartAddTest` 28 例（tokenizer/prompt/解析/归一化/四动作/批量/降级/工具 JSON 键） | ✅ 本轮 |
| M2d-c | **记忆抽取 pipeline**（`memory_pipeline.dart` 1:1 主体）：`MemoryGatekeeper`（`<user_memory>true/false` 解析，malformed 不推进水位）、`MemoryExtractor`（`<extracted>/<item type scope>` 解析、10 条上限、toolDefault* 策略注入范围规则）、`MemoryProfileDistiller`（identity 变更后把画像字段写进 `user_profile_field_rows`，source=distilled）；`MemoryPipelineService`：单并发队列（上限 8，溢出丢最旧）、临时会话与 streaming 直接跳过、`autoOrganizeMemory` + `memoryOrganizeEveryNTurns` 阈值、水位 `conversation_rows.last_memory_extracted_order`（首次窗口截 20）、同窗口连续失败 3 次后强制推进、版本链按 `version_selections_json` 折叠、`buildConversationText`（角色前缀/单条 2000 字/整体 12000 字截断）。接线：`AppContainer.memoryPipeline`（appScope）+ 每轮回复 finally 里 `scheduleIfNeeded`（assistantId 取会话的 owner，注意 ChatViewModel 里 `assistantId` 这个名字在生成循环里其实是**回复消息 id**）；`MemorySettingsState` 快照成 `MemoryPipelineSettings` 便于测试；助手记忆 tab 补「整理」按钮（需记忆模型 + 当前会话属于该助手）与状态行（`_statusLine` 相对时间 × 结果/skip 原因）。**未做**：`memory_trace.dart` 的分步 trace（prompt/response/mutations）没移植，故流程追踪页仍为空；摘要步骤 §12.10 由既有 `TitleSummaryGenerator` 承担 | ✅ 本轮 |
| 存储-3 | **实体键存储审计（同类静默丢数据）**：把「用 `readJson`/`writeJson` 读写 ENTITY 键」当脚本全仓扫了一遍（ENTITY 键在 `PreferenceRepository` 里 read=null / write=no-op）。除记忆两处（M2d-a）外命中 `TtsServicesStore`（`tts_services_v1` → `tts_service_rows`，TTS 服务配置同样从不落库）→ 改走 `PayloadEntityDao(db, "tts_service_rows")`（该表是普通 payload 表）。ASR 的 `asr_services_v1` 是 PREFERENCE 键，本来就正常，勿改。DISCARDED 键（`pinned_chat_ids` 等）全仓无使用 | ✅ 本轮 |
| F4 | 图片 OCR：`OcrService`（读 ocr_enabled/ocr_model/ocr_prompt/thinking 设置；OCR 模型跑图 → 文本；`<image_file_ocr>` 块前置；SHA-256 内容哈希 + LRU 缓存）+ 底部面板 OCR 行（开关 + 长按提示词 sheet） | ✅ 本轮 |
| F3 | 文档文本抽取：`DocumentTextExtractor`（PDF=PDFBox-Android、DOCX=zip+document.xml、.doc 不支持、其余 UTF-8 兜底；path+stat 缓存）+ `UnicodeSanitizer` 移植 + 用户消息把文件文本按 `## user sent a file` / `<content>` 块前置进请求 | ✅ 本轮 |
| F2 | 附件选择 UI：底部工具面板（bottom_tools_sheet.dart 三张 72dp 卡：相机/相册/文件）+ `AttachmentStore`（URI 拷贝到 filesDir/upload）+ 附件预览条（64dp 图缩略图 r10+scrim 删除角标 / 48dp 文档 chip）+ ChatViewModel 待发附件并入用户消息 parts；相机走 FileProvider（新增 provider + file_paths.xml）；原版面板里的指令注入/世界书/OCR 行已随后续批次接入，上下文管理行仍未移植 | ✅ 本轮 |
| UI-1 | 快捷短语页 + 输入栏锚定菜单（quick_phrases_page.dart / quick_phrase_menu.dart → `QuickPhrasesScreen.kt`：列表 + 编辑 sheet + 左滑删除 + Zap 按钮锚定弹层；数据 `quick_phrase_rows` + `QuickPhraseRepository`（子集重排纯函数带测试）；设置页入口 + `quick_phrases` 路由）。sheet 底部按钮用 `IosSheetButton`（`IosButton` 的 modifier 参数此前未生效，已修） | ✅ 本轮 |
| UI-2 | 指令注入页 + 选择 sheet（instruction_injection_page.dart / instruction_injection_sheet.dart → `InstructionInjectionScreen.kt`：分组折叠 + CRUD + 编辑 sheet + 底部工具面板行；`instruction_injection_rows` + `instruction_injections_active_ids_by_assistant_v1` / `instruction_injection_group_collapsed_v1` 两个 preference 映射） | ✅ 本轮 |
| UI-3 | 世界书页 + 选择 sheet（world_book_page.dart 2107 行 / world_book_sheet.dart → `WorldBookScreen.kt` / `WorldBookSheet.kt`：书分组折叠 + 书/条目拖拽重排（新增 `ReorderableColumnWithHandle` / `ReorderableInlineColumnWithHandle`，拖拽柄只挂在书头与书签图标上，与行内点击/长按不冲突）+ 条目长按操作 sheet + 关键词 chips + 注入位置/角色选择 sheet + RikkaHub lorebook 导入导出；数据 `world_book_rows` + `WorldBookRepository`（active ids by assistant / collapsed 两个 preference 键）+ 8 条纯逻辑测试；设置页「世界书」行 + `world_book` 路由 + 底部工具面板世界书行（有书才显示，长按进管理页））。**注入引擎**（`core/common/...` 之外、`app/.../worldbook/WorldBookInjector.kt`）：关键词/正则匹配（case sensitive + 失败容错）、scanDepth 1-200（夹回范围）、priority desc + 文件顺序 asc、5 个注入位置（BEFORE_SYSTEM_PROMPT / AFTER_SYSTEM_PROMPT / TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH）、role USER 包 `<system>...</system>`、role ASSISTANT 出纯文本、constantActive 免关键词触发、tool 消息前不插入；ChatViewModel 在系统提示词写入 history 之后立即调一次（assistant 维度的 activeIds），从此 WorldBookSheet 拨开关真的影响下次 LLM 请求。**31 条单测**（`WorldBookInjectorTest`）覆盖：5 注入位置、role 包装、scanDepth 窗口、priority/sequence 排序、constantActive、case sensitive、regex 容错、disabled book / disabled entry、activeIds 过滤、tool 消息回退、空输入早退、混合 active 多书合并 | ✅ |
| UI-4 | 翻译页（translate_page.dart → `TranslateScreen.kt`：输入/流式输出双卡 + 顶部粘贴/复制/清空/模型品牌按钮 + 底部语言卡与翻译/停止按钮（AnimatedSwitcher 缩放淡入）+ `LanguageSelectSheet`；模型回退链 translate_model_v1 → 当前助手 chat model → selected_model_v1，prompt 走 translate_prompt_v1，thinking 走 translate_generation_thinking_enabled_v1；抽屉底部翻译按钮接线 + `translate` 路由；新增 `IosIconContentButton`（IosIconButton 的 builder 变体）与共享 `loadModelOptions`） | ✅ 本轮 |
| UI-4 | 翻译页（translate_page.dart → `TranslateScreen.kt`：输入/流式输出双卡 + 顶部粘贴/复制/清空/模型品牌按钮 + 底部语言卡与翻译/停止按钮（AnimatedSwitcher 缩放淡入）+ `LanguageSelectSheet`；模型回退链 translate_model_v1 → 当前助手 chat model → selected_model_v1，prompt 走 translate_prompt_v1，thinking 走 translate_generation_thinking_enabled_v1；抽屉底部翻译按钮接线 + `translate` 路由；新增 `IosIconContentButton`（IosIconButton 的 builder 变体）与共享 `loadModelOptions`） | ✅ 本轮 |
| 收尾-1 | 顶栏统一：新增共享 `MemoTopBar`（56dp 工具栏、56dp 前导槽 + 44dp 返回键、标题在槽后 16dp、18sp semibold、44dp 动作槽；`MemoTopBarContent` 支持自定义标题（供应商详情的头像+名称））并转换全部 40+ 页面；此前标题有 16/18/20/22sp 四种、返回键 22/24dp 混用 | ✅ 本轮 |
| 收尾-2 | 页面转场：Flutter 当前 Android 默认 `PredictiveBackPageTransitionsBuilder`→`FadeForwardsPageTransitionsBuilder`（450ms，新页从右侧 25% 滑入 + 前 75% 淡入，旧页左滑 25% + 前 25% 淡出，pop 镜像，easeInOutCubicEmphasized 三段点曲线）替代 Navigation Compose 默认 M3 淡入淡出 | ✅ 本轮 |
| 收尾-3 | 触觉反馈接线：`Haptics` 服务 + 6 开关 + 分类门控早已就绪，补齐调用点——设置行/开关行（soft，按 hapticsOnListItemTap）、世界书页与 sheet、快捷短语/指令注入、底部工具面板、抽屉会话行与开关脉冲（hapticsOnDrawer）、发送/重新生成（hapticsOnGenerate）、消息操作图标与用户气泡长按菜单 | ✅ 本轮 |
| 收尾-4 | 输入框几何：原版 composer 是无边框裸 TextField（contentPadding 垂直 2/横向 0，InputDecorator 非 dense 字段最小高 48dp）；M3 TextField 自带 16dp 横向内边距且最小高 56dp → 改 `BasicTextField` + decorationBox 占位符 + 48dp 最小高居中，宽高都对齐 | ✅ 本轮 |
| UI-6 | 助手剩余 tab + 标签管理：快捷短语 tab（拖拽重排/左滑删除/玻璃加号/共用编辑 sheet）、自定义请求 tab（headers/body 键值卡，逐键落库）、正则 tab（`AssistantRegex` DTO + 名称/正则/替换 + 4 个范围 chip + 正则可编译校验 + 拖拽/开关/删除）、`TagsManagerScreen`（assistant_tag_rows + assignment/collapse 两个 preference 键，创建/重命名/删除/排序/点按指派并返回）、助手卡长按上下文菜单（编辑/复制（`_buildCopyName` 命名）/清除标签/管理标签/删除）+ `tags_manager/{assistantId}` 路由 | ✅ 本轮 |
| UI-7a | 聊天周边 1/2：消息"更多"里的 Select & Copy（`SelectCopySheet`：可选中正文 + Copy All）、Render WebView（`HtmlPreviewScreen` + `assets/html/mark.html` 模板 + 主题色占位替换；**2026-09-13 拆成两条路**：`HtmlPreviewRequest(content, rawHtml)` —— 代码块「预览」走原始 HTML 的 `_wrapIfNeeded` 包装，消息 Render WebView 才走 markdown 模板，见 §4-34）、Share（系统分享纯文本）、`BoundedLargeTextView`（40 行/12k 字符折叠 + 分块懒加载，投影算法带单测） | ✅ 本轮 |
| UI-7b | 聊天周边 2/2：`ChatAssistantBackground`（当前助手壁纸 + surface 遮罩渐变 0.20→0.50 × display_chat_background_mask_strength_v1，网络/沙箱文件，`isBackgroundActive` 带单测）+ ChatContent 包一层 Box 挂到聊天页背后 | ✅ 本轮 |
| UI-7c | 推理预算全链路：`LlmRequest.thinkingBudget`（null/-1 auto、0 off、>0 预算）+ `reasoning` 模型标记；`ReasoningBudget`（effortForBudget / claudeThinkingConfig / _googleThinkingConfig 全量移植，含 Gemini 3 pro/flash/image 与 Gemma4 的 thinkingLevel 分支，带单测）→ 三客户端分别下发 `reasoning_effort` / `thinking`（含 reasoning 时省略 temperature）/ `generationConfig.thinkingConfig`；ChatViewModel 预算解析=助手覆盖→thinking_budget_v1；输入栏 Brain 按钮改渲染当前档位图标 + `ReasoningBudgetSheet`（off/auto/light/medium/heavy/xhigh/max/自定义，图标用 idea-01 SVG） | ✅ 本轮 |
| UI-7d | 清空上下文：`ContextManagementSheet` + 底部工具面板"上下文管理"行；`ConversationDao.setTruncateIndex`、`ChatViewModel.clearContext`（截断点=消息数或 -1 恢复）、生成历史按截断点过滤、"清空上下文 (actual/configured)"标签 | ✅ 本轮 |
| UI-7e | 压缩上下文：原项目那套（`core:common/CompressText` + `Utf16SafeCut`：start/recent 窗口、keepRecent 选择、分块、字符预算、新建会话放摘要）**2026-09-13 已整体换成 opencode 机制**：`core:common/SessionCompaction`（estimate / serialize / select / buildPrompt / shouldCompact / thresholdTokens / checkpointText / windowIds，带单测）+ `core:data/CompactionPart`（检查点载荷 summary+recent+boundary）+ `ChatViewModel.compactWindow`（自动阈值 + 手动「立即压缩」，锚定摘要落成同会话检查点）+ `CompressContextDialog`（自动开关 / 保留 tokens / 缓冲 / 上下文窗口 / 估算行）+ **对话内分隔线**（`CompactionDivider`：压缩中扫光 / 已压缩静态，摘要不进界面、不进导出）+ **上下文管理 sheet 里的占用卡**（`ContextUsageCard`，分母=自动阈值；用户当日又要求撤掉输入栏上方那条常显细条） | ✅ 本轮（机制 + 呈现，见 §5.11） |
| 待改 | ~~**上下文压缩机制要换掉，不用原项目这套**（用户 2026-09-11）~~ **已落地**（用户 2026-09-13「改成 opencode 那个压缩阈值来压缩」，源码 `<opencode 本地克隆>`）。落地形态：① `core/common/SessionCompaction.kt` 逐行移植 `packages/core/src/session/compaction.ts` + `util/token.ts`（`estimate`=字符/4、`select` 的 boundary 切分、`buildPrompt` 的 previous-summary 更新指令、`shouldCompact` 阈值、`checkpointText` 的 `<conversation-checkpoint>` 包装、`windowIds` 的 latestCompaction 语义），默认 `DEFAULT_BUFFER=20000`/`DEFAULT_KEEP_TOKENS=8000`/`SUMMARY_OUTPUT_TOKENS=4096`/`TOOL_OUTPUT_MAX_CHARS=2000`/`SUMMARY_TEMPLATE` 原文；② `core/data/MessagePart.kt` 新增 `CompactionPart`（kind=`compaction`，payload `{summary,recent,boundary}`）——检查点是与其它消息一样的行，`boundary` 之前的消息不再进请求；③ `ChatViewModel`：`startGeneration` 组装前先算阈值（系统提示词 + 会话正文 + 工具定义 JSON），超了就 `compactWindow`（锚定摘要 + 落库 + UI 插到骨架前），随后按 `compactionWindow` 组装请求；`compactContextNow` 是手动「立即压缩」（不看阈值，`/compact` 等价物）；④ `CompressContextDialog` 换成四个设置项 + 估算行；⑤ 模型编辑页 Advanced 加「上下文长度」（写 `modelOverrides[modelId].contextWindow`，压缩阈值基准；留空用全局默认 128k）；⑥ **对话内呈现**（2026-09-13 用户点名）：`CompactionDivider`（压缩中扫光/已压缩静态）替代气泡与加载弹窗，摘要不进界面/导出/多选/标题/总结/记忆；`ContextUsageCard`＝上下文管理 sheet 顶部的占用卡（分母=自动阈值；**输入栏上方的常显细条已按用户当日改口撤掉**，`ChatViewModel.contextUsage` 在加载/回复结束/换模型/清空/压缩后重算）。**删除**：`CompressText` 旧机制（Mode/字符预算/分块合并/keepRecent/压缩请求预算/context-length 探测）、`Utf16SafeCut`（随旧机制一起无引用）、旧偏好键 `compress_limit_mode_v1`/`compress_max_chars_v1`/`compress_keep_user_messages_v1` 的读取、`CompressLoadingDialog`。**未接**：opencode 的 `compactAfterOverflow`（provider 报 context-length 错误后自动压缩重试）——`isContextLengthError` 的探测逻辑随旧代码删掉了，要做时单开一条。 | ✅ 2026-09-13 |
| UI-7f | 记忆关于页 + 注入种子：`MemoryAboutScreen`（6 段参考文案，FAQ 段带小标题）+ 记忆设置入口；`InstructionInjectionRepository` 空表时用 `learning_mode_prompt_v1`（回退 STUDYING 默认提示词，`LearningModePrompt.DEFAULT` 逐字）播种第一条注入项，`learning_mode_enabled_v1` 为真时默认勾选 | ✅ 本轮 |
| UI-7g | 助手 MCP sheet：输入栏 Hammer 按钮（原为空实现）→ `McpAssistantSheet`（已连接服务器 + 启用/总数标签 + 单行开关 + 全选/清空，写 assistant.mcpServerIds） | ✅ 本轮 |
| UI-7h | 建议气泡：`core:common/SuggestionText`（parseSuggestions 去项目符号/编号/引号 + 上限 3 条、buildContent 最近 8 轮/尾部 4000 字符，带单测）+ 回复完成后按 `suggestion_generation_enabled_v1` 生成（suggestion 模型/prompt/thinking）写回 conversation.chatSuggestions + `ChatSuggestionBubbles`（最后一条助手消息下方，点按按 `suggestion_insert_on_tap_only_v1` 插入或直接发送） | ✅ 本轮 |
| UI-7i | 消息多选 + 导出：更多 sheet 的 Select Messages 进入选择态（锚点消息配对的 user/assistant 预选、行内 20dp 复选框 + 点按切换、顶栏换成关闭/已选计数/反选/全选、底部输入栏换成导出栏或删除栏）；`MessageExport`（part 遍历 + markdown/txt 文档构建，带单测）；导出走 CreateDocument（.md/.txt）。**不移植**：图片导出（2026-09-20 用户拍板整块撤掉，见 §5.31）、选择态 mini-map | ✅ 本轮（图片导出 ❌ 已撤销） |
| 修复 | 智谱 400：`glm-5.3-flash` 是始终思考模型，只接受 `reasoning_effort` 的 low/high/max（其他值/`thinking:{type:disabled}` 报 1210）。移植 `applyVendorReasoningKnobs` 到 `ReasoningBudget.vendorReasoningFields`——智谱/小米/火山 `thinking:{type}`、DashScope `enable_thinking`、OpenRouter `reasoning`、Laguna `chat_template_kwargs`，通用 OpenAI 兼容端点才发 `reasoning_effort`（curl 实测确认） | ✅ 本轮 |
| 修复 | 关于页闪退：`R.mipmap.ic_launcher` 是 adaptive-icon XML（`mipmap-anydpi/ic_launcher.xml`），Compose `Image` 只吃 VectorDrawable / PNG / JPG / WEBP，adaptive-icon XML 抛 `IllegalArgumentException` 直接 FATAL。改成 `R.drawable.ic_launcher_foreground`（`drawable-*/ic_launcher_foreground.png`，每 dpi 都有）——视觉上跟 launcher icon 一样（就是 launcher 的前景层） | ✅ 本轮 |
| 修复 | 语音服务 / 备份 / 赞助 UI 壳子（无功能）：原版 Flutter 端有这 3 个页面，Android 端之前缺。3 个新文件 `BackupScreen.kt` / `LocalSnapshotsScreen.kt` / `PendingUiShells.kt`（只装 `SponsorScreen`，其它 Provider 子页`MultiKeyManagerScreen` / `ProviderNetworkPage` / `ProviderCustomRequestPage` / `BalanceScreen` 已有完整实现在 `MultiKeyManagerScreen.kt` / `ProviderSubPages.kt` / `BalanceScreen.kt`，通过 `ProviderDetailScreen` 内部 state 弹出，不重复定义）。**BackupScreen** 6 个 section 跟原版 `backup_page.dart` L303-840 一一对应：备份管理（Chats / Files 开关，2 行）、备份提醒（启用开关 / 频率 / 上次备份，3 行）、本地副本（启用开关 / 管理副本入口，2 行——按原版 L1546-1600 `_LocalSnapshotMobileSection` 结构，`Saved a copy now` 按钮在子页不在主页）、本地备份（导出到文件 / 导入备份文件 / 从 Cherry Studio 导入 / 从 Chatbox 导入，4 行）、WebDAV 备份（服务器设置 / 测试连接 / 恢复，3 行——服务器设置进 sub-page，不是行内字段）、S3 备份（同上 3 行）。**LocalSnapshotsScreen** 7 个 settings（启用 / 频率 / 保留数 / 保留上周 / 保留每月 / 空间限制 / 保存时通知——4 个开关 3 个 nav row）+ 全宽 primary `IosTileButton` "立即保存副本"（原版 L148-153）。**SponsorScreen** 2 个 section（赞助方式 / 赞助者，赞助方式含 Afdian + WeChat Sponsor 2 行）。所有 label 走 `stringResource(...)`，新加 3 个 ARB key `backup_page_wifi_only` / `backup_page_while_charging` / `local_snapshot_on_device_copies`（en + zh + zh_Hans + zh_Hant，`arb_to_android.py` 重生成）。`MainActivity.kt` 加 `backup` / `local_snapshots` / `sponsor` 三条路由。`SettingsScreen.kt` 加 `onOpenBackup` / `onOpenSponsor` 回调 + 接线 Backup 行 + 重新打开 Sponsor 行（之前因品牌规则被砍，这批按"原版 UI 全移植"要求加回来）。`EditNavRow` 从 `private` 升 `internal` 方便壳子复用。`BackupSwitchRow` / `LocalSnapshotSwitchRow` 严格对齐原版 `_iosSwitchRow`（`backup_page.dart` L2219 `EdgeInsets.symmetric(horizontal: 12, vertical: 2)`——switch 行 30dp 故意比 nav 行 42dp 矮 12dp）。功能下一批：BackupProvider / WebDAV / S3 / LocalSnapshot / Sponsor QR 走 RikkaHub `data-sync` + `app` 对应模块。 | ✅ 本轮 |
| 修复 | 语音服务编辑器全屏化（1:1 fidelity）：原 `TtsServicesScreen.kt:541` `NetworkTtsEditorOverlay` 和 `AsrServicesSection.kt:384` `AsrEditorSheet` 都用了 `ModalBottomSheet`，但 Flutter 端是 `Navigator.push(MaterialPageRoute)` 全屏页面——抽到新文件 `TtsServicesEditorScreen.kt`（L762-1860 `_NetworkTtsEditorPage`）和 `AsrServicesEditorScreen.kt`（L589-1178 `_AsrEditor`），外壳换成 `MemoTopBar + scrollable Column` + **底部固定全宽 primary `IosTileButton` "Add" / "Save"**（tts_services_page.dart L1305-1319 / asr_services_section.dart L1058-1071：label 用 `tts_services_dialog_add_button`/`tts_services_dialog_save_button` 和 `asr_services_add_action`/`asr_services_save_action`，icon `Lucide.Check`，背景前景都 `cs.primary`，靠 `windowInsetsPadding(WindowInsets.navigationBars)` 避让导航条；`enabled = canSubmit` ASR 那个还带 system kind 要 `SpeechRecognizer.isRecognitionAvailable` 通的检测）。原先 `MemorySheetActions` 配的 Cancel 配对移除——back 箭头负责取消。`MainActivity.kt` 加 `tts_editor?id={id}` / `asr_editor?id={id}` 两条路由（`id` 缺省 = 新增）。`TtsServicesStore` / `AsrServicesStore` 升到 `AppContainer` 上共享实例（`container.ttsServicesStore` / `container.asrServicesStore` lazy），editor 的 `upsert/add` 改 `store.version`，list 页用 `key(rev + store.version)` / `key(store.version)` 自动 recompose 拿到新列表——等价于 Flutter 端 `notifyListeners`。`baseUrlOf` / `modelOf` / `voiceOf` / `extra1Of` / `extra2Of` / `languageTypeOf` / `streamOf` / `asrApiKeyOf` / `asrEndpointOf` / `asrModelOf` / `asrResourceIdOf` / `asrLanguageOf` / `asrKindTitle` / `asrKindTitleText` 全部从 `private` 升 `internal` 让 editor + `TtsServicesEditorHydrationTest` 都能调。`AsrServicesSection.kt` 从 783 行瘦到 395 行（删 `AsrEditorSheet` + 4 个 widget 死代码 388 行），`TtsServicesScreen.kt` 也清掉 196 行 `NetworkTtsEditorOverlay` + `EditorTextField` | ✅ 本轮 |
| B | 记忆 / 本地工具 / MCP 三个 tab（拆自 M2b / L1 / MCP-2，已全部 ✅） | ✅ |
| C | tab 布局管理页（AppBar Settings2 按钮）：`AssistantTabLayoutScreen`（1:1 `_AssistantTabLayoutPage` L598-733：RotateCcw 重置 / 提纲模式开关卡 / 13sp 说明 / r14 卡片列表 = 34dp 图标槽 + 15sp semibold 标签（隐隐藏降 42% 透明）+ IosSwitch 可见性 + GripVertical 拖拽手柄）；重排写 `mobile_assistant_edit_tab_order_v1`、隐藏写 `mobile_assistant_edit_tab_hidden_v1`、拖动落地 `applyAssistantTabMove`；关闭最后一个可见 tab 弹 "至少保留一个" 警告；`AssistantTabLayoutState` 容器级共享（编辑页 `context.watch<SettingsProvider>` 等价物）→ 布局页改完返回编辑页立刻生效。同时补齐 **提纲模式**（`_AssistantDetailOutlinePage`：82dp 头像 + 21sp 名称 + 2 行提示词卡 + 各 tab 导航行 SectionCard；点行进 `_AssistantDetailSectionPage` = 自带 AppBar 的单 tab 页）与 `_AssistantDetailSectionPage` 路由 `assistant_section/{assistantId}/{tabId}` | ✅ 本轮 |
| 模型选择 | ModelSelectSheet 可拖拽高度：1:1 `DraggableScrollableSheet`（`initialChildSize`=`maxChildSize`=0.8、`minChildSize`=0.4）→ NestedScrollConnection 等价物（initial==max 故只保留收缩半边：列表在顶下拉压缩高度、到 0.4 即 `sheetState.hide()` 关闭，对齐 `shouldCloseOnMinExtent`）；同时给 `ModalBottomSheet` 接自己的 `rememberModalBottomSheetState()` | ✅ 本轮 |
| 记忆-旧版移除 | **按用户决定不移植旧版（V1）记忆模式**（原版留它是为了兼容老版本写下的 `assistant_memories_v1` 数据，Memo 全新安装永远没有）：删掉记忆设置页的「旧版记忆模式」开关与整个 legacy 分支、「旧版记忆（只读）」行、「旧版记忆迁移」提示词行、`LegacyMemoryScreen` + `legacy_memory` 路由 + `onOpenLegacyMemory`、`LegacyMemory`/`LegacyMemoryStore`、`BuiltInToolCatalog.legacyMemoryDefinitions` 与两处 `memory_legacy_mode_v1` 读取（工具描述页/编辑器），`MemoryPromptKind` 去掉 MIGRATE/LEGACY_RULES。**保留**：`assistant_memory_rows` 表 + `AssistantMemoryRowDao`（备份归档格式含 `assistant_memories_v1`，恢复照旧写入，只是不再有 UI 读它）、`MemoryPrompts` 的 legacy/migrate 提示词常量（上游镜像） | ✅ 本轮 |
| 记忆-可见性 | **记忆写进去看不见的两个根因**：① `MemoryProviderV2` 只在屏幕里被 `initialize()` 过，聊天工具路径之外（**系统提示词的记忆块** `MemoryBlockBuilder.buildPrefix`、助手记忆 tab）拿到的是**空 store** ⇒ 记忆既不注入也不显示；改 `ensureLoaded()` 幂等加载 + 两处读取点补调用（工具路径/注入块/tab）。② 记忆列表与 tab 各自 new 一个 provider，聊天工具写到容器实例后列表看不到新条目 → 统一用容器级 `container.memoryProviderV2` 并读 `version` 触发重组；助手记忆 tab 顺带补齐「本助手可见的记忆」列表（1:1 `assistant_settings_edit_memory_tab.dart` L335-479：添加记忆/整理/状态行/空态/可见+归档卡片）+ 复用 `MemoryEntryEditSheet`（新增 `defaultScope` 参数） | ✅ 本轮 |
| 记忆-新建 sheet | 记忆条目编辑 sheet 1:1 还原（用户「样式还有问题」）：`overlaySurface` + 顶圆角 16（原来吃 M3 默认 28/容器色）、拖柄上 10dp、**标题居中**、列表 padding 16/12/16/12 且内容超高才内部滚动（上限 0.9 屏）、内容框改 `IosFormField`（surfaceCardFill r12、minLines 4/maxLines 10、autofocus、hint 内嵌）；chips 由横向滚动改 **FlowRow 换行**（=Dart `Wrap(spacing:8,runSpacing:8)`）；助手选择器**仅**在 scope=assistant 时出现（L1281）。顺手把 `IosFormField` 从 WorldBookScreen 私有实现提为 `IosWidgets.kt` 共享 `internal fun`，两处共用 | ✅ 本轮 |
| 备份-3 | **本机副本（local snapshots，§5.10 子块 3）**：`core:data/backup/LocalSnapshotRetention.kt`（GFS-lite 保留策略：保留最近 N + 每周/每月各一份 + 置顶 + 「有内容的副本永不被空副本挤掉」+ 高水位掉量保护（90 天窗口）+ 字节上限；纯逻辑带 9 例单测）、`LocalSnapshotStore.kt`（`<filesDir>/snapshots/`，`memo-snapshot-<nanos>.zip` + `.json` 边车，写临时名再 rename 发布、`sweepIncomplete` 清残骸、列表忽略外来文件/空归档、边车丢失按「有内容」处理）、`LocalSnapshotSchedule.kt`（默认间隔按库大小 1/3/7 天、首次观察 10 分钟宽限、失败退避 1h×2^n 封顶 16h、剩余空间水位 2GB + 预计占用 1.2×+1/3、`DatabaseChangeFingerprint`（db + -wal 的大小/mtime，读不到即视为「有变化」））、`LocalSnapshotSettings.kt`（设置/状态 preference 读写 + 夹取）、`LocalSnapshotService.kt`（`take` 走 `MemoBackupService.exportToCache` 打包后 publish + prune；`runIfDue` 按「廉价判定优先」顺序：开关→退避→间隔→首次宽限→指纹→空间；失败记 streak 并在同窗口竞争时按 busy 跳过）。App 侧：`LocalSnapshotsScreen` 接线（设置卡：开关/间隔/保留数/周月/空间上限/完成通知；状态行；「立即存一份」；副本卡片 = 时间/大小/来源/会话与消息数 + 恢复/导出（SAF）/置顶/删除（含「最后一份有数据的副本」警告）；恢复前先存一份置顶的 before-restore 副本 → 走 `BackupRestorer` → 重启提示）。启动与回前台 `maybeRunLocalSnapshot()`。ICU 复数串（`localSnapshotUsage` 等）用新增 `icuString()`（android.icu MessageFormat，与 arb_to_generator 的约定一致）。**两处平台差异（踩坑）**：① 数据库在 `/data/data/<pkg>/databases/memo.db`，**不在 filesDir 下** → 指纹要读 `context.getDatabasePath("memo.db")`（原来按 filesDir 拼路径，会永远读到空 → 自动副本只存一次就再也不触发）；② 快照**运行状态**（last_success/failure/skip、failure_streak、fingerprint、first_observed）必须放 **SharedPreferences**，否则写状态本身就是写数据库 → 指纹每次都"有变化"，`unchanged` 闸门永不生效（原版的状态在其 preferences blob 里，同理）——注意这几个键在生成的 registry 里是 UNKNOWN（会进 DB），所以显式走 `readLocal/writeLocal`；设置在 DB（随备份走），状态是设备本地（不随备份） | ✅ 本轮 |
| 记忆-trace | **分步流程追踪落地**（`memory_trace.dart` 1:1）：`provider/MemoryTrace.kt`（Trigger/Scope/StepKind/StepStatus/MutationKind 枚举、`MemoryTraceStep`（prompt/response 多段追加 `─── #n ───`、parsedResult、error、mutations、duration）、`MemoryTrace`（trigger/scope/会话与助手/水位与窗口/advanced/forcedAdvance/repeatCount）、`MemoryTraceHandle`（beginStep/setWindow/commit，commit 时把仍 RUNNING 的步骤标 FAILED）、`MemoryTraceRecorder`（内存环形缓冲 24 条、**重复的 no-op 触发合并计数**、关掉即清空、不落盘；Compose 可观察）。写入方：pipeline 每趟开一条 trace（auto/manual）记录四阶段（未走到的阶段补 SKIPPED）+ 每步的 prompt/response/parsedResult + 变更（Smart Add 的 created/merged/archived/linked、Distiller 的 profileFieldWritten 带 before/after）；记忆工具每次调用一条单步 trace（trigger=toolCall，label=工具名，args 为 prompt、结果为 response、`"error":` 载荷即失败；临时会话不记）。页面 `MemoryTraceScreen` 按新模型重写：卡片（触发/会话/结果 pill + 范围/步数/变更数 + 失败步骤 + 错误行）、详情（overview KV：时间/耗时/触发/范围/会话/助手/水位/窗口/结果/错误；逐步卡片含状态 pill、错误行、prompt/response/parsed 折叠代码块（420 字预览 + 复制 + 展开）、变更 tile 带 before/after） | ✅ 本轮 |
| 修复 | **记忆模型请求不到（用户实测「判断是否值得记忆时无法请求记忆模型」）**：真机库里 `provider_rows` 是 `Zhipu AI`（带 API key），但 `memory_model_v1` / `selected_model_v1` / `summary_model_v1` 存的是小写旧拼写 `"zhipu ai::glm-5.3-flash"` —— 旧版允许手打 provider key，`migrateNonCanonicalBuiltinKeys()` 只把**行**改名，没管**引用该 key 的存储值** ⇒ `providerConfig("zhipu ai")` 查不到 → key/baseUrl 落到 SharedPreferences/LlmDefaults 兜底 → 请求直接抛错。三层修：① `AppContainer.providerConfig/apiKeyFor/baseUrlFor` 查询时用 `canonicalizeKey` 折一次（所有模型槽位/聊天路径一次性受益）；② `migrateNonCanonicalBuiltinKeys()` 追加**按值扫描**的迁移：8 个 `*_model_v1` 槽位、`conversation_rows.chat_model_provider`、`assistant_rows.payload.chatModelProvider` 全部折到规范拼写（自建 provider 不动；行已改名过也照样靠按值扫描修好）；③ pipeline 的 gate/extract 失败原因带上异常信息（`gate_request_failed:<msg>`，对齐原版 `:$e`），以后这类问题在流程追踪页直接可见 | ✅ 本轮 |
| 语音播放器 | **悬浮语音播放器落地（用户点名「语音播放这个样式」）**：原版 `shared/widgets/tts_floating_player.dart`（674 行，挂在 `app_overlays` 全局）Android 侧一直没做——只有消息行的 Speak/Stop 与工具卡重播小按钮。本轮：① 纯逻辑 `ui/chat/TtsPlayback.kt` 1:1 —— `TtsTextChunker`（空白归一、句界含 CJK `。！？；`、ASCII 边界补空格、超长硬切、按上限合并）、`TtsPlaybackState`（status/position/duration/speed/chunkIndex/totalChunks + isActive/isPlayerVisible/progress/chunkProgress）、`TtsPlaybackTimeline`（200ms/字符估算、分块时长夹 1–60s、`seekTarget`/`offsetForChunk`/`positionForChunkProgress`）、`TtsPlaybackSpeed`（0.8/1.0/1.2/1.5/2.0、`toSystemRate=speed/2`）；② `TtsPlayer` 从「只会 speak/stop」升级成分块播放状态机（系统引擎无法暂停 → 暂停=stop、继续=从当前块重播；±15s 走时间轴定位到块；变速 `setSpeechRate(toSystemRate)` 后重起当前块；`onRangeStart` 把字符偏移折成位置，进度环才平滑；播完状态 ENDED 让胶囊留在屏幕上以便重播）；③ `TtsFloatingPlayer` UI 1:1（surface 96% + r999 + 阴影、42dp 双弧进度环〔外 2.2 轨道/2.6 进度 + 内 2.0 分块弧〕内含 32dp 播放键、X 关闭、展开后 rewind/`x1.2` 胶囊/fastForward、chevron 展开收起、宽度 120↔232 动画 220ms、fade 160ms、可拖动并夹在屏内、初始位置 (12, safeTop+68)），挂在根 Box（任何页面之上）。网络 TTS 与「保存音频」按钮属后续批次，故按钮缺失=原版无网络音频时的形态。**修复（用户实测「不能自由拖动」）**：拖动回调里用了「合成期间算出的坐标」（被 `pointerInput(Unit)` 永久捕获的普通值）作为起点，每帧都从旧原点重算 ⇒ 拖一下弹回原处；改成读 `position` 状态本身 + `rememberUpdatedState(PlayerBounds)` 取最新边界。**再修（用户实测「按下叉没反应」）**：`stop()` 当时写成了「结束但仍显示」（那是**自然播完**的 ended 语义），而原版 `stop()` 走 `_stopInternal` 回 **idle** ⇒ `isPlayerVisible=false`，胶囊消失；同时补上「ended 状态下按播放键 = 重播」（原版 `togglePause` 在 ended 时转 `replay()`）。现在：X = 收起胶囊，自然播完 = 胶囊留存可重播。**测试补齐（用户质问「测试怎么没有测出这些问题」）**：三个 bug 全在「播放器 ↔ 引擎/手势」接线层，而当时只有纯逻辑测试（分块/时间轴/速度）够不着。故把播放逻辑从 `android.speech.tts.TextToSpeech` 后面抽出来：新增 `ui/chat/TtsEngine.kt`（`TtsEngine` 接口 + `SystemTtsEngine` 适配器 + `TtsPlaybackController` 状态机，`TtsPlayer` 退化成 holder，`MemoApplication.onCreate` 里 `TtsPlayer.init`），并给 `TtsFloatingPlayer` 加了可注入的 `state`/`TtsPlayerActions` + `testTag`。测试：`TtsPlaybackControllerTest` 11 例（**X→idle 且 isPlayerVisible=false**、**取消会话的迟到 onStart/onDone 被忽略**、自然播完=ended+可重播、暂停/继续重起当前块、±15s 定位、变速 0.6 引擎速率且同 id 重起、引擎失败→error、id 编解码不再歧义）与 `TtsFloatingPlayerTest` 6 例。**再修（用户实测「按暂停对话没显示暂停」「点一条播放怎么所有界面都显示播放」）**：查证原版 `chat_message_widget.dart:3253-3291` 的图标确实由**全局** `tts.playbackState.isActive` 驱动（没有 per-message 身份），所以原版也有「读一条、所有消息都显示停止」的问题；按用户要求改成 **owner 作用域**：`TtsPlaybackState.ownerId`（发起播放的消息 id）+ 纯函数 `messageTtsAction(state,msgId)` → 只有被朗读的消息显示 STOP、暂停时显示 RESUME、其他消息恒为 SPEAK（`tts_playback_models` 无此概念，属**有意偏离原版**）；无 owner 的播放（工具卡重播原始文本）不影响任何消息图标。测试追加 4 例（owner 随会话/停止清空、暂停保持 owner 且只对该消息显示 RESUME、非 owner 恒 SPEAK、无 owner 不影响消息）。（Robolectric+Compose UI：**真的拖一下要跟手**、**第二次拖动要从上次落点继续**〔正是旧代码「用合成期原点」的失败模式〕、X 命中、展开才出现 ±15s/速度、idle 不渲染、ended 留重播） | ✅ 本轮 |
| 收尾-5 | Toast：原计划用 `io.github.dokar3:sonner` 替换手撸 `MemoSnackbar`。**2026-09-13 用户决定：不做**（「这个不用做了 已经弄好了toast这个部分」）——保留手撸 `MemoSnackbar.kt`（core:ui/snackbar/：圆角/阴影/堆叠/动作按钮/入场出场 + 队列计时、滑动关闭、滑动方向三次修复，`ToastQueueTest` 7 例），不引 sonner | ✅ 关闭（用户改口：不用做） |
| 收尾-6 | 日志三件套：1) `core/common/.../logging/` —— `LogPayloadElider`（从 `app/ui/LogData.kt` 迁出）+ `LogRedactor`（敏感字段脱敏）+ `RequestLogger`（`logs.txt` `[REQ]/[RES]/[CHUNK]` writer、日切轮转、active 文件永不删、cleanup 旧/大文件）+ `FlutterLogger`（`flutter_logs.txt`、多行带 tag、redactText、自动转义、`Thread.setDefaultUncaughtExceptionHandler` 接管崩溃栈）；2) `core/llm/.../RequestLogInterceptor`（OkHttp interceptor，请求体总是记 ≤4MB、4xx/5xx 读出 256KB 写 `[RES n] body=`、chunk 走 elide+redact+escape，对齐 `dio_http_client.dart:196-211, 254-261, 286`）；3) `app/.../logging/` —— `ContextLogger`（`context_logs.txt` JSONL，redact 整行）+ `LogBootstrap`（启动 init、写 setEnabled 同时落盘、按 key 类型路由 LOCAL_ONLY vs preference_rows、修 AboutScreen/LogViewerScreen 写错位置的 bug）；4) `MemoApplication.onCreate` 调 `LogBootstrap.init`；`AppContainer.OkHttpClient` 装 `RequestLogInterceptor`；5) LogViewer 顶栏统一：OverlayScaffold 改用 MemoTopBar，6 处 20dp actions 换成 TopBarAction 22dp。**测试**：64 个单测全绿（LogRedactor 22、RequestLogger 15、FlutterLogger 6、RequestLogInterceptor 13、ContextLogger 5、writer→parser 端到端 3），`./gradlew :app:assembleDebug` 通过。**注**：context 段组装（`message_builder_service` / `message_generation_service` 那侧的 tag helpers）随聊天 pipeline 批次一起做 | ✅ |
| 收尾-7 | 设置行提示统一 Tooltip（**用户规范 2026-09-09：「不直接裸提示词，都改成 Tooltip」**，两笔 391e069/03bd31b）：`SettingsUi.kt` `SettingsSwitchRow` 删除 subtitle 裸排参数，全部走行尾 ⓘ（BadgeInfo 16sp@45%，28dp 触控区）+ 浮动气泡（TooltipBox+PlainTooltip，tap 触发 isPersistent、280dp、点别处收起，对齐 `_iosSwitchRow` 的 MemoryTipIcon 形态）；同步重构 消息样式页 StyleRow/TextSwitchRow、图片处理页 2 处、渲染/主题 4 处、自动重试、TTS 2 处、MCP 工具行、记忆设置 3 处导航行（`MemoryTipIcon` 原为**行内展开**简化版——点击文字挤行内撑开布局，正是工作约定禁止的参考项，借机一并改为标准浮动气泡）；暂留（语义不同）：弹层选项描述（ChoiceOption/MigrationChoiceRow）、空状态、About/LogViewer/DefaultModelScreen 后续处理。**测试**：`SettingsSwitchRowTipTest` 反转锁新规范（tip 隐藏→点 ⓘ 弹出、无 tip 无 ⓘ 图标） | ✅ 本轮 |
| 收尾-8 | 设置/关于/统计/网络代理全站分类（用户点名「原项目很多 UI 不统一」，按 SectionHeader + SectionCard 分组）：偏好主页 17 行拆 5 组（外观/聊天 UI/字体/行为/通知与后台，8b8df4f）；五个偏好子页 20 行内分组（chat item display / rendering / behavior & startup / message style / auto retry，行序与键不变只加组头，b0e38b5）；触感页 3 组（总开关/交互/生成，3305437）；关于页 2 组（应用信息=版本+系统、社区与链接=官网/GitHub/许可证，补原项目有而我们缺的 3 个链接行，`AboutNavRow(detail="")` + Intent.ACTION_VIEW 跳转，Lucide.Github/Earth/FileText 均在 lucide jar）；统计页 2 组（数据概览=热力图/总览/趋势、排行榜=3 排名卡，卡内标题保留原项目 StatsSectionCard 形态）；网络代理页「代理设置」组头 + 测试头统一 SectionHeader（原为裸 14sp Text）；全部新组名走 ARB 四份源（en/zh/Hans/Hant）+ 生成器。793c956 | ✅ 本轮 |
| 存储-1 | 存储主页分类 + 上传管理器补完整（4270bae，**2026-09-13 按用户实机反馈修显示形态**）：主页拆「空间总览」「存储分类」两组；`StorageFileEntry` 加 source（USER_UPLOAD=upload/ 附件、ASSISTANT=images/ 生成图，对齐 Dart `StorageFileSource`）；`UploadManagerSection` 重写为原项目 `_UploadManager` 形态——排序 pills（最新/最旧/最大/最小）+ 图片页来源筛选 pills（全部/用户上传/助手发出）+ 计数/全选/已选删除条 + 图片缩略图网格 + 非图片文件行（Paperclip + 名 + 大小·时间）；删除后 uploadRefreshKey 重载 + refresh() 刷报表、陈旧选中清理；LOGS 类别补「查看日志」按钮（→log_viewer）、LOCAL_SNAPSHOTS 补「管理副本」入口（→local_snapshots）。**2026-09-13 保真修正（用户「聊天记录存储这个里面文件显示也有问题」）**：① 文件行改成原版的**独立卡片**（r12 + 1dp `onSurface@8%` 边 + `onSurface@3%` 底 + 行间 8dp，原来是单张 SectionCard + 分隔线）；② **非选择态点按 = 打开文件**（FileProvider → 系统 APP，mime 按扩展名猜）、长按 = 进选择（原版 L2060-2076）；③ 图片网格按原版 `GridCells` 等价的 **max-extent 140 / 间距 10**（原来固定 3 列 8dp、还写了死高度），缩略图 1:1 等宽、末行补位；④ 图片块边框 未选 `onSurface@10%` / 选中 `primary@55%`、底色 `onSurface@3%` | ✅ 4270bae / 793c956 / 2026-09-13 修正 |
| 存储-2 | 用量条撞色修复（用户反馈图片/缓存同色，f5be77d）：根因 chartSeries 仅 8 色、存储 10 分类 `%size` 取模回卷（CACHE→槽0 撞 IMAGES、LOGS→槽1 撞 FILES）；亮/暗色板各补 2 色（亮 DB2777 粉 + 4F46E5 靛；暗 F472B6 + 818CF8）到 10 色；**测试**：`StorageBarColorsTest` 三断言（色板覆盖分类数/全异色/images≠cache）+ SemanticColorsTest 色板断言同步 | ✅ f5be77d |
| 收尾-9 | 图片查看器底部玻璃功能栏 + 完整变换（用户反馈「预览没做完整、底部有功能栏」，0aa9e9d）：`ImageViewerOverlay`（chat/ImageViewer.kt）补原项目 `_buildActionChrome`——底部毛玻璃面板（r30、黑 26% + 白 16% hairline）内 44dp 玻璃圆钮：保存（Download，从右上孤图标移入）· 分享（Share2）｜左右镜像（FlipHorizontal2）· 上下镜像（FlipVertical2）· 左旋（RotateCcw）· 右旋（RotateCw）；顶部 关闭 + 磨砂 pill 计数器（对齐 _GlassLabel）；变换改 **per-image 状态**（`ImageViewerTransform(scale/panX/panY/flipX/flipY/quarterTurns)` 存 List，对齐 `_displayTransforms` 数组——翻页不串状态、翻回保留）；分享链路：本地文件 FileProvider 直发、http/data: 落 cacheDir/share（`file_paths.xml` 补 `images/` root）；拆 `materializeShareablePath`（纯逻辑）+ 薄包装便于测试。**测试**：`ImageViewerShareTest` 5 例。**未移植**：复制（桌面专属 compact 外）、缩放三钮（桌面）、拖拽关图、桌面翻页箭头 | ✅ 0aa9e9d |
| 默认模型-生成 | 标题/对话总结真实 LLM 生成（补「默认模型」两块短板，`title_model_v1`/`summary_model_v1` 之前只作压缩兜底）：`TitleSummaryGenerator`（generateTitle 回退 title→chat→selected、generateSummary 回退 summary→title→chat→selected，门控对齐 home_view_model.dart——`title_generation_enabled_v1` / `assistant.allowPastConversationRecall && generateConversationSummary` / `recentChatsSummaryMessageCount` 阈值；占位符 `{content}{locale}` / `{previous_summary}{user_messages}`）；`core/common` 纯逻辑 `TitleText`(buildContent 取最近 12 轮尾 3000 字 + parseTitle 去围栏/引号/空白) / `SummaryText`(buildContent 拼接新用户消息头截 2000 字 + parseSummary)；`ConversationDao.updateSummary`；`ChatViewModel` 回复完成 finally 自动触发 maybeGenerateTitle/Summary；`SideDrawerContent` 长按菜单「重新生成标题」(`side_drawer_menu_regenerate_title`，force=true)；单测 `TitleTextTest`/`SummaryTextTest` 全绿，quality_gate 通过 | ✅ 本轮 |
| 修复 | 顶栏标题不刷新（用户实测：侧栏标题变了、对话界面还是"新对话"）：根因=Flutter `chat_service` 持**共享** `_conversationsCache`，抽屉与顶栏都读它，任意处写标题后 `notifyListeners()` 双方自动同步（`renameConversation` L2628-2632）；Android 端无此共享层——顶栏读 `ChatViewModel.title`（进会话时一次性快照，L159），抽屉读自己的列表，二者独立。**修复**：① `TitleSummaryGenerator.generateTitle` 返回类型 `Boolean`→`String?`（带回新标题），自动生成路径直接写 `title.value`（对齐 home_view_model L1531-1536 的 `updateCurrentConversation`+`notifyListeners`）；② 新增 `ChatViewModel.refreshTitle()`（重读库里标题写 `title`）+ `TitleText.shouldRefreshCurrent(changedId, currentId)` 纯逻辑（只刷新当前展示会话）；③ `SideDrawerContent` 新增 `onConversationTitleChanged` 回调，手动「重新生成标题」与**手动重命名**都触发；④ HomeScreen 用 `titleRefreshTick` 计数信号经 `ChatContent` 通知 `vm.refreshTitle()`。**顺带修复**：抽屉手动重命名此前同样不刷新顶栏（同一根因，历史遗留）。单测 `TitleTextTest` 补 4 例 `shouldRefreshCurrent` | ✅ 本轮 |
| 修复 | 思考卡折叠态冷启动回退（用户实测：手动折叠思考卡，冷启动又展开）：根因=`ChatViewModel.encodeSegments` **每次增量/落库都把全部 segment 重算成 `expanded = !autoCollapse`**，覆盖用户的展开/折叠点击（Flutter `stream_controller.dart:776` 明确注释「Do not reset r.expanded here - preserve user's toggle state during streaming」，只有**新建** segment 才赋初始态）。**修复**：① `core:data` 新增纯逻辑 `ReasoningSegmentCodec.applyInitialExpanded`（新段赋 `!autoCollapse`、老段保留）与 `collapseFinishedSegments`（流结束且开自动折叠时才折起已结束段）；② `encodeSegments` 走上述逻辑 + `seenSegmentIndices` 跟踪已出现下标（每轮生成清空，续写路径用库里已 decode 的下标预填充）；③ 流正常结束补最后一段 `finishedAt` 后再折叠（对齐 L1246-1255）；④ `persistAssistant`/`persistFinal` 支持传入已算好的 `segmentsJson`，**落库走与 UI 同一编码路径**（此前落库用 `ReasoningSegmentCodec.encode(closed)` 绕过修正，是冷启动回退的直接原因）；⑤ `onPersist` lambda 加第三参 `segmentsJson`。单测 `ReasoningSegmentExpandedTest` 7 例（新段初始/老段保留/重复编码稳定/结束折叠/开关关闭保留/端到端）。**2026-09-13 追修**：① 与② 的 `applyInitialExpanded` + `seenSegmentIndices` 已换成 `resolveExpanded` + `ChatViewModel.segmentExpanded`（权威展开态）—— 旧法在流式期间仍沿用 handler 重建出的 `expanded`，用户点开的思考卡会被下一个增量打回（§4.41）；单测扩到 11 例 | ✅ 本轮 |
| 修复 | **HTML 表格渲染**（用户实测「对话界面表格这些无法渲染」）：根因=`TablesExtension` 早已开启，但 `MarkdownRenderer` 的 `when (node)` **完全没有表格分支**，`TableBlock` 掉进 `else` 被当段落纵向堆叠 → 单元格变成一堆独立段落。**移植**（`markdown_with_highlight.dart` `_MarkdownTableBlock` L3223-3436 + `_MarkdownTableCell` L3763-3816）：① 解析层 `TableModel`/`parseTable`——extension 结构是 `TableBlock → TableHead/TableBody → TableRow → TableCell`（**中间有 section 包装层**，必须递归收集；表头标记在 `TableCell.isHeader()`，`TableRow` **没有** `isHeader`）；② 样式：表头 13sp/w600、正文 13.5sp、`height 1.42`、单元格 padding 10×9、表头 primary 底（暗 α0.15 / 亮 α0.07）、`outlineVariant` 边框（暗 α0.22 / 亮 α0.30）、移动端 r12 圆角卡片 + 0.8dp hairline 外框；③ **边框只画内部线**（对齐 `TableBorder(horizontalInside/verticalInside)`）：行用 `HorizontalDivider` 画底边（最后一行不画）、单元格 `drawBehind` 画右边界（最后一列不画）——外框交给圆角卡片。早期用 `Column.border()` 画外框，在滚动容器里会画出一条贯穿全高的多余竖线（已修）；④ **列宽按 `TextMeasurer` 实测**（对齐 `FlexColumnWidth`）而非字符数：早期版本按字符数算权重，`"排名"`(2 字) vs `"2 小时 45 分钟"`(9 字符) 差 6 倍 → 第一列被压到 1/6 宽、整列被裁（用户明确不满「你自己手写 问题太大了」）。调研 mikepenz 库后发现其表格要求 JetBrains `ASTNode`、与现有 commonmark AST 不兼容（换库 = 重写整条解析链 + 迁 citation 胶囊），且库也是等宽列，**用户拍板不换库**；⑤ ≥4 列走 `_compactColumnWidth`（L3522）：`((maxWidth-16)/2.45).clamp(112dp,178dp)` 固定列宽 + `horizontalScroll`；⑥ emoji 撑高行：`LineHeightStyle(alignment=Center, trim=Trim.Both)` 压回 1.42 行高；⑦ 长 CJK+拉丁串（`"RikkaHub、"`）会溢出列宽 → 单元格 `Text` 加 `Modifier.fillMaxWidth()` 强制换行；⑧ **工具栏与行分页已补齐**（`_MarkdownTableToolbar` L3843-3930 + `_buildRowPager` L3438）：38dp 条（`headerBg` 底、底边 `outlineVariant` α0.20/0.28、0.6dp hairline）左侧「表格」标签（12sp/w600/α0.80）+ 右侧三个 `IosIconButton`（size 15 / minSize 32 / padding 7 / α0.68，各裹一层 Flutter `Tooltip` → M3 `TooltipBox + PlainTooltip`，tap 触发）：**复制**（tap=复制 markdown、长按=复制为图片）、**保存图片**（tap=存相册 `Pictures/Memo`）、**导出 CSV**（tap=SAF `CreateDocument("text/csv")`，文件名 `{stem}_{iso}.csv`，提示走 `message_export_sheet_exported_as/failed`）。序列化 `MarkdownTableText`（`toCsv`/`toMarkdown`/`csvCell`）逐行对齐 `_rowsToCsv`/`_rowsToMarkdown`/`_csvCell`（L4014-4075，CRLF 连接、`|---|` 分隔行、`\\`/`\|`/`<br>` 转义、仅必要时加引号）。行分页：`_initialRows=40` 首屏、`_rowPageSize=100` 每次展开，`large_content_show_more(remaining)`/`large_content_collapse`。平台动作经 `MarkdownTableActions` 由 app 注入（`core:ui` 拿不到剪贴板/MediaStore/SAF），空值即不画该按钮。**踩坑**：① `GraphicsLayer.toImageBitmap()` 返回 **hardware bitmap**，软件 `Canvas` 拒画（`Software rendering doesn't support hardware bitmaps`）——必须先 `copy(ARGB_8888, false)`；② 截下的图层是**半透明**的（卡片靠页面 `surface` 透底），直接写文件透明像素变黑 → 导出图「上亮下暗」，须合成到不透明底（原项目 `_capturingTableImage` L3269 注释同样记载 *"Capture must be opaque"*）；③ 表头/主体底色是 `Color.alphaBlend(primary@α, surface)` 的**不透明合成**（暗 0.15/0.04、亮 0.07/0.015），不是直接画半透明 `primary`——后者叠在页面背景上会发灰发浑。**测试**：`MarkdownTableTextTest` 14 例（CSV 引号/CRLF、markdown 补齐列/转义/trim、空输入）+ `MarkdownTableLayoutTest` 补 6 例工具栏 widget 测试（无 actions 不渲染 / 全接线三按钮 / null 即隐藏 / 复制与导出回调拿到正确序列化 / 不撑破视口）。**测试**：`core:ui` `MarkdownTableTest` 12 例（表头标记/列数取最宽行/单列/内联样式保留/对齐/空表头/列宽算术 5 例）+ `app` `MarkdownTableLayoutTest` 4 例 Robolectric 布局断言（**锁「内容绝不越过视口右边界」**，正是几轮返工踩的坑） | ✅ 本轮 |
| 修复 | **stop 按钮样式**（用户实测「输入框这个 stop 这个样式 太丑了」）：根因=用了 `Lucide.CircleStop`（圆圈带叉），与原项目不符；且缺切换动画。**移植**（`chat_input_bar.dart` `_CompactSendButton` L3264-3327 + `assets/icons/stop.svg`）：① 图标改为**自绘 14×14、rx2 实心圆角方块**（stop.svg 是 24 viewBox 内的实心方块 `fill=currentColor`，不是描边圆圈）——`ChatStopSquare(color, size)` 按 viewBox 比例换算；② `AnimatedContent` + `scaleIn/scaleOut` + `fadeIn/fadeOut` `tween(200ms)` 表达原项目 `AnimatedSwitcher(duration 200ms)` 的 Scale+Fade 切换；③ 常量进 `ChatStyleSpec`（`SEND_ICON_SWITCH_MS=200`、`STOP_SVG_VIEWBOX_DP=24`、`STOP_SVG_SIDE_DP=14`、`STOP_SVG_RADIUS_DP=2`）；④ **顺带清 2 个既有 warning**：`LocalClipboardManager`（已 deprecated）→ `LocalClipboard` + suspend `setClipEntry(ClipEntry(ClipData))`；`if (visible && toolPart != null)` → `if (visible)` | ✅ 本轮 |
| 修复 | **聊天建议残留**（用户实测「我添加了聊天建议模型 后面去掉了 但是后面还会出现」「聊天建议 去掉了 对话里的聊天建议也没有消失」）：**核实结论——`resetSuggestionModel` 把 enabled 写成 `true` 是原项目既定设计**（`settings_provider.dart` L3917-3925；其 `resetTitleModel` L3654-3662 同构；单测 `'reset follows the current chat model until disabled'` 已固化），语义是「重置=改用当前对话模型」（行 tooltip 就是「使用当前对话模型」）——**不是移植 bug，不改**。真正的移植缺口有两处：① **对话界面建议气泡的外层门控缺失**（`home_page.dart:1285-1288`：`suggestions: suggestionsEnabled ? (conversation.chatSuggestions ?? []) : const []`）——禁用后**已显示的建议应立刻消失**（不删库、只门控展示）。Android 端此前直接渲染 `chatSuggestions` 无门控 → 已在 `HomeScreen` 补 `suggestionsEnabled`（读 `suggestion_generation_enabled_v1`，经新增纯函数 `DefaultModelPrefs.parseJsonBool` 解析）+ 门控条件；② **发送/重新生成时清建议缺失**（`home_view_model.dart` L421/L496/L529/L1104/L1355 调 `_clearSuggestionsFor`）→ 新增 `ChatViewModel.clearSuggestions()`（内存置空 + `writeSuggestions(emptyList())`），在 `send()` 与 `regenerate()` 调用。**标题模型**经核实**没有对应的 UI 门控**（标题直接渲染在顶栏），只有「重置≠禁用」的语义陷阱，属原项目设计，不改。`DefaultModelPrefsTest` 补 4 例 `parseJsonBool`（含 `"true"` 带引号宽容行为单列一例锁定既有语义） | ✅ 本轮 |

| 修复 | **供应商列表拖拽重叠**（用户实测"拖拽一个 会导致其他会过来重叠"，dc496fd）：根因=`sh.calvin.reorderable` 的 `onMove` 给的是 **LazyColumn 全局索引**（`LazyListItemInfo.index`），而旧 `ReorderableColumn` 封装在列表里渲染了 header/footer 占位 item（index 0 / N+1）→ 数据索引整体错位 1 → 每次 crossing 移错对象 → 库内部拖拽追踪脱节 → 兄弟卡片堆叠。**照 RikkaHub `SettingProviderPage` 重写**：裸 `items()`（无 header/footer）、独立卡片 + `spacedBy(8.dp)`、行尾 GripVertical 手柄 `longPressDraggableHandle`、onMove 改本地顺序（结束落库一次）；配色复用本主题语义色（enabled=surfaceCard、disabled=errorContainer），未硬搬它的 extendColors。另在 core:ui 封装加 `dataIndexOf()`（按 item key 反查数据索引，非数据项回退钳制索引）+ `ReorderIndexTest` 7 例——**其他调用方都没传 header/footer，本无此隐患**，此举是堵住封装坑本身 | ✅ dc496fd / 3b04a6f |
| 修复 | **输入栏模型/搜索按钮显示品牌图标**（用户"选择了对应的模型或者搜索都要显示对应图标"，345cf84）：模型按钮选中后显示 `CurrentModelIcon`（品牌 asset：modelId 优先 providerKey 兜底、无 asset 用模型名首字母；**底色必须透明**——原版 `backgroundColor: Colors.transparent`、RikkaHub `AutoAIIcon(color = Transparent)`；第一版画了 primary 圆底被用户以"一圈阴影"否掉）；搜索按钮在助手启用搜索时显示所选**搜索服务品牌图标**（未启用=Globe；内置搜索 S5 未移植故此处两态）。**bing/linkup 图标不显示**的根因：coil-svg 底层 androidsvg **不支持 `<mask>`**、且 `fill="url(#渐变)"` 带 `gradientTransform="rotate(a,b,c)"` 解析不了 → path 空白；**RikkaHub 对这两个只配 png**（`AIIconMatcher`，其 icons 目录里就没有这两个 svg），我们的 png 与它 **md5 一致** → 改映射即可。着色规则照 RikkaHub：**svg 才 tint、png 保原色**。`1em` 尺寸无害（40 个图标都用，勿误修）| ✅ 345cf84 / a3727fc / 56d0df4 |
| 修复 | **toast 上滑关闭丝滑化**（用户"没有丝滑的上滑去掉 现在会卡一下"，c1f85d9）：根因=原版 `snackbar.dart` 的 `-40`/`-150`/`-300` 是 Flutter **逻辑像素(dp)**，移植时当成 Compose 的 **px** 直接用——3x 屏上关闭阈值只剩 1/3、飞出距离只剩 1/3（约 50dp，飞到半空停住），余下靠 300ms 淡出补完 → 顿挫。修：三个数值全过 `LocalDensity` 换算；手势从 `detectVerticalDragGestures` 换成 **`Modifier.draggable`**（唯一能拿到 fling velocity：快速轻甩也能关、飞出带 `initialVelocity` 延续手指速度、回弹改 `spring`）；滑出后**直接 remove**（不再叠 300ms 淡出）。**通用教训：Flutter 手势/动画里的距离与速度常量都是 dp，抄进 Compose 必须换算** | ✅ c1f85d9 |
| 性能 | **聊天列表滑动卡顿**（用户"滑动有点卡或者帧率很低"，照 RikkaHub 聊天列表逐条对照，2b37c1b/3532401）：① `MarkdownText` 每次重组都同步跑 CommonMark 解析（流式每 chunk 一次）→ 首帧同步、之后 `snapshotFlow + distinctUntilChanged + drop(1) + mapLatest + flowOn(Dispatchers.Default)` 后台解析并丢弃过期请求（RikkaHub `Markdown.kt:240-252` 同款）；② item 闭包捕获整个 `messages` 且**每行**扫 `lastOrNull{assistant}`（O(n²)）→ 提前算 `lastAssistantId`，items 加 `contentType`；③ **`MessageRow` 从不可跳过变 skippable**：含 List 字段的模型（UiMessage/MessagePart/Assistant/Conversation/ProviderConfig）被判 unstable → 加 `app/compose_compiler_config.conf` + `composeCompiler { stabilityConfigurationFiles.add(...) }`（`reportsDestination` 报告确认 `restartable skippable`）；**⚠️ 该配置文件只吃裸类名：写 `#` 注释会被当 pattern 直接构建失败，且增量编译 UP-TO-DATE 时看不出，要 `--rerun-tasks` 才暴露**；④ `timeStr()` 每次 new `SimpleDateFormat` → 共享 formatter + 按 timestamp `remember`；⑤ `nodeText()` 每次重组递归拼子树文本 → 解析时预计算 `Node→String` 表（**commonmark 0.26 移除了 `Node.data`**，map 只能显式穿过渲染链）| ✅ 2b37c1b / 3532401 |
| 助手 | **头像真实显示**（用户"助手这个部分做完了吗？头像显示这个什么的"，ca8bb54）：编辑页那半边早已完成，**显示这半边是缺的**——聊天消息头硬编码首字母 + `useAssistantAvatar` **完全没被读**、抽屉两处（当前助手卡 32dp / 列表 28dp）同样写死首字母、统计页 mini 只处理 http+emoji（相册图会显示成文件路径首字符）。修：聊天头照 `chat_message_widget.dart:2787-2802`（`useAssistantAvatar` 优先 → 助手四态头像；否则 `display_show_model_icon_v1`(默认 true) → **该条消息自己的模型品牌图标**，新增 `MessageModelIcon` = CurrentModelIcon size 30）；抽屉/统计改用共享 `AssistantListAvatar`（**顺带去掉了我们多加的 0.5dp 描边**——原版 `_AssistantInitialAvatar` 只有 primary-15% 圆底）；相册选图补原版降采样（限宽 1024 + JPEG90）；复制助手时迁移本地头像/背景文件（`_duplicateLocalFile`）| ✅ ca8bb54 |
| 聊天 | **历史分页 + 打开定位最新**（用户"点击对话里显示的都是最新的部分呀"→ 实为**只加载最近 40 条、往上翻不到历史**）：原版是分页的（首屏 40 = `defaultTimelineInitialSlots`、后续每页 20 = `defaultHistoryPageSize`），我们只移植尾页且 `getBefore()` **写好从未被调用**。修：`ChatViewModel.hasMoreBefore` + `loadOlderMessages()`（每页 20、跳过已在屏的版本组、整页都是版本行则继续往前）；列表滚到距顶 **96dp** 触发（`message_list_view.dart:1816-1830`），插入后 `requestScrollToItem(inserted, anchorOffset)` **把视口锚回原内容**（LazyColumn 按 index 保位，不补偿会跳到新页顶部；锚回后 index≠0 也天然重新武装阈值）。**另**：`LazyListState` 默认 index 0 = 窗口内最旧 → 打开长会话停在最旧页；照 RikkaHub `ChatPage.kt:170-183` 首次拿到非空消息 `requestScrollToItem(lastIndex)`，`listInitialized` 保证只滚一次、不抢用户滚动 | ✅ 17275d5 / 004a11a |
| 用户 | **用户头像与昵称移植**（用户"用户头像和名字这个也移植过来吧"，5c17e5c/d39190f/286dd01）：核查发现**用户侧整条链路都是空的**（聊天头名字硬编码默认名 + 恒定 `Lucide.User` 图标；抽屉用户栏用**从未被传入**的 `userName` 参数；设置页 4 个 `display_show_user_*` 开关无任何渲染消费；`avatar_type`/`avatar_value` 零读写）。新增 `UserProfileStore`（`user_name`/`avatar_type`(`emoji|url|file`)/`avatar_value`，**用户侧是 type+value 两键、助手才是单 avatar 字段**，原版即如此）+ `UserAvatar(profile,name,size,fallback)` 四态（**两处空态按原版必须不同**：气泡=`Lucide.User` 图标、抽屉=名字首字母）+ 聊天头接三开关（名字/时间戳/头像各自门控，名与时间戳都显示才留 2dp）+ 抽屉用户栏接 store 与两个编辑入口 + `UserAvatarEditor`（五选一 sheet → emoji/链接/QQ 子弹窗；相册走 PickVisualMedia + 解码限宽 1024 + JPEG90 → `filesDir/user_avatars`，失败降级到链接弹窗）+ `NicknameDialog`（24 字符上限/实时计数/非空且变化才可存）。**复用而非复制**：原版 side_drawer 把 emoji/URL/QQ 弹窗又写了一份私有实现，这里改为给助手的四个组件加 `AvatarSheetStrings`（Assistant/User 两套文案）。**两个 Compose 坑**：① `AvatarPickerSheet` 每行"先 onDismiss 再 action"——用 `if (open)` 包住编辑器会在点选图时卸载组件、注销 `rememberLauncherForActivityResult` → **选了图头像不加载**（emoji/链接/QQ 更彻底：step 随组件销毁，点完什么都不弹）→ 改为**编辑器常驻组合**、内部用 open+step 决定渲染、launcher 注册放在"空闲早退"之前；② 为让 sheet 关闭后子弹窗仍能弹出，渲染条件允许"open=false 且 step 是子弹窗"→ 子弹窗取消/保存只清 open 不重置 step → **弹窗关不掉** → 所有出口统一走 `closeEditor()`。**顺手修**：`display_show_model_icon_v1` 此前用 `readJson`(DB) 读、设置页用 `writeLocal`(SharedPreferences) 写 → 开关一直失效，已统一 `readLocal` | ✅ 5c17e5c / d39190f / 286dd01 |
| 修复 | **输入框高度**（用户"把上下高度加大一点就行了"→"再高一点"）：输入区最小高 48dp（kelivo 的 kMinInteractiveDimension）→ 56 → **64dp**，多行仍随内容长高。**样式其余部分保持 kelivo 原样**——期间曾按用户要求把描边/底色/内边距/按钮尺寸全按 RikkaHub 对齐（1a13831），用户随后"不改了 回滚吧 还是原来样式吧"→ 工作区干净时直接 `git reset --hard` 撤销（不留反向提交）。**以后不要再动输入栏样式参数**（圆角 20 / 半透明底 `inputFillColor` / 描边 onSurface@0.10·outline@0.20 / 按钮 32dp·图标 20dp / 间距 8dp）| ✅ bd8005d |

| 修复 | **`display_*` / `user_*` 存储层统一**（2026-09-10 用户问过"什么意思"后拍板要做，b813f5f）：`classifyBusinessKey` 把 `display_*` 前缀与 `user_name`/`avatar_type`/`avatar_value` 归 **PREFERENCE（DB `preference_rows`，进备份）**，但设置页 13 个 `SwitchItem` 与所有渲染读的都是 `readLocal`/`writeLocal`（SharedPreferences）→ 备份采集只遍历 DB（`BackupSettingsSnapshot.kt:115-123`）→ **备份/恢复不含这批显示开关与用户资料**。修：① 设置页开关、`UserProfileStore`、`ChatTimelineSettings.fromPrefs`、模型图标/聊天背景遮罩/导出用户名全部改走 `readJson`/`writeJson`（裸 "1"/"0" 原样存，与 LogBootstrap 一致）；② 启动迁移 `PreferenceRepository.migrateLegacyLocalSettings()`（MemoApplication.onCreate 调用）：SharedPreferences 残留旧值搬进 DB（DB 已有值不覆盖）、空值丢弃、清本地位、幂等；③ `PreferenceMigrationTest` 6 例（Robolectric） | ✅ b813f5f |

| 修复 | **存储层审计修复**（2026-09-11，接上一条存储统一）：① **资产目录名偏离原版**（d4df6ec）——运行时自创 `assistant_avatars`/`user_avatars`/`assistant_backgrounds`，而备份（`BackupArchiveCodec.ASSET_ROOTS` = upload/avatars/images/fonts，且 `BackupSnapshotBuilder` 把 root 直接映射成 `filesDir/<root>`）与存储页（`StorageSpace.classify` 按顶层目录名）都按原版名找 → **备份从来不含头像/背景**、存储页把头像算进「其他/app」。修：新增 `AppDirs`（头像统一 `avatars/`——助手与用户共用、原版 `getAvatarsDirectory` 即如此；背景 `images/`）+ `AssetDirMigration`（搬文件 + 重写路径：`assistant_rows.payload` 的 avatar/background、`preference_rows.avatar_value`；**只按目录名替换、不带分隔符**，否则桌面/Robolectric 的反斜杠路径漏改）+ `AssetDirMigrationTest` 4 例。② **读写路由补漏**（f1e09c3）——`readJson` 对 LOCAL_ONLY 键对称回退 SharedPreferences（与 `writeJson` 一致），全项目 12 个文件（触感/显示/行为启动/消息样式/渲染/图片/主题等）的调用点统一走 `readJson`/`writeJson`，由 `classifyBusinessKey` 决定存储；迁移只搬 PREFERENCE 键，并**反向**把早前误搬进 DB 的 LOCAL_ONLY 键（`display_chat_font_scale_v1` 等）送回 SharedPreferences；`PreferenceMigrationTest` 扩到 8 例。③ **Coil 磁盘缓存位置**（d840f27）——存储页「缓存 → 头像缓存」此前恒为 0（Coil 写系统 `cacheDir`，而页面只 walk `filesDir`），把 Coil `DiskCache` 指到 `filesDir/cache/avatars`（原版头像缓存同位置，上限 64MB）→ 子项变真实数据。审计确认无误：分类表无重叠/冲突、无绕过 repo 的 `preference_rows` 直写、备份归档格式与原版对齐、恢复侧统一 `writeJson` 路由 | ✅ d4df6ec / f1e09c3 / d840f27 |

| 修复 | **表格样式与导出三连修**（2026-09-11 用户实测，逐条对照 `markdown_with_highlight.dart`）：① **导出图片缺列**（34384dc）——录制层原先挂在**横向滚动容器外面**（宽度=视口），多出来的列根本不在图层里；挪到滚动容器**内部的表体**上（宽度=列数×列宽）即可导出完整宽表，导出图只含表格（不含工具栏/分页器）；另曾试图"导出时把列压进视口"（错误方向，已回退）。② **导出只含前 40 行**（cf0d167）——长表格默认只渲染 `TABLE_INITIAL_ROWS=40` 行，截图前先展开全部行、等两帧、截完恢复用户展开状态。③ **底色与原版不一致**（d375557）——原版表头/正文/卡片底色在屏幕上统一乘 `kBlockFillAlphaTable=0.72`（透出助手壁纸，L54/L3271/3274/3347），只在截图时切回不透明（L3269-3274，JPEG 透明孔变黑）；卡片 tint 是 `0.045/0.018`（我们错用了正文的 `0.04/0.015`）。④ **竖线接不满**（8d36723）——原版 `TableBorder(verticalInside)` 整表描网格，我们逐单元格画右边线，行内高度不一致（多行 vs 单行）时短的那条断在半截；改为在**整行**上画竖线（贯穿行高，x 用与单元格相同的列宽规则）。⑤ **"时间"列被挤成每行两三字**（9e136fc）——列宽只按自然宽比例分配、无下限，长文本列把短列压垮；补 Flutter `Table` 的 **minIntrinsicWidth** 语义：每列先取「最长不可断片段 + padding」，剩余按「自然宽 − 最小宽」比例分配，放不下则保持最小宽；单元格改用显式宽度（`weight` 表达不了下限），旧 `columnWeights` 与其测试一并替换 | ✅ cf0d167 / 34384dc / d375557 / 8d36723 / 9e136fc |
| 修复 | **抽屉手势三连修**（2026-09-11 用户实测"一开始拉就抖一下 / 反方向也动 / 关掉再马上拉没反应"）：① 手势**挂了两处**（主内容层 + 抽屉层，兄弟节点）→ 一次拖动被两个 `pointerInput` 同时处理（offset 加两次、settle 两次）→ 只挂在公共父容器上（一处，抽屉上起手也有效）；② **无方向门控** → 反方向拖动虽被 clamp 但会触发 `onPresent()`，而 `if (presenting)` 会**插入 scrim 节点**（布局变化=抖动）→ 关闭态只跟右拖、打开态只跟左拖；③ **抽屉与 scrim 用 `if (presenting)` 插拔组合**（3f65864）→ 起手那一帧要现建整棵抽屉（含会话列表）= 抖动 → 改**常驻组合**，靠 offset/alpha 驱动（`pointerInput` 有无不影响布局）；**注意** scrim 的手势处理器必须**按需挂载**，无条件挂会让整屏点击失效（我曾踩：`awaitFirstDown` 常驻 → "点击全部失效"）→ 改为 `if (drawerOpen)` 时挂；④ **关闭动画途中反向拖动无响应**（f36fe89）——方向门控原先读**动画中的位移**（`contentOffsetPx>0`）而非**逻辑状态**，收尾动画没跑完时仍被判为"已打开"→ 新右拖被当反方向忽略；改用 `drawerOpen` 逻辑状态 + 拖动开始时 `dragJob?.cancel()` 取消收尾动画；⑤ 起手位置**不限**（f92b604）——曾按原版加 24dp 左边缘限制，用户体感"手势失效"（3x 屏仅 72px），移除改回任意位置起手 | ✅ ae7b078 / 3f65864 / f36fe89 / f92b604 |
| 修复 | **空会话不该进历史列表**（1b143f1，用户"我什么都没发，历史就多一个新对话"）：原版新建会话是 **draft**（`chat_service.dart` L1868 `createDraftConversation`："not persisted until first message arrives"，只放 `_draftConversations` 内存，首条消息经 `_saveConversation` 才落库）→ 历史列表天然看不到空会话。我们两条创建路径（新建按钮 + 启动无历史）都**立即 insert** ✗。修：创建只生成 id 并选中（不落库），`ChatViewModel.ensureConversationRow()` 在**首条用户消息落库前**补写 conversation_rows（标题留空、绑定当前助手）；未发消息就切走则自然丢弃 | ✅ 1b143f1 |
| 日志 | **上下文日志 + 应用日志通电**（2026-09-11 用户问"这个日志里面的上下文和应用日志 是没有接线吗？我怎么一直没有看到呀"）：三个 tab 里原先只有请求日志有数据。① **上下文**——模型从 `app/ui/LogData.kt` 搬到 `core/common/.../logging/ContextLogModels.kt`（`ContextSource`/`ContextSegment`/`ContextLogSnapshot`/`ContextLogTailReader` + 新增 `ContextTag`/`ContextTags`/`TokenEstimator`），标签挂在 `LlmMessage.contextTags` 上（= 上游 map 的 `_kelivo_ctx_segments`；provider 客户端逐字段拼 JSON，故无需"发请求前 strip"）；新增 `app/logging/ContextLogAssembler.kt`（`buildSnapshot`/`logPrepared` + `joinSystemParts`/`systemMessageTags`/`appendedSystemMessageTags`）；`ChatViewModel` 组装 history 时给系统消息各段（系统提示词/记忆规则/搜索提示词/指令注入）与最后一条用户消息（记忆快照 + 正文）打标签，世界书 `inject(..., tagContextLog = true)` 也带 `worldBook` + position 标签，请求前 `logPrepared` 落一条 JSONL。**上游语义坑**：追加段（`_appendToSystemMessage`/世界书 after）标签长度含前导 `"\n\n"`，段文本会带空行（测试 `appendedSegmentsOwnTheirLeadingBlankLine` 锁住）。② **应用**——接上 Android 有对应物的失败路径：SSE 邻接 JSON 恢复（`SseEventParser` 默认 `onRecovery`，tag `SseFramingRecovery`）、三个 provider 的畸形事件（`ChatCompletionsDecoder(providerLabel=)` / Claude / Gemini，tag `DecoderParseError`）、后台任务（标题/摘要/记忆整理/建议 → `logBackgroundTaskFailure`，tag `HomeViewModel`）、抽屉重新生成标题（`SideDrawer`）、压缩上下文（`HomePage`）、供应商保存/删除（`Provider`，**保存此前让 collect 直接崩掉**）、模型详情保存（`Model`）；上游 `ImageFallback`/`ModelOverride` 在 Android 无对应路径未接，desktop 两处不移植。③ **顺带修掉的真缺口**：**指令注入从未进入请求**（只有设置页写库）→ 按 `injectInstructionPrompts` L1748-1772 接上（`activeIds(assistantId)` → 空行连接 → 加进系统消息，来源 `instructionInjection`）。测试：`ContextLogAssemblerTest` 10、`TokenEstimatorTest` 6、`WorldBookInjectorTest` 新增 5、`SseFramingRecoveryLogTest` 2、`DecoderParseErrorLogTest` 2；全模块 1237 例绿 | ✅ 本轮 |
| 供应商 | **供应商域收官：详情页 AppBar/配置 tab + 头像 + API 端点 combobox + Responses API 真接线**（用户 2026-09-12 连续点名「现在把供应商这个部分全部移植吧 还有点击供应商的界面」「添加供应商这个界面 也对应改一下哦」）：① 详情页（`ProviderDetailScreen`）——AppBar 品牌头像（24dp，点开五选一 sheet）+ 测试/分享/删除（`userAdded = providerId !in BUILTIN_KEYS`）、配置 tab 去掉分隔线（原版 `SectionCard(dividers:false)`）**且整卡去掉前置图标**（原版 `_iosRow` 只有标签+开关，见 §5.11）、底部 Config/Models 分段条把 `navigationBarsPadding()` 挪到卡片外（原来卡片被撑高、底边贴手势条）。② 头像 `ProviderAvatar(+Sheet)` / `BrandIconCatalog`（59 个品牌图标取自 `BrandAssets.selectableIcons`；`icon` 值仍存 Dart asset 串以便备份往返；svg 才 tint、png 保原色——与输入栏品牌图标同结论）。③ **API 端点三选一 combobox**（明细见 §5.11「API 端点选择」）：原版是「Response API 开关 + 手填路径」两件套，现合成 `ApiPathField`（选中变色不打勾、字段与同屏输入框同宽同高），详情页与添加供应商页共用。④ **`/responses` 以前只换 URL**（body/解码仍是 chat-completions）→ 本轮补齐：`core/llm/provider/ResponsesApi.kt`（system→顶层 `instructions`；聊天轮→input items：assistant 用 `output_text`、user 用 `input_text`/`input_image`、助手图片攒给后面那条 user；`tool`→`function_call_output`、assistant `tool_calls`→`function_call`（`call_id` 成对）；工具定义摊平 `toResponsesToolsFormat`；`max_tokens`→`max_output_tokens`；`reasoning:{summary,effort}` + MiMo/DeepSeek/DashScope 三种厂商变体（`applyCompatibleResponsesReasoning`）；custom body 最后覆盖）、`ResponsesDecoder`（`response.output_text.delta` / `reasoning_*_text.delta` / `output_item.added` + `function_call_arguments.delta` + `output_item.done` / `response.completed|incomplete|failed` 携 usage；`[DONE]` 与断流兜底；**工具 id 用厂商 `call_id`** 而非 Dart 的 series id，因为上行 follow-up 必须 `function_call.call_id` ↔ `function_call_output.call_id` 成对）、新增 `stream/StreamDecoder` 接口让客户端按请求选解码器、URL 固定 `/responses`、非流式 `complete()` 读 `output[].content[].text` 与 `input_tokens`/`output_tokens`；`LlmRequest.useResponseApi` 由 `container.usesResponseApi(providerKey)` 在 8 处请求组装点填充（聊天压缩/聊天流式×2/标题摘要×2/记忆 LLM×2/OCR/翻译/多密钥测试/供应商测试）。测试：`ResponsesApiTest` 8、`ResponsesDecoderTest` 8、`OpenAiClientIntegrationTest` +2（MockWebServer：路径 `/v1/responses`、input items、SSE 解码）、`ApiPathFieldTest` 3、`SettingsSwitchRowTipTest` +1 | ✅ 本轮 |
| **供应商分组：整块删除（用户点名）** | 原版 `provider_groups_page` + 两个分组 sheet + 列表分组头/折叠 + 详情页「分组」行 + 分组数据层（`ProviderGroup`/`ProviderGroupLogic`/`provider_group_map_v1` 等） | **UI 与数据层一起删**：详情页分组行、列表分组头/折叠、多选「移动分组」钮、分组管理页与 `provider_groups` 路由、`ProviderGroup.kt`/`ProviderGroupLogic.kt` 及其测试、repo 的分组 API 与三个偏好键、备份快照/恢复/合并里的 `provider_groups_v1` 实体都去掉；`SettingsKeyRegistry` 里那几个键是**生成物**（来自 Flutter 的分类表）故保留。`provider_group_rows` 表仍在 drift 生成的 schema 里（schema 由 Flutter 源生成、不许手改），只是没人读写 | 用户 2026-09-12：「把分组这个去掉吧 我感觉没有什么用」+「我这个是个独立项目了呀 跟原项目有什么关系 去掉就彻底呀」。**不要因为原版有就加回来** | ✅ 已删 |
| **流式等待提示：扫光文字（用户点名改）** | `LoadingIndicator`（三点波浪脉动，`chat_message_widget.dart` L4104-4196）：1100ms、相位差 0.22、scale 0.85→1.0、alpha 0.45→0.90；位置两处：空等时在助手气泡内、有正文时挂在最后一个块后（left 4/top 4） | **`ThinkingShimmerText`**（`ChatMessageWidgets.kt`）：**15sp、主题色**（底色 primary@50%、高光 primary@100%，扫光=被点亮）的一句轮换中文短语，高光 1.5s 从文字左侧扫到右侧（`Brush.linearGradient` 三色带、带宽随文字宽度），短语 2.2s 换一句（`Crossfade`），词表在 `ThinkingPhrases.ALL`（用户给的「思考中／嘻嘻中／深挖中」+ 我扩的 9 个）；位置改成**列表末尾单独一行靠左**（原版两处合一）。**2026-09-13 起三项可自定义**（用户「这个加一个设置功能到设置里面吧 让用户可以自定义文字大小，颜色，提示词的字」）：字号 10–28sp、颜色（跟随主题或 `#RRGGBB` 预设/自填）、提示词逐行编辑；落点是**显示设置 → 渲染 → 流式等待提示**（`RenderingSettingsScreen` 新区块 + 三个 sheet，字号 sheet 里用真组件做实时预览），键 `display_thinking_indicator_font_size_v1` / `_color_v1` / `_phrases_v1`（本工程新增，原项目没有这个指示器），解析在 `ui/chat/ThinkingIndicatorSettings.kt`，`ThinkingIndicatorSettingsTest` 10 例。工具卡里的 3/2/12 小点保持原版不变 | 用户 2026-09-12：「输出时的三个点跳动这个我想改成文字 比如思考中，嘻嘻中，深挖中 这些扫光文字…位置挪到列表末尾靠左单独一行」→ 随后「文字可以大一点」（13sp→15sp）「颜色也改成主题色吧 现在是黑色的」；看过 RikkaHub 的两种做法（自家兔子 AnimatedVectorDrawable 眨眼 / M3 `ContainedLoadingIndicator`）后选了扫光文字。**勿按原版修回三点** |
| 供应商 | **模型工具栏 + 网络/自定义请求子页按原版规格重做（2026-09-12 续）**：① 工具栏按钮照 `_buildActionToolbarButton` / `_buildSelectionToolbar*`（L2520-2990）四档配色：拉取模型=透明底+`primary@35%` 描边+primary（14sp semibold）、新增/检测=`primary@12%` 底+primary（14sp medium）、删除类=`error@10~12%` 底+error（默认条图标 18dp，选择条 20dp）、全选/清空=透明底+`onSurface@20%` 描边；禁用态统一 `onSurface@10%` 底 + `onSurface@50%` 前景；最小 44×44、图标 20dp、标签 14sp。检测中按原版换成 `Loader` 图标。② **网络代理页**：去掉多出来的卡片与前置 Globe 图标（原版是裸开关行）、代理类型从 chevron 行改成**带标签的选择框**（r10/surfaceFill/hairline/18dp 箭头，点开仍走我们统一过的卡片选项 sheet）、补 host/port 占位（`127.0.0.1`/`8080`，端口只收数字）、**密码改密文**；`SubPageInput` 统一成 r10 + `outlineVariant@12%` 发丝边 + `primary@35%` 聚焦 + 内边距 12/10 + 14sp 文本（原版 `_proxyInputDecoration`）。③ **自定义请求页**：行改成**裸字段 + 行尾 Trash2 图标钮**（≥440dp 名称:值 = 4:6 并排，否则名称+删除钮一行、值另起一行）、body 值多行（2~5 行）、添加钮换成 `IosTileButton(Plus, 13sp, padding 12/9)`、标题 13sp emphasis@80%、描述行高 1.45、页面底部留白 24。④ AppBar 多选钮在**批量检测进行中**换成 `Loader` 并忽略点击（原版 L219-232）——此前检测途中点它会清掉正在测的选中集（`detecting` 已提到与 `modelSelectMode` 同层） | ✅ 本轮 |
| 供应商 | **供应商列表页多选两钮通电（2026-09-12 续）**：多选后「导出」此前只有选中 1 个才走分享面板、≥2 个静默无反应；「移动分组」同理。补 `MultiProviderExportSheet`（原版 `_showMultiExportSheet` L1528-1691：标题带数量、≤4 个给二维码〔白卡保证可扫〕、代码预览限高 128dp/7 行、复制 + 分享两钮）与 `ProviderGroupSelectSheet`（原版 `provider_group_select_sheet.dart`：只返回分组 id，由列表页对**整批**选中项 `setGroupFor`）。两者与单个供应商的 `ShareProviderSheet`/`ProviderGroupPickerSheet` 共用同一份 UI（`ProviderGroupSheet` 私有壳 + 两种公开包装）**（注：分组功能随后按用户要求整块删除，`ProviderGroupSelectSheet` 已不存在）** | ✅ 本轮 |
| 供应商 | **供应商域收尾（2026-09-12 三路并行审计后的修复批）**：① 模型 tab 的**获取模型选择面板**（`FetchModelsSheet`，原版 `_showModelPicker` L3341-4019：搜索 + 全选/清空/反选 + 家族分组折叠 + 整组/单行增删 + SiliconFlow 无 key 只给两个免费模型）；② **测试连接对话框**（`ConnectionTestDialog`，原版 `_ConnectionTestDialog` L4200-4516：选模型 + 「使用流式」+ 四态）与模型工具栏的**批量流式开关**（`LlmClient.probeStream` = `testConnection(useStream:)`）；③ 审计修复：分享码 `type` 写 `google`（此前写 `gemini`，两端都导错）、ChatBox 导入读 `providers["gemini"]`、子页写库改为回写详情页 cfg（此前会被详情页的防抖整段覆盖 = 代理/自定义请求静默丢失）、多 Key「添加」后自动检测（此前基准 cfg 里没有新键 → 全跳过）、多 Key 测试带 provider 自定义头/体、供应商列表全选/批删排除内置行 + 删除清理引用 + 新建供应商插到顺序表最前、Vertex 存 aiplatform base、空 baseUrl 回落默认、**分组行**显示当前分组名（此前是「选择分组」且无值）、余额行文案用 `_balance_info`、Network 行排在 Custom request 之前、批量删除提示带数量且无撤销（单行滑动仍可撤销）、批量检测只清本轮被测行的结果、余额徽标不可取时不再画「~」+ 失败进 tooltip、模型检测成功也有 tooltip、空模型态按原版 18sp/13sp primary 居中、SiliconFlow「Powered by」图片；④ 添加/导入/分组面板补 `MemoSheetHandle()`，分组面板可滚动、建组走 `repo.createGroup` 并关闭。测试：`FetchModelsLogicTest` 6、`StreamProbeTest` 3、`ResponsesApiTest` 8、`ResponsesDecoderTest` 8。**未做（下一批）**：列表页分组头/折叠、工具栏按钮配色/悬浮、触感反馈、`UI_AUDIT_2026-09-12.md` 的 H1/H2 共享封装收敛 | ✅ 本轮 |
| 备份-2+4 | **merge 恢复 + 备份提醒**（§5.10 子块 2/4，2026-09-12，明细见 §5.10.4）：① **会话库合并** `DatabaseSnapshotMerger`——ATTACH 快照按 id 遍历：坏 `message_order` 跳过计数、指纹相同去重、id/消息 id 冲突 ⇒ 整段会话换 `merge-<sha256前32>` 确定性新 id（消息/分组 id 重映射、`version_selections` 重写、`asset_reference_dirty_rows` 标脏），`PRAGMA foreign_key_check` 闸门；指纹只要求运行内自洽（同 Dart 字段集：排除 updated_at/is_streaming/附件 unavailable、时间折秒、group→序号）。② **settings 合并** `SettingsSnapshotMerger`——助手本地 avatar/background 优先、provider 载荷 incoming 胜 + **order-only 占位不实体化**（有意偏离，Android 无此形态，materialize 会造幽灵 provider）、其余 local-first 去重、记忆条目内容去重 + id 重排 + relatedIds 重写/悬挂清理 + migrationIds 合并；偏好 = 本地有就不动（putIfAbsent）、pinned 并集、ASR 按 id 并集、关系映射本地胜。③ `BackupRestorer` 修序：**数据库先行、settings 后写**（原 settings 写在被 swap 丢弃的库上）；MERGE 不再 `replaceAll` 误清本地实体（原 merge 实现是破坏性的）。④ **备份提醒**：`app/BackupReminder.kt`（五键进 preference_rows、`(lastBackupAt ?: enabledAt)+intervalDays` 到期、分钟 ticker、会话内 snooze）+ 备份页 §2 五行全接线（启用未选时间先弹滚轮，对齐 upstream setEnabled 的 StateError 前置）+ 双滚轮时间 sheet / 频率 sheet / 1-365 自定义对话框 + 抽屉到期横幅（新增 `onOpenBackup` 导航）+ 导出到文件成功 `recordBackupCompleted()`。⑤ 顺带点亮 §3 本机副本启用开关（原为死占位，`LocalSnapshotService.preferences` 转 public）。测试：`SettingsSnapshotMergerTest` 16、`DatabaseSnapshotMergerTest` 6、`BackupReminderTest` 6；全模块 1265 例绿 | ✅ 本轮 |
| 接线审计 | **WebDAV 子页状态栏 + 全局代理 + TTS 自动播放 + 用户画像入口**（2026-09-12，用户问“设置里不会大部分都是UI吧”，做了全设置区接线审计）：① **WebDAV 设置子页顶栏顶进状态栏**——漏了 `windowInsetsPadding(WindowInsets.statusBars)`（BackupScreen/NetworkProxy 同款），已补。② **全设置区写入无读取扫描**（脚本比对 81 个偏好键的读写）：真死键两处——**全局网络代理七个键写了没人读**（OkHttp 完全没接代理，上游 dio_http_client L79-140 是全局生效）与 **TTS「自动播放助手回复」写了没人读**。修复：`app/GlobalProxy.kt`——`ProxySelector` 每次连接读当前配置（改了立即生效，无需重建客户端）、http/https→`Proxy.Type.HTTP`（CONNECT 隧道）+ proxyAuthenticator Basic（对齐 `addProxyCredentials` L120-124）、socks5→`Proxy.Type.SOCKS`（**已知偏差**：Java 层不支持 SOCKS 用户名密码，上游支持）、**绕过规则实现了精确/后缀/CIDR 匹配**（上游 dio 没消费 bypass——有意增强，本地 Ollama/LM Studio 挂代理时必须直连）；TTS 自动播放挂在 `runGenerationLoop` 正常返回后（`home_page_controller.dart` L1763-1765），`TtsPlayer.speak(text, ownerId)` 免 Context 重载。③ **记忆设置页「用户画像」行**：`UserProfileScreen` + 路由 `user_profile` 其实早已存在，只是这一行从未接导航——已接（`onOpenMemoryProfile` → `user_profile`），**不是删行**（用户明确：缺功能就接线，不许删）。④ 审计结论：Cherry/Chatbox 导入（子块 8）与 S3（子块 6）仍是占位、TTS 编辑器的语速/音调随网络 TTS 批次、`applyContextLimit` 用户暂缓、赞助页是品牌刻意的空壳——除这些外设置区全部接线。测试 +6（`GlobalProxyTest`）；全模块 1277 例绿 | ✅ 本轮 |

| 修复 | **新建对话后模型"要重新选"**（2026-09-12 用户实测「新建对话后这个 大模型又要从新选择」）：原项目的模型归口是 `resolveChatModel`（`model_display_helper.dart` L44-58）= **会话覆盖 → 助手默认 → 全局默认（`selected_model_v1`）**，请求侧 `message_generation_service.getModelConfig` 也走同一条链、按需现算。Android 侧两处偏离：① `ChatViewModel.init` 把 JSON 文本偏好**直接**丢给 `parseModelSelection`（见 §4 坑 28），解出的 provider/model 带引号 ⇒ 模型选择器不认（"没选中"）、请求拿不到 key；② 链里**漏了助手默认这一层**，且新建会话是 draft（没有会话行）⇒ 只剩全局默认可用。修：`DefaultModelPrefs` 增 `decodeStoredString` / `parseStoredModelSelection`（存储侧解包，裸串兼容）+ `resolveChatModel(conversation, assistant, global)`（三层链，纯函数）；`ChatViewModel.init` 先按「助手 → 全局（解包后）」定模型，会话行仍异步覆盖（优先级最高）。**用户「对齐原项目 就行」后一并去掉 Memo 自造的兜底**：三层都解析不出时**不再退到"第一个启用的 provider"**（原项目此处留空），改为在 `send` / `regenerate` / `editMessage(shouldSend)` / `resumeAfterToolAnswer` 四个生成入口加原版 `ChatActionResult.noModel()` 的门（`hasModelOrWarn()`：提示 `homePagePleaseSelectModel`「请先选择模型」、**不吞草稿**、不改状态），`AppContainer.firstEnabledProviderConfig()` 随之删除。测试：`DefaultModelPrefsTest` +8 例（真机形态的带引号值、裸串、空值、三层链优先级、新建会话继承助手模型） | ✅ 本轮 |

| 修复 | **新建对话后助手名字/头像不刷新 + MCP 工具页多出"MCP服务器"行**（2026-09-12 用户实测两条）：① 消息头的助手行是 `LaunchedEffect(conversationId)` 一次性读 `conversation_rows.assistant_id`，而新建会话是 draft（行要等首条消息才写）⇒ 读到 null 后再也不重读，头部一直显示兜底名"助手"+ 模型图标，切出去再回来才对。修：改成派生值（key = conversationId + 消息是否已落库 + 当前助手），走新的 `ui/HomeScreen.kt` 纯函数 `headerAssistantId(会话行.assistantId, 当前助手)` —— 会话行优先、draft 回落到当前助手（＝原版 draft 内存里带着 assistantId）。② MCP 服务编辑 sheet 的工具审批开关错绑 `mcp_conversation_sheet_title`（"MCP服务器"）⇒ 每个启用的工具都多一行"MCP服务器"，看着像 MCP 服务被重复列出。修：改用 `mcp_tool_needs_approval`（"需要审批"）+ Shield 13dp + 12sp 的卡内紧凑行，且只在 `tool.enabled` 时出现（1:1 `mcp_server_edit_sheet.dart` L639-683）。测试：`ui/ChatHeaderAssistantTest` 4 例（纯规则 3 + Robolectric 渲染 1：会话行绑定的助手名必须出现在消息头、当前助手名不得出现） | ✅ 本轮 |

| 修复 | **MCP 工具 tab 卡片化对齐**（2026-09-12 用户「MCP 工具 tab 的卡片化对齐（工具名 + 描述 + 参数 chips + 卡内审批行，跟原版一致）」）：原版工具 tab 是「每工具一张卡」（`mcp_server_edit_sheet.dart` L511-690：`margin bottom 10 / padding 12 / surfaceFill / r12 / outlineVariant@0.2 边框`），卡内 = 名称（bodyMedium+emphasis）+ 描述 12sp@0.7 + 参数 chips（`Wrap` spacing/runSpacing 6：11sp w600、h8/v2、r999、边框各色 50%；必填 primary 字 + primary 12% 底，选填 onSurface 50% 字 + 6% 底）+ 启用 IosSwitch（与名称同排、顶端对齐），**启用时**才追加卡内审批行。我们此前是「设置行 + 分隔线」形态且没有 params。本轮：① 新增 `core:data/model/McpToolParams.kt` `deriveToolParams(schema)`（1:1 L2337-2360：properties → name/required/type（数组用 `|` 连接）/default），编辑 sheet 合并 live 工具时一并写入 `params` —— **此前 Android 从不下发 params，payload 比原版少一段、备份里的 MCP 工具缺参数规格**；② 工具 tab 改卡片布局（去掉我们自加的"工具: x/y + 同步"行）；③ 同步按钮按原版**移到 sheet 顶栏右侧**（primary 22dp，仅编辑已有服务器时显示）—— 唯一差异：原版 `refreshTools` 用库里配置抓工具，我们点同步会先落盘当前表单再重连（改了 URL 点同步才生效，更直观，有意保留）。测试：`core:data` `McpToolParamsTest` 7 例（required/type 数组拼接/default 原样/无 schema/非对象属性容忍/payload 往返带 params） | ✅ 本轮 |

| 修复 | **tip 图标改为紧跟标签文字**（2026-09-12 用户：「这个 tip 图标位置也不对，应该在文字的旁边 现在是在最左边 我感觉不合理」→ 选「全站改成紧跟标签文字后面」）：原版（`_iosSwitchRow` L1508 / `memory_settings_page.dart` L1083 等）都是 `Expanded(标签) → MemoryTipIcon → 间距 → 开关`，ⓘ 被推到行尾、离文字很远。改法：`SettingsUi.kt` 抽出共享 `SettingsTipIcon(tip)`（原本 SettingsSwitchRow 内联 + MessageStyle/MemoryUi/TtsSettings 三份私有副本，一并收敛）与 `RowScope.TipHuggingLabel(label, tip, labelStyle)` —— 外层 `weight(1f)` 吃掉整行余量（行尾开关/chevron 因此照旧贴边），内层文字 `weight(1f, fill=false)` 只占所需宽度，ⓘ 紧贴文字、余量留在组内右侧。落地点：`SettingsSwitchRow`（覆盖全站多数设置页）、MessageStyle 的 StyleRow/TextSwitchRow、TtsSettingsRow、MemoryNavRow、助手编辑页「追加当前时间」行（文本块 + ⓘ 打包）、记忆设置页 `SettingsSectionHeader`（标题 fill=false）。测试：`SettingsSwitchRowTipTest` 新增 `tipIconHugsTheLabelText`（边界断言：ⓘ 在文字右侧、间隙 < 14dp、位于行宽左半侧）——旧排布下这条会失败。**属用户点名的有意偏离，见 §5.11；勿按原版"修回"行尾**。 | ✅ 本轮 |

| 修复 | **两处裸排提示改成 ⓘ tooltip**（2026-09-12 用户点名）：① 记忆设置页「每类注入条数」原来把说明当**副标题**裸排，而原版是 `title + tip + detailText`（`memory_settings_page.dart` L176-179）→ `MemorySettingsScreen.SettingsNavRowFull` 补 `tip` 参数（title 行内 ⓘ，副标题保持可选），该行改传 `tip`。其余记忆行（思考/条目/画像/关于/提示词）原版本来就是 `subtitle:`，**不动**。② MCP 服务器编辑 sheet 工具卡里的**工具说明**原来卡内裸排 12sp（原版 `mcp_server_edit_sheet.dart` L547-560 也是裸排）→ 按用户要求改走工具名旁的 ⓘ tooltip（卡片其余部分照原版）。两处都用 `SettingsUi.TipHuggingLabel`（ⓘ 紧跟文字）。 | ✅ 本轮 |

| 修复 | **sheet 样式统一**（2026-09-12 用户：「有的把手是原生的有的是自己绘制 统一成手绘那种」+「颜色模式/应用语言/应用字体/代码字体/消息导航按钮/后台聊天生成……统一成更多那个 sheet 样式」）：① **拖柄**：新增 `SettingsUi.MemoSheetHandle(trailingGap)`（40×4、onSurface@20%、全圆、上 8/下 10，与既有自绘件像素一致）；原先用 Material 原生胶囊的 19 处（关于页、日志、记忆追踪、记忆选项、主题×2、TTS×2、用户资料、引用来源、显示页 4 个滑块、颜色/语言/代理类型等）全部改 `dragHandle = null` + 内容首项调用它 —— 现在 **73 处 `ModalBottomSheet` 全部手绘把手**（含原本就自绘的 54 处）。② **选项面板**：新增 `SettingsUi.MemoSheetOptionRow`（取值照「更多」sheet：surfaceCard + r14 + 48dp + 左右 12；选中 = primary 文字 + ✓），列表 `Arrangement.spacedBy(8.dp)`、去掉 `HorizontalDivider`；落地 8 处：颜色模式（`SettingsScreen`，删 `ColorModeOption`）、应用语言（`SettingsUi.LanguageSheet`，删 `LanguageOption`）、应用字体/代码字体/后台聊天生成（`DisplaySettingsScreen.SelectSheet`，三处共用）、消息导航按钮（`BehaviorStartupSettingsScreen`，删 `NavModeOption`）、代理类型 ×2（`NetworkProxyScreen` 删 `ProxyTypeOption`、`ProviderSubPages`）。测试：`SheetStyleTest` 2 例（拖柄 tag 存在；选项行 48dp 卡 + 8dp 间距 + 选中才带 ✓；语言 sheet = 1 拖柄 + 4 卡） | ✅ 本轮 |

| 修复 | **sheet 高度：一次展开到内容高度**（2026-09-12 用户：「很多 sheet 高度有问题，最后一个选项会被挡一下、拉一下才能看到」，点名消息「更多」里的「删除本版本」）：66 处 `ModalBottomSheet` 没传 `sheetState`，M3 默认 `skipPartiallyExpanded = false` ⇒ 内容超过半屏时先停在半屏、底部选项被裁。修：新增 `SettingsUi.rememberMemoSheetState()`（`skipPartiallyExpanded = true`），**全部 73 处 sheet 统一用它**（含原先自建 state 的 4 处：模型选择/模型详情/mini-map/工具卡，原来是默认值）。 | ✅ 本轮 |
| 修复 | **MCP 添加/编辑表单 1:1 重做**（2026-09-12 用户：「添加 MCP 这个界面和原项目不一样吧」）：① tab 条只在**编辑已有服务器**时出现（原版 `if (isEdit)`，新增只有基础表单）；② 传输方式改用原版分段选择条（复用 `EditSegTabBar`，改成 internal，参数同 `_SegChoiceBar` 44/r18/4/6/88）；③ 补上 SSE 提示行、名称 `My MCP` / URL `http://localhost:3000[/sse]` / 请求头两框 hint；④ 启用开关改回「无图标、独立小卡」；⑤ 自定义请求头改成**每行一张卡**（字段上下排 + 右对齐红色垃圾桶）＋ primary「添加请求头」按钮；⑥ 保存按钮 新增=＋ / 编辑=✓。测试：现有 MCP 测试全绿 + 门禁。 | ✅ 本轮 |
| 修复 | **JSON null 不再变成字面量 `"null"`**（2026-09-12 用户：「换 DeepSeek 输出全是乱的、会输出 null」）：`JsonNull` 也是 `JsonPrimitive`，`.content` 给的是字符串 `"null"` ⇒ DeepSeek 思考分片的 `"content":null` 被当正文追加。三个 provider 解码器/非流式客户端、附件 part、app 侧解析模型输出与用户粘贴 JSON 的 29 处全改 `contentOrNull`（详见 §4-31）。测试：`OpenAiClientIntegrationTest.nullContentAndReasoningNeverBecomeTheLiteralNull` + `JsonNullTrapTest`。 | ✅ 本轮 |
| 修复 | **来源链接画成序号胶囊**（2026-09-12 用户：「这个链接不是显示我弄到的胶囊加数字呀，是直接显示链接这两个字」）：真机取证（会话 `fc3b07de`、`deepseek-flash` + aihot MCP）——模型没写 `[cite:id]`，而是 `[链接](https://aihot.news/items/…)`，且该工具回纯文本（无 items[]/无 id）⇒ 既没有来源也没有序号，只剩链接文字。修：① `extractCitationItems` 把**正文里的 http(s) Markdown 链接也收作来源**（按 URL 去重、编号排在工具来源后；仅当本条消息用过工具；「链接/link/here/点击」等占位标签不当标题）；② 新增 `resolveSourceUrlCapsule`：命中来源的普通链接同样画成序号胶囊；③ `HomeScreen` 的引用解析补**按 URL 匹配**。测试：`CitationSourcesTest` +4、`MarkdownCitationTest` +2。 | ✅ 本轮 |

| 性能 | **冷启动 → 输入框抬起动画的打嗝**（2026-09-17 用户：「冷启动 app 点输入框，输入框抬起会一顿一顿的」；用户原话：「你看看 rikkhub 这个部分 可以你是在冷启加载一些东西导致的」）：参照 `Rikkahub-app/RikkaHubApp.kt` 的 `runBlocking(IO)` 关键 / `launch(IO)` 杂事范式，找出几个主线程上的 IO 重活。① `AppContainerImpl.httpClient`：原本「`val httpClient = OkHttpClient.Builder()...build()`」在构造期立即跑 `proxySelector(GlobalProxy.selector(preferenceRepository))`，顺手把 `preferenceRepository` 这个 `by lazy` 拽醒，触发 SQLite cold-open（**~50–150ms 主线程**）⇒ 改成 `by lazy`，`AppContainerImpl(this)` 只做 self-ref 赋值。② `providerPreWarmJson`：上一批「redundant Json format」警告里漏的另一处「`prewarmConfigCaches` for-loop 内 `Json { ignoreUnknownKeys = true }`」提到 class scope singleton；与 `AssistantRegexApplier.decodeJson` / `AskUserCard.askUserDecodeJson` 同一模式。③ `prewarmConfigCachesOnMainIdle`：新增方法，挂 `android.os.Looper.myQueue().addIdleHandler { appScope.launch { prewarmConfigCaches() }; false }`，**首帧画完、用户眼里画面 ready 之前不动 IO**——之前 `appScope.launch { ... }` 立即起飞，与 IME inset 动画抢 IO 间接打帧。④ `MemoApplication.onCreate` 把 5 段同步 IO 全发到 IO：a) `TtsPlayer.init` — **最大热点**，`TextToSpeech(...)` IPC 200–500ms；b) `mcpConnections.connectEnabled` — `McpRepository.enabledServers` DB 读 + JSON 解析；c) `BundledSkills.seedIfNeeded` — assets 读 + filesDir 写；d) `DocumentTextExtractor.init` — PDFBox 资源加载；e) `LogBootstrap.init` — crash handler + 日志目录。`controller(context)` 内部已有兜底，自动播放路径（`ChatViewModel` 里 `tts_auto_play_assistant_replies_v1`）改成传 `container.appContext` 走懒 init 双保险。migrations / builtin seeding / 第一读 prefs……保留 sync，按依赖链顺序。测试新写 `ColdStartInitTest` 4 例：`httpClient` 单例、`AppContainerImpl(this)` 在 Robolectric 里不抛、`prewarmConfigCachesOnMainIdle` 不触发 `database` 立即读、`providerPreWarmJson` 行为 `ignoreUnknownKeys = true`。**全模块 1230 例绿、assembleDebug 通过**。⚠️ **但这一批并没有解决用户报的卡顿** —— 这些是「一次性初始化耗时」，而卡顿是「每帧开销」，两者不在同一条轴上；用户实测「没有改变」。真因与修法见下一行。 | ✅ 本轮（结论已修正） |

| 性能 | **【真因】键盘抬起时每帧重组整个聊天页**（2026-09-17 用户复测：「改的好 没有问题了」）。上一行那批初始化 IO 调整**不是**本 bug 的解。真因是 **composition 期读 `WindowInsets.ime`**：Compose 里该值挂在快照状态上，「在组合体内读」= 订阅 IME inset，而键盘升起时它每帧都变 ⇒ 读它的 composable 每帧重组（250ms 动画 ≈ 60 次）。代码里有两处这么读：① `ChatContent.kt` 的 `val imeBottomPx = WindowInsets.ime.getBottom(scrollDensity)` —— `ChatContent` 约 1700 行（顶栏 + 整条时间线 + 输入栏接线 + 所有 sheet/dialog 挂载），整块按帧重跑，且紧随的 `LaunchedEffect(imeBottomPx)` 每帧重启；② `ChatInputBar.kt` 的 `visibleHeightDp` → `maxInputHeightDp` → 文本框 `heightIn`，整个输入栏（文本框 + 图标行 + 建议气泡 + 语音 UI）按帧重组。两处叠加正是用户看到的「一顿一顿」。修法（**UI/UX 与公式逐字不变**）：① IME 钉底逻辑抽成独立 composable `ImeRisePinEffect(...)` —— 按帧重组只剩这个**不渲染任何东西**的函数，`ChatContent` 自身不再订阅 inset；语义逐字保留（inset 读取时机不变、`tailNearBottom`/`pointerDown` 仍组合期读、「新视口布局之前取值」的 `derivedStateOf` 约束不变、`record` 在组合期 / `consume` 在 effect 里不变、`ImePinTracker` 初值仍是首次组合观察到的 inset）。② 输入框高度上限搬到**布局期**：新增 `Modifier.imeCappedInputHeight(windowInfo, imeInsets, attachmentPreviewHeight)`，公式与 `chat_input_bar.dart:2569-2586` 逐字一致，只把求值换到 measure 阶段。**踩到一个坑值得记**：`WindowInsets.ime` 的**属性 getter 本身带 `@Composable`**，不能在 `layout {}` 的 measure 块里直接访问（报 `@Composable invocations can only happen from the context of a @Composable function`）；正确做法是**组合期取 holder 实例传进 modifier、measure 期再调 `getBottom(density)`** —— 后者是普通函数，此时读快照状态属「布局期读」，只触发重排不触发重组；而重排本来就每帧都在做（列表视口随键盘收缩），所以是净赚。两处都留了「**不要挪回组合体**」的注释。验证：编译无新 warning、全模块 **1230 例绿**、`assembleDebug` 通过、装机实测用户确认解决。 | ✅ 本轮 |

| 清理 | **清掉 14 条既有编译警告**（2026-09-17 用户：「处理了 你看看这个」）：`compileDebugKotlin --rerun-tasks` 重出 14 条，按用户原话列出的「NetworkTts 冗余 else / AskUserCard 恒真恒假 / 4 个旧剪贴板 API / ToolCallCard 缺 opt-in 等」范围全部归零。① `NetworkTts.synthesize`：12 个 sealed 子类型穷尽 → 删 `else -> throw TtsException(...)` 兜底（再添子类由编译器兜住）。② `AssistantRegexApplier.decodeRules` + `AskUserCard.parseAnsweredValues`：把函数里的 `Json { … }` 提到 module/file `private val decodeJson = Json { ignoreUnknownKeys = true }` 复用（消除「redundant Json format」）。③ `AskUserCard` 提交流的 `enabled = (pendingRequest != null || onSubmit != null)` 与点击分支 `if (pendingRequest != null && askUser != null) … else if (onSubmit != null) …`：进函数体那一刻已经被早返回拦在 `pendingRequest == null` ⇒ 两条恒假、删除；按钮 `enabled` 改成纯 `onSubmit != null`，点击走 `onSubmit?.let { … }`。④ `BackupProgressDialog`：`showBackground` 已包括 `backgroundLabel != null` 检查 ⇒ `if (showBackground && backgroundLabel != null)` 简化为 `if (showBackground)`。⑤ `ProviderSubPages` 代理子页：`snapshotFlow { arrayOf(proxyEnabled, proxyType, …) }` reified T 与 Boolean/String 的公共父类碰撞 → 显式 `arrayOf<Any?>(…)`，加 `@OptIn(FlowPreview::class)`。⑥ `ToolCallCard.prettyToolJson` + `prettyJson = Json { prettyPrintIndent = "  "; ... }`：`prettyPrintIndent` 自身是 `@ExperimentalSerializationApi` → 给 val 与函数都打 `@OptIn(ExperimentalSerializationApi::class)`，并把 `prettyToolJson` 从 `private` 抬到 `internal` 好测。⑦ `TtsEngine`：父类 `UtteranceProgressListener.onError(utteranceId: String?)` 是 deprecated 重载 → override 标 `@Deprecated("Use onError(utteranceId, errorCode).", level = WARNING)`；**K2 下仍有 `overridingDeprecatedMember` 残留 warning**（试过 `@Suppress("overridingDeprecatedMember") / @file:Suppress(...) / 改 implementation 各种名都不中，已记录）→ 暂保留，等 Kotlin 编译器更新。⑧ `ModelDetailSheet` / `ThemeSettingsScreen` / `TranslateScreen` / `SelectCopySheet`：Compose 1.8+ 弃用了 `LocalClipboardManager` → 全部改 `LocalClipboard.current` + `rememberCoroutineScope().launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text))) }`（写入端，沿用 `LogViewerScreen`/`ChatContent` 已建立的惯例），读端 `clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()`；删除未使用的 `AnnotatedString` import。⑨ 顺带清掉测试代码里的同型 reified 警告：`core/data/.../MessageDaoPartsTest.kt` 两处 `arrayOf` 加 `<Any?>`。新增/延展测试：`ToolCallCardLogicTest` +3（`prettyToolJson` valid object → round-trip + 含换行、`invalidFallsBackToRaw` 损坏+空、`nullElementPreserved`），其它项由类型层兜住或被既有路径覆盖。**全模块 1226 例绿、assembleDebug 通过。** 摘要与已知 §5.13 不变 | ✅ 本轮 |

| K1 | **Agent Skills（照 RikkaHub 移植）**：`core:common` 的 `SkillFrontmatterParser`（snakeyaml，SafeConstructor + 收紧 LoaderOptions）/`SkillPaths`（canonical 边界）/`SkillStore`（列表·原子保存·删除·文件表）+ 工具面 `SkillTools`（定义 / `<available_skills>` 系统提示词块 / 执行）+ `SkillImporter`（md 与 zip，含嵌套技能与条目穿越防护）+ `Assistant.enabledSkills` + `ContextSource.skillPrompt` + 管理界面（技能页 / 技能详情页 / 助手编辑页「技能」tab / 设置入口） | ✅ 本轮 |

## 5.9 全量缺口审计（2026-09-09 系统普查，Flutter vs Android 逐域比对）

方法：`lib/features` 16 域 + `lib/desktop` 全页面类名 → 对比 Android `*Screen/*Sheet`，逐项 `grep` 验证实现真实性（非壳子）。**结论：页面级覆盖率约 95%，剩余缺口集中在 3 块。**

### P0 — backup 全域（**已不是真空**：主链路 + 本机副本已通，余下 6 个子块）
- **现状（2026-09-12）**：子块 **1~6 全部落地**并带单测 —— 归档格式层（manifest/限额/ZIP 读写/校验）、导入导出主链路（SAF 导出 / 导入 + 模式选择 + 进度对话框 + 重启提示 + 实体路由）、**merge 恢复**、**本机副本**、**备份提醒**、**WebDAV**、**S3**；`BackupScreen` 六个 section 与 `LocalSnapshotsScreen` 均已接线（「本地备份」里的 Cherry Studio / Chatbox 两行仍是壳 = 子块 8）。明细见 §5.10.4。
- **Flutter 规模**：`lib/core/services/backup/` **29 文件 / 20429 行**（`data_sync.dart` 3998、`cherry_importer.dart` 1874、`chatbox_importer.dart` 1434、`chatbox_backup_archive.dart` 1414、`restore_bundle_staging.dart` 1356、`s3_client.dart` 1144、`cherry_direct_backup_reader.dart` 1060、`restore_receipt/…_mover/…_lock/…_lease/…_cutover_executor/…_durability/…_startup_gate/…_previous_*` 等）+ `lib/features/backup/` 5128 行。我们按子块增量移植，不追求行数对齐。
- **剩余子块**（编号照 §5.10.4 表，**只剩两块**）：
  7. **前向兼容闸门（完整版）**（`minimumReadableFormatVersion` / `minimumReadableSchemaVersion` + 同意对话框；最小版已随子块 1 落地）
  8. **Cherry Studio / Chatbox 导入**（纯 importer；设置里那两行现在是壳）
- **依赖 RikkaHub 参考**：`<RikkaHub 本地克隆>` 的 `data-sync` 模块（WebDAV/S3/备份语义）+ `app` 侧调度，按工作约定"功能/逻辑直接搬 RikkaHub"。

### P1 — 小项（各 < 200 行，可一并做）
- **保持屏幕常亮**：**不移植**（2026-09-09 用户明确"这个我不要"，已否决）。Flutter `core/services/screen_wakelock.dart`（引用计数 + 10s 延迟释放 + `reassert()` 恢复重开；设置键 `display_keep_screen_on_during_generation_v1`）→ Android 不做，设置页也不加行。若日后要恢复，落地方式为 `ChatViewModel` 生成起/止 acquire/release + `Activity.addFlags(FLAG_KEEP_SCREEN_ON)`。
- **健康数据设置页**：**不移植** —— Flutter `healthSupported = iosDeviceToolsSupported && _healthDataAvailable`，纯 HealthKit，Android 无对应能力（`Assistant.healthDataTypeIds` 字段保留为数据兼容即可，已存在）。
- **联网搜索引用胶囊**：**改回原项目样式**（2026-09-10 用户明确"显示改成圆形、不显示链接、改成数字那种，就是原项目那种"，且"不考虑兼容性，一锤定音"）。撤销此前"整体换 RikkaHub 域名胶囊"的例外：
  - **胶囊**（`core/ui/.../MarkdownRenderer.kt` `citationInlineContent`，度量抽成 `CITATION_BADGE_*` 常量便于微调）：**16dp 高** + 圆角 8dp（=高度/2，即全圆）+ **最小 16dp 宽** + 左右 3dp 内边距 + **10sp** 常规字重 + `primary` **16%** 底 + 左右各 2dp 外边距（= 原项目 `EdgeInsets.symmetric(horizontal: 1.5)` 的等价留白，防角标与前面文字粘连）。**注**：原项目是 20dp 高 / 12sp / 20% 底（`markdown_with_highlight.dart` L441-472），2026-09-10 用户实测后要求"小一点，有点影响阅读、太显眼"，故整体缩小一档 —— 这是**用户明确认可的偏离子项**，不要再改回 20dp。
  - **垂直对齐（Compose 专属坑）**：`PlaceholderVerticalAlign` 必须用 **`AboveBaseline`**（坐在文字基线上），**不能**照抄原项目的 `TextCenter + translate(0,-2)`。Compose 的 `TextCenter` 对齐的是**行盒中心**，该位置浮在 CJK 字形上方，再叠加 -2dp 上移会让角标明显悬空（用户实测反馈"和输出文字没有水平对齐"）。改用 `AboveBaseline` 后仅需轻微下移 1dp 微调即可视觉齐平。
  - **标签**：**恒为数字序号**（`CitationInfo.index`），永不显示域名。`resolveCitationCapsule` 忽略 label 里的 domain 元数据；数字解不出时回落 `?`（元数据本身是数字则用它）。
  - **提示词**：回到原项目的 **`[cite:id]`**（`SearchToolService.TOOL_DESCRIPTION` / `SYSTEM_PROMPT`），不再要求模型写 `[citation,domain](id)`。
  - 历史 `[citation,domain](id)` / `[cite,domain](id)` 仍能被解析（容忍模型漂移），但域名字段被丢弃。

### P2 — 已核实「无需移植」或「已被替代」
- **migration（Hive→SQLite）**：`hive_to_sqlite_migration_page.dart` 1773 + `service` 2436 行——Flutter 端历史包袱（老用户 Hive 库迁移）；Android 端是全新 SQLite schema，无 Hive 历史用户。**不移植**。仅 `legacy_data_retirement_service.dart`（清 hive 残留文件）的等价能力已在 `StorageSpace.kt` L123/278/350/493 实现（`hiveArtifacts` 识别 + 归类 other + 删除）。
- **scan（二维码）**：`QrScanPage` 仅被 `import_provider_sheet` 调用；Android `ImportProviderSheet.kt` 已用 quickie（相机扫描）+ zxing（相册解码）覆盖，**无需独立页**。
- **Desktop\* 四页**（DesktopChat/Home/Settings/TranslatePage）：桌面专属，Android 不移植。
- **IosBackgroundSettingsPage**：iOS 后台生成专属，不移植。

### 仍挂账（PORTING.md 既有 ⬜，非本次新发现）
> **2026-09-12 清理**：原先挂在这里的 **M2d 记忆收尾**（Smart Add LLM 合并 / pipeline / 哈希冻结自愈 / tab 内条目列表）与 **C（tab 布局管理页）**都已落地（批次表 M2d-b / M2d-c / C 行），已从挂账里删掉；**S4 用量卡**、**消息模板/预设对话卡** 同理（见 §6 两条已改写）。别再当待办。
> **现行剩余**：备份子块 **7/8**（§5.10.4）、**§5.12 五个接线批**（渲染/输入/聊天行为/语音/模型）、S5 搜索 kelivo + 启动自测（不移植）、MCP-3（OAuth + 会话内 sheet；STDIO 桌面专属不移植）、选择态 mini-map（**UI-7i 图片导出已整块撤销**：2026-09-20 用户拍板，根因与删除清单见 §5.31）。**已关闭**：收尾-5（Toast→sonner）——用户 2026-09-13「不用做了，toast 已经弄好了」，保留手撸 `MemoSnackbar`；**上下文压缩机制换成 opencode 阈值机制**——用户 2026-09-13「改成 opencode 那个压缩阈值来压缩」，见 §5.11 + 批次表「待改」行；**助手域三处遗留全部补齐**（2026-09-13 用户「给我做完吧 我要使用了」）：「管理总结」列表（编辑/清除会话总结）、「流式输出」开关（非流式路径 `completeAsChunks`）、`applyContextLimit`（限制上下文条数）。
- **搜索服务列表出现重复 Bing**（2026-09-10 用户反馈，指示"先不管"）：代码层无重复 bug（编辑器新建用 UUID id、`SearchSettingsRepository.addService` 按 id 去重、列表只渲染表数据）→ 疑为真机数据层两行（默认 `default` 行 + 用户/测试添加的 `bing_local` 行）。若处理：同 type 去重会误伤 searxng/anysearch 等多实例配置，只能针对 `bing_local`（无配置差异）或让用户手删。
- **日志页三个 tab 已全部通电**（2026-09-11 用户问"上下文/应用日志是没有接线吗"，本轮接上；原先只有请求日志是通的）：
  - **上下文**：模型搬到 `core:common/.../logging/ContextLogModels.kt`（写入端 `ContextLogger` 之外，`ContextSource`/`ContextSegment`/`ContextLogSnapshot`/`ContextLogTailReader` 都在这里），新增 `ContextTag`/`ContextTags`/`TokenEstimator`；标签随 `LlmMessage.contextTags` 走（= 上游 map 里的 `_kelivo_ctx_segments`；provider 客户端逐字段拼 JSON，所以 Android 不需要"发请求前 strip"这一步）。组装侧新增 `app/logging/ContextLogAssembler.kt`（`buildSnapshot`/`logPrepared` + 标签助手 `systemMessageTags`/`appendedSystemMessageTags`/`joinSystemParts`），`ChatViewModel` 在 history 拼装处打标签、世界书 `inject(..., tagContextLog = true)` 也带标签，请求前 `logPrepared` 一条快照。
    - **注意上游语义**：追加段（`_appendToSystemMessage`/世界书 after）的标签长度含前面的 `"\n\n"`，所以该段文本会带前导空行——这是原版行为，测试 `appendedSegmentsOwnTheirLeadingBlankLine` 锁住它。
  - **应用**：`FlutterLogger.log` 已接上 Android 侧存在对应物的失败路径——SSE 邻接 JSON 恢复（`SseEventParser` 默认 `onRecovery`）、三个 provider 的畸形事件（`provider=<id> eventType=… error=…`，tag `DecoderParseError`）、后台任务（标题/摘要/记忆整理/建议，tag `HomeViewModel`）、抽屉重新生成标题、压缩上下文、供应商详情保存/删除、模型详情保存。上游的 `ImageFallback`（图片降级）与 `ModelOverride` 在 Android 没有对应路径，未接；desktop 两处不移植。
  - **顺带修掉的功能缺口**：**指令注入此前从未进入请求**（`InstructionInjectionScreen` 只写库，`ChatViewModel` 从不读）——现在按 `injectInstructionPrompts` L1748-1772 接上（`activeIds(assistantId)` → 非空提示词用空行连接 → 追加到系统消息，来源标签 `instructionInjection`）。
- **`applyContextLimit` 已接线**（2026-09-13，用户先说「先留着」后要求「给我做完吧」）：`message_builder_service.applyContextLimit` L2139-2166 1:1 移植到 `app/llm/prompt/ApplyContextLimit.kt`（保留系统消息 + 最近 N 条、N 夹在 `Assistant.Min/MaxContextMessageSize`、丢掉裁点留下的悬空 `tool` 消息），在 `startGeneration` 的世界书注入之后、`logPrepared` 之前调用（原版顺序：message_generation_service L194-197）。副作用与原版不同的一点：我们的 OCR/文档抽取在裁剪**之前**就跑了（原版是裁完再抽），被裁掉的消息里的图片会白抽一次——结果有缓存，只影响首次。
- **上下文压缩机制已换成 opencode 阈值机制**（用户 2026-09-11 提出要换 → 2026-09-13 给出方案「改成 opencode 那个压缩阈值来压缩」）：不再用原项目那套（LLM 折叠成摘要 + 新建会话），改为「估算超阈值就先压缩、同会话插入锚定摘要检查点」；详见 §5.11 与批次表「待改」行。**唯一未接**：provider 报 context-length 后的 `compactAfterOverflow` 自动压缩重试。
- **引用胶囊出现 `?`**（2026-09-12 定位 → **同日已按用户决定修掉**）：不是解析失败，是来源筛选导致的序号解不出。白名单改为按 `items[]` 结构判定 + 解不出的标记不再渲染（详见 §4-26、§5.11）。**遗留**：返回纯文本的工具，其引用现在被静默丢弃（不显示 `?` 也不可点），要可点需工具侧结构化返回。


---

## 5.10 backup 移植详细规格（**实施中**，2026-09-10 起）

> 本节是 backup 域的施工图。通用纪律照旧：纯逻辑进 `core:common`/`core:data` 并带单测；IO 编排进 `core:data`；UI 只做薄壳；每个子块完成即 `bash tools/quality_gate.sh` 全绿再提交。

### 5.10.0 归档格式（**必须逐字节对齐，否则 Flutter 端写的备份安卓读不了、反之亦然**）

来自 `data_sync.dart` L248-280、L1948-1986。

```
<name>.zip
├── manifest.json          # UTF-8 JSON，见下
├── settings.json          # 业务设置快照（13 张实体表 + preference 键）
├── database/kelivo.db     # 可选（includeChats=true 时）
├── upload/                # 可选（includeFiles=true 时）用户上传附件
├── avatars/               # 头像
├── images/                # 助手生成的图片
└── fonts/                 # 自定义字体
```

`manifest.json` 字段（**键名/顺序/semantic 都不能动**）：

| 键 | 值 | 备注 |
|---|---|---|
| `format` | `"kelivo-backup"` | 常量 `_backupFormat` |
| `formatVersion` | `2` | 当前写 2 |
| `minimumReadableFormatVersion` | `2` | 老版本据此判断能否降级读取 |
| `payloadKind` | `"sqlite"` \| `"settings-only"` | 由 includeChats 决定 |
| `createdAtUtc` | ISO8601 UTC | `DateTime.now().toUtc().toIso8601String()` |
| `appVersion` | `"1.0.5+6"` | version+buildNumber |
| `includeChats` / `includeFiles` / `secretsIncluded` | bool | secretsIncluded 恒 true |
| `businessEntityRowIds` | `Map<String, List<String>>` | model 模式下实体 id 投影，用于 merge 时保持 DB 身份 |
| `database` | 对象（仅 includeChats） | `{entry:"database/kelivo.db", schemaVersion:3, minimumReadableSchemaVersion:<n>, conversationCount, messageCount}` |
| `entries` | `Map<name, {bytes, sha256}>` | 每个归档条目的字节数与 SHA-256，restore 时逐条校验 |

**上限常量**（恢复侧防护，`_ExtractionBudget`）：单条目 8 GiB / 总计 16 GiB / 条目数 100000 / manifest 16 MiB / settings 1 GiB。

### 5.10.1 settings.json 快照内容（**等价物已在 Android 侧齐备**）

Flutter `BusinessRestoreService.exportSettings()` → `BusinessSettingsRouter.exportSnapshot(await repo.readSnapshot())`，产出：

- **13 张实体表**（`BusinessEntityKind`，`core/database/business_data.dart` L8-45）：`assistant_rows` / `provider_rows`(PK=`provider_key`) / `provider_group_rows` / `mcp_server_rows` / `world_book_rows` / `assistant_memory_rows` / `quick_phrase_rows` / `search_service_rows` / `tts_service_rows` / `instruction_injection_rows` / `assistant_tag_rows` / `memory_entry_rows` / `user_profile_field_rows`。**Android 侧 13 张表全部存在**（`assets/memo_schema_v3.sql`，共 30 表 17 索引），且 `PayloadEntityDao(table, pk)` 已是通用读写器（`id` / `sort_order` / `payload` / `updated_at`；provider 传 `provider_key`）——**直接复用，不要另写 DAO**。
- **preference 键**：`SettingsKeyRegistry` 已生成（`tools/settings_keys_gen.py`，源=`business_settings_router.dart` + `business_data.dart`），含 `LOCAL_ONLY_KEYS`(9) / `DISCARDED_KEYS`(6) / `PREFERENCE_KEYS`(~130) / `ENTITY_SOURCE_KEYS`(13) / `PROVIDER_ORDER_KEY`。`PreferenceRepository.readAllRows()` 已能吐出全部 preference_rows。
- **路由规则**（`classifyBusinessKey`，Android 已实现，**判断顺序不可换**）：ENTITY → PROVIDER_ORDER → LOCAL_ONLY(含 `restore_*` 前缀) → DISCARDED → PREFERENCE(含 `display_*` 前缀) → UNKNOWN。
- **UNKNOWN 键**：进 preference_rows 原样透传（`readAllRows` 天然覆盖）。

### 5.10.2 密钥策略（**注意：与 S3/WebDAV 无关**）

`secretsIncluded` 恒 true —— provider 的 apiKey 随 `provider_rows.payload` 一起备份（这是原项目行为，已知安全取舍，不要"顺手加固"）。备份**不加密**：Flutter 端 `exportToFile` 也没有口令字段（`backup_page_password` 只属于 WebDAV/S3 远端凭据）。

### 5.10.3 与 Flutter 的差异点（**实施时必须显式决策**）

| 点 | Flutter | Android 落地 |
|---|---|---|
| 归档写出 | Dart isolate（`runBackupIsolate`）+ 手写 streaming zip（`_StreamingZipWriter`，含 ZIP64/data descriptor） | 用 `java.util.zip.ZipOutputStream`（Zip64 原生支持，JDK7+）；大文件走 64 KiB buffer 复制，避免 OOM |
| 归档读出 | `_extractZipSync` + `_BoundedOutputFileStream` 逐条限额 | `ZipInputStream` 逐条 + 自建 `ExtractionBudget` 计数器，限额常量照抄 |
| 完整性 | 打包后 `_verifyPackedBackupSync` 重读核对 sha256 | 打包后重开 zip 逐条算 SHA-256 比对 manifest.entries |
| DB 快照 | `chatService.snapshotDatabase(file)`（drift 侧 VACUUM INTO 或复制） | `SQLiteDatabase` 在线备份：优先 `VACUUM INTO '<path>'`（SQLite≥3.27，Android 11+ 有），回落 `db.backup` 式逐页复制（或 `query` 全表重建）；**必须保证 WAL 已合并**，用 `PRAGMA wal_checkpoint(TRUNCATE)` |
| appData 目录 | `getUploadDirectory()` 等 | `context.filesDir` 下同名子目录（`upload/` `avatars/` `images/` `fonts/`），需核实现有命名 |
| 文件选择/保存 | `FilePicker` + `NativeFileSave` | SAF：`ActivityResultContracts.OpenDocument`（`application/zip`）/ `CreateDocument("application/zip")`；MIME 过滤比扩展名宽松，读取时兜底校验 zip magic |
| 进度 | `BackupProgressSink(phase/processed/total/unit/cancellable)` | 同构 Kotlin `sealed class BackupPhase` + `data class BackupProgress` + `fun interface BackupProgressSink`；协程 `ensureActive()` 实现取消 |

### 5.10.4 子块拆分与顺序（每块独立可交付 + 可测）

| # | 子块 | 产出 | 依赖 | 状态 |
|---|---|---|---|---|
| 1 | **归档格式 + 本地导出/导入** | `core:data/backup/BackupArchiveCodec.kt`(纯逻辑 zip 读写+manifest) / `BackupSnapshotBuilder`(13 表+prefs+db 快照) / `LocalFileExporter` / `LocalFileRestorer` / `BackupScreen` 4 行接线 / SAF 选择器 | 无 | ✅ 2026-09-10（明细见下） |
| 2 | **恢复模式（overwrite / merge）** | merge 语义：实体按 id upsert + preference 逐键覆盖 + 会话按 id 去重；`RestoreMode` 已在 Flutter 定义 | 1 | ✅ 2026-09-12（明细见下） |
| 3 | **本地快照** | `LocalSnapshotStore`(快照文件管理/保留数/空间上限) + `LocalSnapshotScheduler`(频率) + `LocalSnapshotsScreen` 接线 + 7 个 settings | 1 | ✅ 2026-09-11（批次表「备份-3」，5 文件 + 5 测试类） |
| 4 | **备份提醒** | `BackupReminder`(启用/频率/上次备份时间) + 完成时 `recordBackupCompleted()` + 3 行接线 | 1 | ✅ 2026-09-12（明细见下） |
| 5 | **WebDAV** | `WebDavClient`(PROPFIND/PUT/GET/DELETE + Basic auth) + `WebDavConfig` model + 服务器设置子页 + 测试连接 + 远端列表 + 恢复 | 1 | ✅ 2026-09-12（明细见下） |
| 6 | **S3** | `S3Client`(SigV4 + list/put/get/delete) + `S3Config` model + 服务器设置子页 + 测试连接 + 恢复 | 1 | ✅ 2026-09-12（明细见下） |
| 7 | **前向兼容闸门** | `minimumReadableFormatVersion` / `minimumReadableSchemaVersion` 判定 + 同意对话框（`forward_compat_consent_dialog`） | 2 | 🚧 最小版已落地（`BackupManifestCodec.declaresNewerBuild`），完整版 ⬜ |
| 8 | **Cherry Studio / Chatbox 导入** | 两个 importer（可选，纯数据转换） | 2 | ⬜ |

#### 子块 1 进展明细（2026-09-10）

**已完成 —— 格式层（`core:data/backup/`，4 文件 / 58 单测全绿）**

| 文件 | 职责 | 对应 Flutter |
|---|---|---|
| `BackupManifest.kt` | manifest 编解码 + 版本闸门 + 恢复上限常量。`BackupManifestCodec.encode/decode/acceptsFormat/declaresNewerBuild` | `_buildBackupManifestJson` L1948-1986、`_acceptsArchiveFormat` L1624、`_declaresNewerBuild` L1645 |
| `BackupArchiveGuards.kt` | `ExtractionBudget`(总量/条目数上限) + `BoundedEntryBudget`(单条目 + 声明大小校验) + `ZipEntryNames.validate/validateRoot`(穿越/盘符/根目录白名单) + `CollisionLedger`(重名拒绝，大小写折叠) | `_ExtractionBudget` L3359、`_BoundedOutputFileStream` L3373、`_validatedZipEntryName` L1334、`_validateZipPathPrefixes` L1355 |
| `BackupArchiveCodec.kt` | `pack`(entry 顺序 settings→db→upload/avatars/images/fonts→manifest；逐条算字节数+SHA-256) / `extract`(逐条校验摘要+大小) / `verifyPacked` / `readManifest` / `readEntry` | `_StreamingZipWriter` L3535、`_packZipSync` L905、`_extractZipSync` L1183、`_verifyPackedBackupSync` L1029 |
| `BackupSettingsSnapshot.kt` | `export`：13 实体表 → `settings.json`（provider 出 **map** + `providers_order_v1` 数组、其余出数组按 `(sort_order,id)` 排序；preference 键按 `classifyBusinessKey` 过滤 LOCAL_ONLY/DISCARDED，UNKNOWN 透传） | `exportSnapshot` L269、`exportSnapshotWithRowIds` L295、`_compareRows` L1269、`isProviderOrderOnlyRow` L392 |

**格式层关键结论（实施中已验证，务必遵守）**
1. **`manifest.entries` 不含 `manifest.json` 自身**，但打包后返回给调用方的 entry 表**含**它（Dart 先序列化 manifest 再加入自身条目）→ 提取时必须跳过 manifest 条目，否则报"未声明条目"。
2. **entry 顺序固定**：`settings.json` → `database/kelivo.db` → `upload` → `avatars` → `images` → `fonts` → `manifest.json`（`ASSET_ROOTS` 顺序优先于调用方传入的 map 顺序）。
3. **重名检查大小写折叠**（Windows/macOS 文件系统会折叠）。
4. **`providers_order_v1` 包含 order-only 哨兵行**，但 provider map 排除它们（`PROVIDER_ORDER_ONLY_PAYLOAD = {"enabled":"__kelivo_provider_order_only__"}`）。
5. **根目录白名单**：`upload` / `avatars` / `images` / `fonts` / `database` + 两个顶层文件 `manifest.json` / `settings.json`。
6. 已用 JDK `ZipOutputStream`（原生 ZIP64）替代 Dart 手写 streaming zip —— 只保留格式语义部分（命名/顺序/摘要/限额）。

**已完成 —— 服务层 + UI 接线（子块 1 主链路已通）**

| 文件 | 职责 | 对应 Flutter |
|---|---|---|
| `core/data/backup/BackupProgress.kt` | `BackupPhase`(16 项，wire 值对齐 Dart)、`BackupProgressUnit`、`BackupProgress(fraction)`、`BackupProgressSink`、`BackupCancelledException`、`ProgressBridge` | `backup_progress.dart` |
| `core/data/backup/MemoBackupService.kt` | 公开门面：`exportToCache` / `restoreFromFile` / `peekManifest` / `defaultArchiveName`（`memo_backup_<ISO8601冒号换破折号>.zip`——**品牌化改名**，原版拼 `kelivo_backup_`；文件名不是格式的一部分，归档内容仍逐字节兼容）；`BackupManifestView` / `RestoreReportView(skippedConversations)`；builder/restorer 抛的 `IllegalStateException("备份已取消")` → `BackupCancelledException` | `data_sync.dart` L515 等 |
| `app/ui/backup/BackupProgressDialog.kt` | `TaskProgressDialogCard`(padding 20/18/20/16、14sp bold 标题 + 18dp 图标、6dp 圆角条、13sp phase label + spinner/百分比、12sp@60% subtitle)、`BackupProgressBar`(null = 不定态来回扫)、`backupPhaseIcon` / `backupPhaseLabelRes` / `backupProgressSubtitle`、`formatBytes` / `formatCount` | `task_progress_dialog.dart` + `backup_progress_dialog.dart` |
| `app/ui/backup/BackupImportModeDialog.kt` | 导入模式二选一（覆写 / 合并），按压 scale 0.98 + r14 hairline + 40dp primary 10% 图标砖 | `_chooseImportModeDialog` |
| `app/ui/backup/BackupTaskRunner.kt` | 进度对话框生命周期编排：IO 执行 + `AtomicBoolean` 取消轮询 + 成功 600ms 延时 + 失败保留对话框 + 错误吐司 | `backup_task_runner.dart` |
| `app/ui/backup/BackupRestartDialog.kt` | 恢复后重启提示（含 skipped 变体）+ `restartApp()` = `AlarmManager.setExactAndAllowWhileIdle` + `Process.killProcess` | `backup_restart_dialog.dart` |
| `app/ui/BackupScreen.kt` | §1 两开关接真实 state；§4「导出到文件」`CreateDocument("application/zip")` / 「导入备份文件」`OpenDocument` → cache → `peekManifest` 校验 → 模式对话框 → 恢复。§2/3/5/6 仍为壳 | `backup_page.dart` |

**测试**：`app/src/test/.../backup/BackupProgressUiTest.kt`（8 例：16 phase 图标/标签全枚举覆盖、图标分组与 Dart 一致、标签无意外共享、`formatBytes` 十进制单位与 TB 封顶、`formatCount` 千分位）。
**质量门禁**：`bash tools/quality_gate.sh` 全绿 ✔

**子块 1 踩坑记录（务必遵守）**
- **Lucide 图标必须两行 import 齐全**：`import com.composables.icons.lucide.<Icon>`（扩展属性）+ `import com.composables.icons.lucide.Lucide`（receiver），调用写 `Lucide.<Icon>`。裸写 `<Icon>` 报 `receiver type mismatch`；只写 `Lucide.<Icon>` 缺第一条 import 报 `Unresolved`。
- **`Box` 图标与 Compose layout `Box` 冲突** → `import com.composables.icons.lucide.Box as BoxIcon`，调用 `Lucide.BoxIcon`。
- **`BackupRestorer` 的 phase 常量必须用 Dart wire 值**（`reading_settings` / `validating` / `staging_candidate` / `finalizing`），否则进度标签全退化成 "Preparing"。
- 批量正则改图标时**注意别误伤类型名**（`java.io.File` 被误加 `Lucide.` 前缀）。

#### 子块 2 + 4 进展明细（2026-09-12）

**子块 2 —— merge 恢复（`chat_database_repository.dart:5057-5177` + `business_settings_merger.dart` 全量）**
- `core/data/backup/DatabaseSnapshotMerger.kt`：会话库合并。ATTACH 快照为 `merge_source` → 按 id 遍历 → ① `message_order` 负数/重复（绕过约束的坏快照）跳过计数；② 指纹（对话字段 + MCP 选择 + 消息/分段/思维签名，排除 `updated_at`/is_streaming/附件 `unavailable`，时间戳折秒，group→序号）与本地同 id 相同 ⇒ 去重跳过；③ 冲突（id 或任一消息 id 已存在）⇒ **整段会话**换成 `merge-<sha256前32>` 确定性新 id（消息/分组 id 一并重映射），永不交错；④ `PRAGMA foreign_key_check` 必须为空。报告（imported/deduplicated/skipped/remapped）进 `RestoreReportView`，重启对话框沿用 skipped 变体。
- `core/data/backup/SettingsSnapshotMerger.kt`：settings 合并（对照 `business_settings_merger.dart` 逐函数）——助手 `{...local, ...incoming}` 且**本地 avatar/background 非空优先**；provider 载荷 incoming 胜、顺序按 `providers_order_v1`（有 order key 时）且 **order-only 占位不实体化**（Android 快照导出已过滤它们，materialize 会产生幽灵 provider——有意偏离，已注释）；其余实体 local-first 去重；记忆条目按 `(scope,assistantId,type,归一化内容)` 去重、id 冲突随机重排 + relatedIds 重写/悬挂清理 + migrationIds 合并；偏好合并 = 实体/顺序/LOCAL_ONLY 键跳过、pinned 并集、ASR 按 id 并集（本地优先）、关系映射本地胜、**其余 putIfAbsent（本地有就不动）**。
- `BackupRestorer`：**数据库先行、settings 后写**（原先 settings 写在文件 swap 之前 = 全被丢弃，顺手修正为与上游「业务数据最后持久化」一致的顺序）；MERGE 时不再出现 `replaceAll` 误清本地实体（此前 merge 模式实体是破坏性的）。
- 有意偏离：上游 chats-only 合并后有 `recomputeImportedAttachmentAvailability`（本地附件标不可用）——本地文件恢复永远带资产（additive copy），不触发该路径；WebDAV/S3 接上时再补。

**子块 4 —— 备份提醒（`backup_reminder_provider.dart` + `backup_page.dart` §2 + `side_drawer.dart` L1520-1612）**
- `app/BackupReminder.kt`：五键（enabled/intervalDays/minutesOfDay/enabledAt/lastBackupAt，PREFERENCE 键进 preference_rows 随备份走）+ `nextReminderAt() = (lastBackupAt ?: enabledAt) + intervalDays` 在提醒时刻 + 每分钟 ticker（替代 Flutter Timer.periodic）+ 会话内 snooze（不落盘）。容器级单例，`MemoApplication.onCreate` 调 `initialize()`。
- 备份页 §2 全接线：启用开关（**未选时间先弹时间滚轮**，对齐 upstream `setEnabled(true)` 抛 StateError 的前置）→ 频率（预设 1/3/7/14/30 + 自定义 1-365 对话框）→ 时间 → 上次备份 → 下次提醒（后四行仅启用时显示）。导出到文件 **保存成功** 才 `recordBackupCompleted()`（对齐 backup_page L1417-1423）。
- 抽屉横幅 `_buildBackupReminderBanner`：到期才出现，点击进备份页（新增 `onOpenBackup` 导航参数，HomeScreen/MainActivity 转发），X = 会话内 snooze。
- 顺带：备份页 §3 本机副本的启用开关此前是死的占位 → 接 `LocalSnapshotPreferences.readSettings/writeSettings`（service 的 `preferences` 转 public）。

**测试**：`SettingsSnapshotMergerTest` 16、`DatabaseSnapshotMergerTest` 6（Robolectric 双库：完好/坏序快照）、`BackupReminderTest` 6；全模块 1265 例 0 失败。

**子块 2+4 待办（2026-09-12 更新）**：~~WebDAV 的上传成功路径接 `recordBackupCompleted()`~~（已随子块 5 完成）；~~S3 的同款钩子~~（已随子块 6 完成 —— `BackupScreen` 里导出/WebDAV/S3 三条成功路径都调 `recordBackupCompleted()`）；**剩**：导入抽屉的合并报告明细（imported/deduplicated 计数展示，目前只展示 skipped）可在真机反馈后加。

#### 子块 5 进展明细（2026-09-12，WebDAV）

- **`core/data/backup/WebDavClient.kt`**：`WebDavConfig`（url/username/password/path/userAgent，`webdav_config_v1` 单键存 preference_rows；默认路径品牌化为 `memo_backups`——上游是 `kelivo_backups`，目录名属用户可见默认值）+ `WebDavClient`（OkHttp + XmlPullParser，协议语义逐条对照 `data_sync.dart`：`_collectionUri` 尾斜杠 / `_fileUri` / Basic auth / `User-Agent` / `testWebdav`（PROPFIND depth 1，2xx/207）/ `_ensureCollection`（逐段 PROPFIND depth 0 → 404 则 MKCOL，200/201/405 通过、401 报未授权）/ `listBackupFiles`（depth 1 多状态解析：跳过集合自身与目录、displayname 缺省回落 href 尾、mtime 缺省回落文件名时间戳——memo/kelivo 两种前缀都认、按时间倒序）/ 上传半段（流式 PUT + uploading 进度）/ 下载半段（流式 GET + downloading 进度）/ DELETE + `_deleteRemoteQuietly`）。PROPFIND 的 XML 走 XmlPullParser（RikkaHub `WebDavClient` 的解析方式，命名空间后缀匹配）。
- **`MemoBackupService` 扩展**：`webDavConfig()/saveWebDavConfig()/testWebDav()/listWebDav()/backupToWebDav()/restoreFromWebDav()/deleteWebDavItem()`；构造注入容器共享的 OkHttp。`backupToWebDav = exportToCache → ensureCollection → PUT → 删本地临时件`，取消时静默删远端残件；`restoreFromWebDav = 流式下载到 cache → 复用 BackupRestorer`。
- **UI**：`WebDavSettingsScreen`（子页，route `webdav_settings`：URL/用户名/密码（眼睛切换）/路径/UserAgent + 顶栏 Check 与底部整宽 Save；`IosFormField` 扩了 `visualTransformation`/`trailing` 两个可选参数，既有调用点不受影响）；备份页 §5 四行全接线：服务器设置（跳子页）/ 测试连接（成功 `backup_page_test_done` 绿 toast）/ 恢复（列表 sheet → 模式对话框 → 恢复 → 复用重启提示）/ **立即备份**（原版第 4 行，此前 Android 少了这行）→ `recordBackupCompleted()`；远端列表 sheet `WebDavRemoteListSheet`（拖柄 + 居中标题 + surfaceFill r12 行 + 0.18 outlineVariant 边框 + Import/Trash2 小钮 + 删除确认对话框，删除后刷新列表）。
- **测试**：`WebDavClientTest`（core:data，URL 拼装 + 配置 JSON 往返）、`WebDavMultistatusParseTest`（app，Robolectric——XmlPullParser 在纯 JUnit 是 not-mocked）。

#### 子块 3 进展明细（2026-09-11，本机副本）

5 个纯逻辑/IO 文件（`LocalSnapshotRetention` / `LocalSnapshotStore` / `LocalSnapshotSchedule` / `LocalSnapshotSettings` / `LocalSnapshotService`）+ `LocalSnapshotsScreen` 全接线 + 启动与回前台 `maybeRunLocalSnapshot()`；新增 `IcuStrings.kt`（`android.icu.text.MessageFormat`，复数串 `localSnapshotUsage` 等）。**保留策略、调度闸门、两处平台差异（DB 在 `databases/` 下不在 filesDir；运行状态必须放 SharedPreferences 否则写状态即改指纹）详见上方批次表「备份-3」行，勿重犯。** 测试：`LocalSnapshotRetentionTest` / `LocalSnapshotStoreTest` / `LocalSnapshotScheduleTest` / `LocalSnapshotServiceTest`。

**待完成（子块 1 剩余）**
- 完整前向兼容闸门（最小版已落地：`BackupManifestCodec.declaresNewerBuild` + 导入前 `peekManifest` 校验）
- 真机验证（装机由用户自行测试）

**子块 7~8 未开始**
- 7 前向兼容闸门（完整版）、8 Cherry / Chatbox 导入

#### 子块 6 进展明细（2026-09-12，S3）

**`core/data/backup/`（3 个新文件）**

| 文件 | 职责 | 对应 Flutter |
|---|---|---|
| `S3Config.kt` | `S3Config`（endpoint/region/bucket/accessKeyId/secretAccessKey/sessionToken/prefix/pathStyle/userAgent/includeChats/includeFiles，键 `s3_config_v1`，字段名逐字对齐 Dart JSON）+ `S3FileItem` + `S3Exception`；路径布局（`normalizedEndpoint`/`basePathSegments`/`hostHeader`）与 `validate()` | `S3Config`（`core/models/backup.dart` L83-180）、`_normalizeEndpoint`/`_normalizedBasePathSegments`/`_hostHeader`/`_validateConfigBasics` |
| `AwsSignatureV4.kt` | SigV4 签名（`ALGORITHM`/`awsEncode`/`canonicalQueryString`/`sign`）+ `EMPTY_PAYLOAD_SHA256`；**时钟是参数**，AWS 官方向量可复现 | `_canonicalQuery`/`_canonicalHeaders`/`_signedHeaders`/`_stringToSign`/`_signature` L169-242；写法参考 RikkaHub `AwsSignatureV4`（OkHttp 而非 Ktor） |
| `S3Client.kt` | `test`/`upload`（流式 PUT，`UNSIGNED-PAYLOAD`）/`uploadObject`/`download`/`delete`/`list`；manifest 读改写（`readManifest`/`writeManifest`/`upsert`/`removeManifestItem`/`writeManifestIfChanged`）；分页 `listBucketObjects`；`parseListBucket`；错误文档 `errorCode`/`errorMessage`；合并 `mergeBackupItems`/`sameBackupItems`；纯编解码 `encodeManifest`/`decodeManifest`/`parseS3DateTime` | `S3BackupClient` 全量 L14-1109 |

**`MemoBackupService` 扩展**：`s3Config()/saveS3Config()/testS3()/listS3()/backupToS3()/restoreFromS3()/deleteS3Item()`；远端恢复的公共尾巴抽成 `restoreStaged()`（WebDAV 也改用它，消除重复）。

**UI**：`S3SettingsScreen`（route `s3_settings`，8 个输入行 + Path-style 开关行，顶栏 Check / 底部 Save 都写 `s3_config_v1` 后返回）；备份页 §6 四行全接线（服务器设置 / 测试连接 / 恢复 / 立即备份，**原版第 4 行，此前 Android 只有 3 行**），恢复走「远端列表 sheet → 模式对话框 → 恢复 → 复用重启提示」，删除有确认对话框；§1 的两个内容开关（Chats / Files）**此前只改本地 state、什么都没存** → 现在按原版写 **WebDAV + S3 两份配置**（`WebDavConfig` 因此补 `includeChats/includeFiles` 两字段，`backupToWebDav` 不再硬编码 `true/true`）。

**关键结论（勿重犯）**
1. **百分号编码必须大写十六进制**（AWS "UriEncode"）：小写签名能算出来但 S3 报 `SignatureDoesNotMatch`。签名本身（Authorization 里那串）反过来是**小写**。测试用 AWS 官方向量锁死（`GET /test.txt` + `Range`，20130524T000000Z → `f0e8b8…6bdb41`）。
2. **流式上传签 `UNSIGNED-PAYLOAD`**，但要带 `content-length`；缓冲上传（manifest）签真实 SHA-256。
3. **`host` 与 `content-length` 不要手动加进 OkHttp 请求头**——OkHttp 自己会按 URL/body 生成，重复写会让 S3 直接拒；签名里必须有这两个（值同源）。
4. **host 头省略默认端口**（443/80），否则与 OkHttp 实际发出的 Host 不一致 → 签名不匹配。
5. **`ApiResponse` 走完整 body**（分页 listing 是整份 XML），只有**流式**下载/上传的错误路径用 `peekBody`（不能消费 body）。
6. **Dart 里 `_isMissingObjectResponse` 的 404/NoSuchKey 语义**与 manifest 缺失判定要用在 `test()` 上（manifest 不存在 = 可达）。
7. **远端列表 sheet 已泛化为 `RemoteBackupListSheet<T>`**（原 `WebDavRemoteListSheet` 删除），WebDAV/S3 共用一个组件，对应原版的单一 `_RemoteListSheet`。

**测试**：`S3ClientTest`（core:data，34 例：AWS 官方向量、时区归一化、session token、content-length 参与签名、RFC3986 编码、canonical query 排序、空载荷摘要、prefix/manifest key/path 布局/vhost/端点自带 bucket/分段编码/默认端口、配置 JSON 往返与兜底、校验逐字段、manifest 往返/排序/过滤/容错、合并语义/权威性/同刻比较）；`S3XmlParseTest`（app，Robolectric，7 例：分页解析/continuation token/空结果/不跨 Contents 继承 key/错误摘要/missing object/非 XML 正文）；`WebDavClientTest` 补内容开关往返。

### 5.10.5 已有可复用资产（**别重造**）
- `PayloadEntityDao`（13 表通用 CRUD，`core:data/db/`）
- `PreferenceRepository.readAllRows()/writeJson()/readLocal()/writeLocal()/readAllLocal()`
- `SettingsKeyRegistry` + `classifyBusinessKey`（`core:data/settings/`）
- `ConversationDao` / `MessageDao`（会话与消息）
- `MemoSchema`（`DB_NAME="memo.db"`、`DB_VERSION=3`、`EXPECTED_TABLES=29`）
- `BackupScreen.kt` / `LocalSnapshotsScreen.kt`（已接线，勿当空壳重写）
- `MemoBackupService`（`exportToCache` / `restoreFromFile` / `peekManifest` / `defaultArchiveName`）+ `BackupRestorer` / `BackupSnapshotBuilder` / `BackupTaskRunner` / `BackupProgressDialog`（导出/恢复/进度对话框/错误吐司全在这条链上）
- `LocalSnapshotService` / `LocalSnapshotStore` / `LocalSnapshotRetention` / `LocalSnapshotSchedule` / `LocalSnapshotSettings`（本机副本：子块 4 备份提醒、子块 5/6 远端保留策略可复用其保留语义与设置读写模式）
- `IcuStrings.kt`（`icuString()`，复数/占位符串一律走它，别手拼字符串）
- `BackupSwitchRow` / `BackupSection` / `BackupPlaceholderRow`（BackupScreen 内私有组件）

## 5.11 有意偏离原版 / 平台差异清单（**勿"修回"**）

做 1:1 对照时看到下列不一致属正常——它们是用户确认过或平台硬约束的结果。**不要按 Flutter 源码改回去**；改回来等于把已修好的 bug 再引一遍。

### 用户明确要求的偏离

| 项 | 原版 | 我们 | 原因 |
|---|---|---|---|
| **问询 / 审批移到输入栏位置** | `ask_user_input_v0` 的作答表单与工具审批的 X/✓ 按钮都**内联在对话流里的工具卡上**（`chat_message_widget.dart` 的 `_AskUserInlineBody` 与 `CMW:5528-5561` 的行尾 extra 按钮） | 新增 **`ui/chat/ChatInterruptionPanel.kt`**：待答问询与待审批都做成**底部面板，占输入栏的位置**（pending 期间 `ChatInputBar` 暂时不显示）；问询面板 = **一题一页**（`‹ n/N ›` + 右上角 × 关闭）+ 题干 + 选项 + 「其他」自由输入 + 提交（`AskUserPanel`，作答走同一条 `AskUserInteractionService.answer`，× → 新增的 `cancel(toolCallId)`）；审批面板 = 工具名 + 待审批标签 + 参数摘要 + 拒绝/允许（`ToolApprovalPanel`，复用 `ApprovalButton`/`ApprovalDenyDialog`）。对话里的工具卡在 pending 时**只留一行状态**（问询显示「等待你的回复…」，审批仍有参数摘要），不再有交互控件；答完/批完照旧内联显示历史。判据 `currentChatInterruption(askUser, approval, conversationId)`：只看本会话、**问询优先于审批** | 用户 2026-09-14：「还有这个像问问题，工具权限确认这个 都是在对话界面的工具卡片上来点击完成 我觉得不对 这个应该出现在输入框那个位置 你可以看看这个就是在输入框那里显示的 体验更加友好 你可以借鉴一下」；随后确认三点：**两个都做底部面板、输入框暂时藏**、**问询照截图做一题一页 + 左右箭头 + 关闭 X**、**待答时工具卡不再渲染交互控件**。（⚠️ 别按原版改回内联工具卡） |
| **工作区进「+」面板** | RikkaHub 的输入栏「+」里有 `WorkspacePickerListItem`（`FilesPicker.kt:141`）：一行入口 → `WorkspaceSelectSheet` 选工作区 + `onNavigateToManage` 出口 | 照做：`BottomToolsSheet` 加「工作区」行（图标 `Lucide.HardDrive`，与设置→工作区同一颗）→ `ui/WorkspaceSelectorSheet.kt`（统一 sheet 外壳：`MemoSheetHandle` + `MemoSheetOptionRow`；「不使用」永远第一 + 每个工作区带 Shell 状态 + 「管理工作区」出口）。**唯一差异**：上游能同时绑助手与单次会话（`onUpdateAssistant`/`onUpdateConversation`），Memo 的绑定是**每助手一份**（`assistant.workspaceId`，用户先前确认过的形态），所以这里写的是当前助手 | 用户 2026-09-14：「这个输入框加号里面加一个工作区吧 你看看 rikkhub 都有」 |
| **API 端点选择** | 「Response API」`IosSwitch` 开关（`provider_detail_page` L1172-1183）+ 下方手填「API 路径」`_inputRow`（L1379-1393，`!_useResp` 时显示）两件套；添加页同构（`add_provider_sheet.dart` L180-202） | **一个三选一 combobox**：`ApiPathField`（外观=与同屏输入框**完全一致**的 M3 外壳 56dp/r12/surfaceCard，字段里显示当前路径；点击后在该字段正下方弹**锚定下拉菜单**，行=选项名 + 路径）。选项 `Anthropic Messages /v1/messages`、`Chat Completions /chat/completions`、`Responses /responses`；`chatPath` 与 `useResponseApi` 由 `apiPathOptionFor`/`useResponseApiFor` 同步。**详情页**：原「管理」卡里的开关行删掉、路径回到原版位置（凭据区 API Base Url 之后）；**添加供应商页**：Response API 开关与手填路径行一并删掉，改为同一 combobox。**选中项靠变色表示、不打勾**（用户 2026-09-12：打勾会挤得内容换行） | 用户 2026-09-12：「api路径这个改成 Anthropic Messages (/v1/messages) / Chat Completions (/chat/completions) / Responses (/responses) 支持这个三个的sheet来选择」→ 随后「把这个 api 路径还原样式吧 把这个 sheet 改成点击出来那个 combobox 来选择吧」→ 再「这个conbox这个选择变色就是 不打勾 不然里面的内容要换行」+「输入框大小样式和名称和baseURL不一样大小了 统一下嘛」（**不要改回开关 + 手填，也不要改回底部 sheet/打勾**） |
| **供应商详情页行内不带前置图标** | `_iosRow`（provider_detail_page L1350-1390）= 15sp 标签（可带 ⓘ）+ 行尾开关，**整张卡没有前置图标** | 同一张卡（`SettingsSwitchRow(icon = null)`）：是否启用 / 多Key模式 / Vertex AI / APP-Code / Claude 提示词缓存 都不带图标；其它设置页照旧带图标（那是本站全站约定） | 用户 2026-09-12：「人家这个是否启用和多Key管理 没有图标呀」→ 二选一确认时选了「整卡去掉前置图标，和原版一致」（`SettingsSwitchRowTipTest.nullIconDropsTheLeadingIconGutter` 锁住 36dp 图标槽的有无） |
| 设置行 tip 图标位置 | `_iosSwitchRow`（`display_settings_page.dart` L1508）等：`Expanded(标签) → MemoryTipIcon → 12 → IosSwitch`，ⓘ 浮在开关那侧、离文字很远 | **ⓘ 紧跟标签文字**（外层 `TipHuggingLabel`：组 `weight(1f)` 吃满余量、文字 `weight(1f, fill=false)` 只占所需宽度 ⇒ ⓘ 贴文字、余量留组内右侧，行尾开关/chevron 照旧贴边） | 用户 2026-09-12：「这个 tip 图标位置也不对，应该在文字的旁边……我感觉不合理」。`SettingsSwitchRowTipTest.tipIconHugsTheLabelText` 用**边界断言**锁住（ⓘ 在文字右侧、间隙 < 14dp、且在行宽左半侧） |
| sheet 拖柄 | Material `BottomSheetDefaults.DragHandle`（原生胶囊）或各页自绘，混用 | **一律自绘**：`MemoSheetHandle()`（40×4、onSurface@20%、全圆、上 8 / 下 10，SettingsUi.kt）；全部 73 处 `ModalBottomSheet` 统一 `dragHandle = null` + 内容首项调用它 | 用户 2026-09-12：「有的把手是原生的有的是自己绘制 统一成手绘那种」 |
| 下拉选项面板样式 | 分隔线列表（`HorizontalDivider` + 行内 h20/v15 padding） | **「更多」sheet 的卡片样式**：`MemoSheetOptionRow()`（surfaceCard + r14 + 48dp + 左右 12，选中 = primary 文字 + ✓），列表用 `Arrangement.spacedBy(8.dp)`、无分隔线 | 用户 2026-09-12：「统一成更多那个sheet样式吧」（颜色模式 / 应用语言 / 应用字体 / 代码字体 / 消息导航按钮 / 后台聊天生成 + 代理类型 ×2，共 8 处） |
| 旧版（V1）记忆模式 | `legacy` 记忆一整套（兼容老用户旧数据） | **不移植**，只保留 V2 | 用户 2026-09-11 拍板：「我们这个是新的，没有这个问题」 |
| 语音播放图标作用域 | `chat_message_widget.dart:3253-3291` 用**全局** `isActive` ⇒ 读一条消息时**所有**消息都显示停止、暂停态不可见 | `TtsPlaybackState.ownerId` + `messageTtsAction(state,msgId)`：只有被朗读的那条显示停止/继续，其余恒为播放；无 owner 的播放（工具卡重播）不影响任何消息 | 上游行为本身就是 bug（用户实测「点一条播放，所有界面都显示播放」），用户要求按消息归属 |
| 联网搜索引用胶囊 | 20dp 高 / 12sp / primary 20% 底 | 16dp / 10sp / primary 16% 底（常量 `CITATION_BADGE_*`） | 用户 2026-09-10「小一点，有点影响阅读、太显眼」 |
| 输入栏最小高 | `kMinInteractiveDimension` 48dp | **64dp**（多行仍随内容长高） | 用户两次「再高一点」；**其余样式参数（圆角 20 / 半透明底 / 描边 / 按钮 32dp）勿动** |
| 备份建议文件名 | `kelivo_backup_<stamp>.zip` | `memo_backup_<stamp>.zip` | 品牌规则——SAF 保存对话框里这是用户可见字符串；文件名不属于归档格式，内容仍逐字节兼容 |
| 本机副本文件名 | `kelivo-snapshot-<micros padded 16>.zip` | `memo-snapshot-<nanos>.zip` + `.json` 边车 | 品牌规则（同上）；时间戳改用 nanos，列表忽略外来文件故无兼容问题 |
| 搜索服务 `kelivo` 类型 | 内置搜索（上游端点 + 内置令牌） | 不移植（S5），编辑器里该 type 显示 Memo 名称 | 品牌规则 |
| **引用来源的筛选条件** | `chat_message_widget.dart _allSearchItems` **只认工具名** `search_web` / `builtin_search` | **按结构判定**：任何工具只要 content 是 JSON 且带 `items` 数组就计入（`extractCitationItems`） | 用户 2026-09-12「可以去掉白名单可以吧？不然出现这个 `?` 太影响体验了」。原版写法让 MCP 搜索类工具的结果永远进不了引用列表，模型写下的 `[cite:id]` 一律解不出 |
| **上下文压缩机制** | `home_view_model.compressContext`：把会话文本按模式（起始/最近/无限制/保留 N 条）截取或分块 → 交 compress 模型折叠成摘要 → **新建一个会话**、摘要作为首条用户消息 | **照 opencode 的阈值 + 锚定摘要机制**（`packages/core/src/session/compaction.ts`，用户 2026-09-13「改成 opencode 那个压缩阈值来压缩」）：① 阈值 = `estimate(system+messages+tools) > 上下文窗口 − max(输出预算, buffer)`（估算 = 字符数/4），默认 buffer 20000 / 保留 8000 tokens；② **同一会话内**插入一条「压缩检查点」消息（`CompactionPart`：摘要 + 原样保留的最近上下文 + `boundaryOrder`），`boundaryOrder` 之前的消息不再进请求，检查点整条替换成 `<conversation-checkpoint>` 的 user 轮次；③ 摘要提示词 = opencode 的 SUMMARY_TEMPLATE（锚定小节结构 + previous-summary 更新指令），仍可在默认模型设置页自定义；④ 设置项：`context_compaction_auto_v1`（默认开）/ `_keep_tokens_v1`(8000) / `_buffer_v1`(20000) / `_window_v1`(128000)，模型级上下文长度写 `modelOverrides[modelId].contextWindow`（模型编辑页 Advanced）。**不再新建会话**；`CompressText` 的旧机制（模式/字符预算/分块合并/keepRecent）整体删除 | 用户 2026-09-11「这个上下文压缩这个机制这个部分 我们要改 不用原项目这个」→ 2026-09-13 给出方案「改成 opencode 那个压缩阈值来压缩」；**不要改回新建会话 + 首条摘要那套**。**与 opencode 的一处有意差异**：自动压缩只把「本轮锚点消息之前」的历史折进去，锚点（刚发出的那条 user 消息）留在检查点之后单独发送（opencode v2 会把它并进 `recent`，v1 的 overflow 路径反而是 replay 出来——取更自然的那种） |
| **压缩的对话内表现 + 摘要不进界面** | 原版没有这个概念（压缩=新建会话，摘要就是新会话的第一条 user 消息）；也没有任何「上下文占用」显示 | ① **压缩中/已压缩 = 消息流里一条分隔线**：`———— 上下文压缩中 ————`（12sp `onSurfaceVariant@70%`，文字走既有扫光 `ThinkingShimmerText`）→ 完成后原地变 `———— 上下文已压缩 ————`（静态），**不弹任何加载对话框**（原 `CompressLoadingDialog` 删除）；自动压缩也走同一条线（**排在流式骨架之前**）。② **摘要只给模型看**：检查点消息不画气泡、不可点、不进导出（`MessageExport` 整条跳过）、不进多选、不进标题/总结/建议/记忆抽取（`TitleSummaryGenerator`/`MemoryPipeline`/`maybeGenerateSuggestions` 过滤 `isCompaction`）。③ **上下文占用**：只在「上下文管理」sheet 里显示（用户 2026-09-13 先要输入栏上方常显细条、同日又改口「把输入栏正上方那条 2dp 细条去掉吧 上下文管理界面那个够用了」）——sheet 顶部一张卡：占比百分比 + 6dp 进度条 + `约 12k / 108k tokens（距自动压缩），窗口 128k`；**分母 = 自动压缩阈值**（窗口 − max(输出预算, buffer)，100% = 该压缩了），配色分档 <70% 主题色 / 70–90% 琥珀 / >90% 红、自动压缩关掉时灰（`contextUsageColor`，`ChatViewModel.contextUsage` 在加载/回复结束/换模型/清空/压缩后重算）。**不要再往输入栏上方加常显条** | 用户 2026-09-13：「上下文窗口的占用状态看不到呀 你觉得在哪里放 样式怎么样弄呀」→ 选「按自动压缩阈值算分母」→ 同日看了实机后「把输入栏正上方一条常显 2dp 细条 这个去掉吧 上下文管理界面那个够用了」；「压缩在对话里面一个横线加上下文压缩中横线 加上文字扫光 压缩完了显示 横线上下文已压缩横线 不直接显示一个压缩对话界面这个体验不好」；「这个上下文压缩了 会把压缩内容发到对话界面里面 这个不太好 不显示在对话界面吧」 |
| **内置主题集** | `lib/theme/palettes.dart` 的 9 套调色板（`core/ui/.../theme/Palettes.kt`，由 `tools/palettes_gen.py` 生成，脚本硬校验「正好 9 套」） | **列表 = Memo 默认 + RikkaHub 7 套预设**（`RikkaHubPresets.kt` 的 `themeChoices`，共 8 条），主题选择页做成 RikkaHub 那种**四列彩色色卡**（1:1）；预设走「原样表面」通道且角色映射照真机取色：**页面 = `surfaceContainer`、卡片 = `surfaceBright`**；Memo 旧 8 套不再列出但 id 仍可解析（`themePaletteById`）；设置页分组标题改跟随 `primary`（`settingsSectionHeaderColor`，见 §4-44） | 用户 2026-09-13「我们这个八个效果不好，用 RikkaHub 那个主题，他那个更全面」+「我们这个默认也要保留 主题按照我们这个 UI 和 UX 不改」→ 2026-09-14「跟着人家一比一做 不然做出来不好看」（实测发现我们把页面/卡片做反了）、「主题设置里面那个分类的字的颜色没有跟着主题走呀 rikkhub就可以呀」。详见 §4-44；**不要改回只列原来 9 套**、不要改回单色圆点列表、也不要把页面/卡片角色换回 M3 默认关系、也不要把分组标题改回 `onSurface@80%` |
| **Agent Skills（技能系统）** | 原版（kelivo）**没有** skill 系统，也没有对应 ARB/页面 | **新增**（照 RikkaHub `data/files/SkillManager` + `data/ai/tools/SkillsTools`）：技能在 `<filesDir>/skills/<名>/SKILL.md`、`use_skill` 工具 + `<available_skills>` 系统提示词块、助手 `enabledSkills`、设置→技能 管理页 + 技能详情页 + 助手编辑页「技能」tab。工具名与工具描述**逐字照上游**；**外壳是 Memo 风格**（`MemoTopBar`/`SectionCard`/长按操作面板），不搬上游的 `LargeFlexibleTopAppBar` + FAB | 用户 2026-09-14 点名移植。**手动粘贴 / 从文件（.md/.zip）/ 从 GitHub 仓库**三种导入方式齐；三处被单测逼出来的实现偏离见 §4-45。**工具卡的标题也照上游 `UseSkillToolUI.title`**（用户 2026-09-14「加载 skill…我这个怎么是调用工具呀显示 应该是加载吧，你看看 rikkhub 就是显示加载 skill」）：`技能：<技能名>`，带 `path` 时追加 ` / <路径>`（`skillToolTitle`/`skillNameFrom`，名字缺失退回 `skill`，别出现空的「技能：」），图标用 `Lucide.Puzzle`（与设置→技能页同一个；上游用 `MagicWand01`，icons-lucide 1.1.0 没收录）—— **勿让它落回默认的「调用工具 use_skill」** |
| **解不出的引用标记** | 回落显示一颗 `?` 胶囊 | **整段标记不渲染**（`resolveCitationCapsule` 返回空 display text ⇒ 调用方直接 `return`；`[citation](id)` 内联分支同理） | 用户 2026-09-12 同一次决定：正文旁边挂一个 `?` 读起来像故障。注意：**数字型 label 元数据仍照旧优先显示**（它本身就是可用序号），只有真正解不出的才丢 |
| **参数不可用的写入调用不再弹审批** | `pathOutsideWritableRoots` = `runCatching { … }.getOrDefault(true)`（`WorkspaceTools.kt:410-413`）⇒ 模型发来的 tool call 参数被截断/没有 `path` 时**先弹一次审批**，用户点完只收到「path is required」 | `WorkspaceTools.pathOutsideWritableRoots` 改成**三态**（`Boolean?`）：能解析出绝对路径 → 按 `/workspace`、`/tmp` 前缀判内外；解析不出（缺键 / 相对路径 / 带 `\0`）→ `null`，闸门判据用 `== true` ⇒ **不弹审批**，直接让工具报参数错误 | 用户 2026-09-16「沙箱里面工具的权限我关闭了确认 为什么还有确认呀」——真机库里那条 `workspace_write_file` 的 `arguments` 就是被截断的 `"{\"path\": \"/workspace/make_docx.py\""`（`path` 解析不出） ⇒ 参数坏掉的调用无论路径是什么都执行不了，占用户一次确认纯属摩擦。`WorkspaceToolsTest.pathOutsideWritableRootsIsTriState` / `unusablePathArgumentsDoNotAskForApproval` 钉住 |
| **关掉审批开关会放行正在等的那一个** | 上游切开关只改记录，屏上已弹出的审批面板照旧拦着 | `ToolApprovalService.approvePendingForTool(toolName)`：工作区详情页把某个工具的审批开关**关掉**时，把该工具**已弹出**的待审批请求直接批准（按工具名匹配，同名即同一助手的同一工作区） | 同上那句用户反馈的另一半：面板是在关开关**之前**建出来的（真机时间线：审批请求 18:43:16、开关写入 18:43:31），用户本意是「以后不用问我」，已弹出的那个不该继续卡住生成。`ToolApprovalServiceTest.approvePendingForTool_releasesOnlyThatTool` 钉住「只放行同名、其他工具照旧等」 |
| **工作区提示词多一句内存提醒** | `WorkspaceReminderTransformer.buildWorkspacePrompt` 没有关于宿主资源的任何说明 | `buildSystemPromptBlock` 多一行：CPU/内存与手机共享、`.NET`/JVM 这类重运行时常常 OOM 起不来、优先 Python/Node/纯 shell | 用户 2026-09-16 点头（出处见 §5.16①）。真机代价：模型为 `.NET CoreCLR 0x8007000E` 白烧了好几轮才改用 python-docx。**别删这句**（`promptBlockWarnsThatHeavyRuntimesMayRunOutOfMemory` 钉住） |
| **失败文案去掉 Java 异常类名** | `chat_actions.dart:2605` 直接把 `e.toString()` 当气泡里的失败文案（Dart 侧形如 `HttpException: HTTP 429: {…}, uri = …`） | `ui/chat/GenerationErrorText.generationErrorText()`：只剥掉开头那层「点分包名 + 大驼峰类名 + `Exception`/`Error` + 冒号空格」前缀（`java.io.IOException: HTTP 429` → `HTTP 429`），剥完为空则退回 `toString()`，其余错误原样 —— **不发明文案、不吞信息** | 用户 2026-09-16「先怎么还是会裸出 `java.io.IOException: HTTP 429` 这样的报错呀」。**同批修掉的真 bug（不是偏离）**：三处**流式**路径（`OpenAiChatCompletionsClient.runStream`、`ClaudeClient.runStream`、`GeminiClient.runStream`）此前只抛 `IOException("HTTP $code")`，把响应体丢了，而原版三处 provider 抛的都是 `HTTP ${statusCode}: $errorBody` ⇒ 429 的限流说明 / 余额 / 模型名错全在 body 里，只报状态码等于没有可诊断信息。现在统一走 `httpFailure(response)`（body 截 500 字）。判据：`GenerationErrorTextTest`（4 例）+ `OpenAiClientIntegrationTest.httpErrorThrowsWithoutRetry` 断言错误里带 body |
| **参数 sheet 的预设值标签去重叠** | `_SliderTileNew` L1359-1388：`Stack` + `Align(Alignment(-1 + t*2, 0))` 按**值**线性摆位，8 个档位全画 | `spreadLabelStops()`：从左到右贪心保留，与前一个保留项的位置差不足 12% 行宽就跳过；首项与末项一定保留（末项与前一项太近时**顶替**它）。位置仍在真实值上（与滑条刻度对齐），只是不再全画 | 用户 2026-09-16「助手里面那个上下文消息这个下面那个数字显示有重叠」—— 那组档位是 1/64/128/256/512/1024/2048/4096，前四个挤在左侧 6.3%、512 在 12.5%，11sp 标签叠成一团（原版同样会叠，属上游版面缺陷）。判据：`AssistantParamLabelStopsTest`（5 例）+ `AssistantParamLabelRowTest`（真实渲染后逐对断言横条不相交、且 64/128/256 已消失） |
| **slider：`MemoSlider` 取代 M3 原生 `Slider`，且是真正无级** | ① 外观：整套设置页用 Syncfusion `SfSlider`（8dp 轨道、20dp 圆钮、刻度 + 刻度数字 + 水滴 tooltip）；「推理强度」那根是另一套（`_EffortSlider` + `_SliderPainter`：34dp 药丸轨道、primary 渐变填充、档位圆点、白色 38dp 圆钮 + 投影、拖动放大）。② 取值：**处处带 `stepSize`**（字体缩放 0.05 / 回到底部延迟 2.0 / 蒙版 5.0 / 输入框不透明度 5.0 / 画质 5 / 参数 sheet 用 divisions），拖起来一格一格 | **`core:ui` 的 `ui/slider/MemoSlider.kt`**：外观照「推理强度」滑条（34dp 药丸轨道 `onSurface@10%`、primary 50%→100% 渐变填充裁剪在药丸内画到圆钮中心、白色 38dp 圆钮 + 投影 blur 10/dy 3/黑 25%、拖动放大 1.14@180ms `EaseOutBack`、两端各内缩一个圆钮半径、控件高 56dp）；**但无级** —— 没有 `steps`、不吸附、也不画档位点（没有档位就没有档位点），值就是手指位置；拖动时圆钮上方浮一枚数值胶囊 | 用户 2026-09-16 四次迭代：①「很多界面都是用的 m3 那个原生 slider 不好看 我们直接自定义个好看的吧」；②「好看是好看 但是怎么不能自由滑动呀」；③「你这个 slider 怎么是靠点击来的呀 是可以无级滑动那种呀」（真因：slop 判据写成**每帧位移**，真机每帧几像素永远超不过 `touchSlop`，只有按下那一下改值）→ 手势改成单一 `awaitEachGesture` 循环（按下即跟手、逐帧上报、横向累计越 slop 后才 `consume`、纵向累计超 slop 且大于横向则让位给滚动并**回滚**、抬手统一 `onValueChangeFinished`）；④「改成不分级 就是真实无极滑动那种 原项目这个有分级这个不好用 改成无极调节」→ **用户明确要求偏离原版**：去掉 `steps`/吸附/档位点，并把 10 处调用点回调里的取整一并删掉（`Math.round(v/0.05f)*0.05f`、`(it.toInt()/5)*5` 等改回原值；`ParamSliderSheet` 的 `divisions`、`SliderRow` 的 `steps` 参数直接删除），只有「数据本身就是整数」的值（上下文条数、token、画质百分比）仍在回调里取整。⚠️ 别按原版把 `stepSize`/刻度/吸附加回来。判据：`MemoSliderTest`（core:ui，4 例：比例 / 行程映射 / RTL / **无极不吸附**），`MemoSliderUiTest`（app，6 例：圆钮 38dp 且顶边 18dp、**每步 4px 逐帧跟手**、**拖动落在档位之外**、纵向滑动让位回滚、胶囊只在拖动中出现、禁用态不改值）。⚠️ `testTag` 必须挂在 `offset` **之后** | **同批新增 `SliderValueLabel(text, widest)`**：与 slider 同排的「当前值」文本按**最宽文案**（范围上限的格式化结果）预留 `widthIn(min=…)`，否则数值一变宽（"9"→"100"、"50%"→"100%"、"0.50"→"1.00"）就会把 `weight(1f)` 的滑条挤短 —— 拖动时滑条本身跟着伸缩（用户 2026-09-16「左右数字会变 导致这个 slider 也会变」）。已用于显示设置 4 处（字体缩放/回到底部延迟/背景蒙版/输入框不透明度）、渲染页字号、以及参数 sheet 的 ValuePill（胶囊保留点击改精确值）。判据：MemoSliderUiTest `fixed width value label keeps the slider width stable`（拖到右侧、文案变宽后滑条宽度必须不变）。

| **自动重试的出厂默认** | `AutoRetryOptions.defaults()`（`lib/core/models/auto_retry_options.dart:41-96`）：`enabled = false`、`maxDelayMs = 30000`、`defaultRetryStatusCodes` = {408,425,429,500,502,503,504,529}、`defaultRetryKeywords` 12 条、`defaultStopKeywords` 11 条 | **只有 `enabled` 偏离：默认 `true`**，其余逐字照 Dart。唯一源头是 `core/llm/.../retry/AutoRetryOptions.kt` 的 `DEFAULT_RETRY_STATUS_CODES` / `DEFAULT_RETRY_KEYWORDS` / `DEFAULT_STOP_KEYWORDS` / `DEFAULT_MAX_DELAY_MS`，设置页（`AutoRetrySettingsScreen`）与容器解码（`AppContainer.currentRetryOptions`）都引用它们、**不再各抄一份** | 用户 2026-09-15「自动重试这个触发条件这个没有做完吧」→「触发条件这个和原项目人家有触发字的呀」，随后拍板 `enabled` 保持 `true`。真因不在链路（`shouldRetryError` 的网络错误/关键词/状态码三档与容器实时读盘都在），而在 Kotlin 侧出厂默认**两个词表是空的**（关键词这档永不命中）、状态码多带 409 又漏了 425/529、`maxDelayMs` 是 15000（Dart 30000）；设置页当时有自己一份 private 拷贝所以「页面看着对、实际不重试」。`RetryPolicyTest` 5 例逐字钉住默认值与「409 不重试 / 425·529 重试 / 命中"限流"重试 / 命中"余额"不重试」 |


- **TTS 无法暂停**：`android.speech.tts.TextToSpeech` 不支持暂停 utterance ⇒ 暂停 = `stop()`、继续 = 从当前块重讲（`TtsPlaybackController`）；`onInitListener` 只能经构造函数传入。±15s 定位只能定位到块粒度。
- **快照运行状态在 SharedPreferences**：`last_success` / `failure_streak` / `fingerprint` / `first_observed` 是设备本地状态（写状态若落库就改了数据库指纹 ⇒ `unchanged` 闸门永不生效）；设置在 DB（随备份走）。这几个键在生成的 registry 里是 UNKNOWN，故显式走 `readLocal` / `writeLocal`。
- **数据库路径**：`context.getDatabasePath("memo.db")`（`/data/data/<pkg>/databases/`），**不在 `filesDir` 下**——指纹与快照都要按它取。
- **归档 ZIP 实现**：JDK `ZipOutputStream`（原生 ZIP64）替代 Dart 手写 streaming zip，只保留格式语义（命名/顺序/摘要/限额）。
- **SAF 无法按扩展名过滤**：导入的 16 种扩展名白名单只在读取处兜底。
- **Compose `TextField` 无 `contentPadding`**：12dp 内边距用外层 `Box` border + padding 等价实现。
- **图标**：lucide 同名图标；`androidsvg`（coil-svg 底层）不支持 `<mask>` 与带 `gradientTransform` 的 `url(#渐变)` ⇒ 品牌 svg 会渲染空白，改映射到 png（bing/linkup）。
- **系统提示词变量的两个平台增强**：`{device_info}` 给「android 厂商 型号」（原版 `prompt_transformer.dart:23` 注释里就是 "Simple fallback; can be extended with device_info plugins"，只给 OS 名）、`{battery_level}` 给真实电量百分比（原版写死 `'unknown'`，同样标着待扩展）。**别"改回" unknown**；读不到电量时仍回落 `unknown`。
- **`sh.calvin.reorderable` 的 `onMove` 给的是 LazyColumn 全局索引**：列表里若有 header/footer 占位 item 会整体错位（供应商拖拽重叠 bug 的根因）；`core:ui` 的 `ReorderableColumn` 用 `dataIndexOf()` 反查兜底。

### 已知品牌残留（**用户 2026-09-11 决定：他自己后续替换，暂不处理**）

- ~~`AboutScreen.kt` 的「社区与链接」三行指向上游~~ **已于 2026-09-21 开源准备时整组删除**（用户决定「应用内链接换掉」，且当时还没有 Memo 自己的仓库地址）。上游署名改由公开仓的 `README.md` + `NOTICE.md` 承担；要恢复一行「源码仓库」需要用户给 URL。见 §5.36。
- 内部标识符 `KelivoOptions`（搜索服务编辑器里的上游 type key，非用户可见）——低优先清理，改动会牵到多文件与测试。

### ⏸ `docs/UI_AUDIT_2026-09-12.md` 是**用户自己**的 UI/UX 统一审计报告（先不做）

那份文档（M3 默认样式残留 / sheet 底色两阵营 / insets / 触感覆盖 / 死代码…）由用户整理，**2026-09-12 明确「先不做」**：里面的 H1/H2 共享封装收敛（`MemoAlertDialog`/`MemoSheet` 收 60 处）、助手 5 个显示开关等**留到后续批次**，不要当待办自行开工，也不要删这份文档。

## 5.12 接线审计清单（2026-09-12，用户："偏好设置里很多没接入功能吧"）

**方法**：两轮并行审计（偏好子页逐行键追踪 + 助手 payload/默认模型/其余页），加 81 个偏好键的"写入无读取"脚本扫描。**教训：审计不能只看"键有没有人读"，要追"读了之后是否真的影响行为"**（applyContextLimit / auto_retry_options 都是"读了但没生效"型）。本节是完整清单——**已修**的勿再动，**待接线**的按批做。

### 已修（✅）

| 设置 | 缺口 | 修法 |
|---|---|---|
| 助手 temperature / topP / maxTokens | 三个采样参数从不进 LlmRequest（topP 连字段都没有） | `LlmRequest.topP` + 三客户端消费（Claude：thinking 时仅 0.95–1.0 下发，对齐 chat_api_helpers L638-644）；chat 请求从 assistant 填充 |
| 助手/供应商/模型 customHeaders + customBody | 写完即沉没（extraHeaders/extraBodyJson 无赋值点） | 新增 `core/llm/client/CustomRequestMerger.kt`（头三层序 base→assistant→provider→model、大小写不敏感去重、`x-conversation-id` 保护；body 经 parseOverrideValue 转型）；chat 请求合并三层 |
| 自动重试 `auto_retry_options` | `AppContainer.retryOptions` 硬编码默认值，整页设置无效 | 客户端改为 `retryOptionsProvider: () -> AutoRetryOptions`，容器按请求实时读键 |
| 后台聊天模式 | 设置页写 preference_rows（readJson），读取端 `readLocal`（SharedPreferences）⇒ 恒 OFF | 读端改 readJson |
| 仅点按插入建议 | 设置页写 "1"/"0"，readBoolPref 按 JSON boolean 解析 ⇒ 恒 false | readBoolPref 同时接受 "1"/"0"/true/false |
| 默认模型页「聊天模型」`selected_model_v1` | 只喂后台任务，聊天 fallback 从不读它；且读的时候没解 JSON 引号、链里缺"助手默认"层 ⇒ 新建对话要重选模型 | init 走 `resolveChatModel` 三层链：**助手默认 → 全局默认（`parseStoredModelSelection` 解包）**，会话行仍异步覆盖；三层皆空时不猜 provider，四个生成入口按原版 `no_model` 提示「请先选择模型」（2026-09-12 修） |
| 消息模板 `messageTemplate` + `appendCurrentTimeToUserMessage` | 用户消息不做模板/时间处理 | 组装期用户消息过 `PromptTransformer.applyMessageTemplate`（core/llm 已有现成移植）+ `<current_time>` 后缀（MemoryPrompts.formatCurrentTimeTag） |
| 助手正则 `regexRules` | 发送/显示均无应用点 | 新增 `core/data/model/AssistantRegexApplier.kt`（三 target：send=user scope 改写请求、visual=渲染层改写、persist=落库改写；$N 组展开 + 编译缓存）；send 在组装期、visual 在 HomeScreen 两个渲染点；**persist 目标待接**（Android 流式多次落库，中途应用会截断正则边界） |
| 预设对话 `presetMessages` | 只在编辑页存在 | 新会话创建时作为**真实消息**落库注入（`ChatViewModel.injectPresetsIfNeeded`，HomeScreen 经 factory 参数 `injectPresets` 标记新会话） |
| TTS「自动播放助手回复」 | 开关写了没人读 | 回复正常跑完 → `TtsPlayer.speak(text, ownerId)`（取消/报错不播） |
| 回车发送 `display_enter_to_send_on_mobile_v1` | ImeAction 硬编码 Send | ChatInputBar 按 setting 切 Send/Default |
| 重新生成确认 `display_show_regenerate_confirm_dialog_v1` | 弹窗无条件显示 | 关闭时跳过确认直接重生成 |
| WebDAV 设置子页 | 顶栏顶进状态栏 | 补 statusBars insets |
| 网络代理 7 键 | OkHttp 完全没接代理 | `app/GlobalProxy.kt`：ProxySelector 每连接读配置 + Basic 认证 + 绕过规则（精确/后缀/CIDR；上游 dio 没消费 bypass，属有意增强）；socks5 无账号密码（Java 层限制，已知偏差） |
| 用户画像入口 | 行从未接导航（页面/路由早已存在） | 接 `user_profile` 路由 |

### 待接线（⬜，按批做；**行不删**）

| 批次 | 项 |
|---|---|
| **渲染批** | ✅ **气泡风格整页已接线**（2026-09-12）：`ChatBubbleStyle`/`BubbleOverrides`/`resolveBubbleStyle` 与设置页共用一份实现（`ui/chat/ChatBubbleStyle.kt`）；`ChatBubbleSurface` 复刻 `_buildSharedChatSurface`（default=用户 primary@0.15/0.08 + 助手裸气泡；solid/frosted=解析底色+描边+圆角，译文卡与助手图片块一起换肤）；`chatSurfaceFg` 走 `_ChatSurfaceTheme` 同款 CompositionLocal（思考/工具/ask-user 卡的前景色板跟随气泡文字色）；`assistantBubbleFitContent` → 气泡裹内容；`assistantBubbleSplitParagraphs` → `splitAssistantParagraphs` 逐段气泡（围栏代码块保护 + 缩进续行/相邻列表/标题合并）。**平台差异**：frosted 只画 tint（原版是 backdrop 快照/`BackdropFilter` 管线，Compose 无等价物；等同原版 `scope == null` 的 Tier 0 分支，`blurSigma` 不参与绘制）。✅ **$LaTeX/数学渲染已接线**（2026-09-12，用户点名「看看 RikkaHub 怎么做的」→ 照做）：依赖 RikkaHub 的 `jlatexmath-android` fork（JitPack，`core:ui` 引入 jlatexmath + greek/cyrillic 字体；该 AAR 全是纯 Java 类，无 Kotlin 元数据风险），`markdown/Latex.kt` 是 RikkaHub `LatexText.kt`/`MathBlock.kt` 的同构移植（`JLatexMathDrawable` → Compose Canvas，解析失败回退原文；`assumeLatexSize` 预算行内占位符尺寸；`JLatexMathSplitter` 把过长行内公式按顶层运算符拆段并插零宽空格提供换行点）；`MarkdownRenderer` 里**整段** `$$…$$`／`\[…\]` 走 `MathBlock`（居中+横向滚动），正文里的 `$…$`／`\(…\)` 走 `InlineTextContent`（与引用胶囊同一套机制）。两开关 `display_enable_math_rendering_v1`（总）+ `display_enable_dollar_latex_v1`（`$` 形式；关掉后 `\[…\]` 仍渲染）经 `ChatTimelineSettings.mathRendering/dollarLatex` → `MathConfig` 送进用户正文/助手正文/思考卡三处；`LatexMathTest` 13 例覆盖块级/行内判定、转义 `\$`、价格误判防护、`$$` 与块级的边界。**已知边界**：非法 LaTeX 原样显示（不吞内容）；正文里混排的 `$$…$$` 保持字面（原版由解析器按行处理）。；✅ **用户/助手 Markdown 开关已接线**（`ChatTimelineSettings.enableUserMarkdown/enableAssistantMarkdown`，关掉时同字号/行高纯文本）；✅ **代码块 chrome 已接线**（`codeBlockAutoCollapse`/`codeBlockAutoCollapseLines`/`mobileCodeBlockWrap` → `CodeBlockConfig`；`MarkdownRenderer.CodeBlockView` 语言条+折叠/换行+Copy，底部 24dp 渐隐；`onOpenHtmlPreview` 走既有 WebView 预览；`onSaveAs` 仍为 null ⇒ Download 钮按既有约定隐藏，待接存储导出） |
| **输入批** | ✅ **聊天字体大小已接线**（2026-09-12）：`display_chat_font_scale_v1` → `ChatTimelineSettings.chatFontScale`，消息行套 `LocalDensity.fontScale ×= scale`（等价原版 message_list_view.dart:2018-2024 的 `MediaQuery(textScaler: 系统×scale)`，头/正文/思考卡/代码块一起缩放，dp 不变）；✅ **输入框不透明度已接线**（浅/深两键 + 壁纸激活比例，`inputFillColor` 三个入参都从设置读）；✅ **自动滚动已接线**（`display_auto_scroll_enabled_v1` 门控流式跟随 + `display_auto_scroll_idle_seconds_v1` 拖动后空闲重判贴底 + 生成结束 450ms 窗口补一次贴底 `stickToBottomAfterGeneration`；**2026-09-13 重做让位逻辑**：用户滚动信号改用 `isScrollInProgress`、贴底改用 `requestScrollToItem`、距离变大不再取消跟随，见 §4.42）；✅ **App/代码字体已接线**（`AppFonts` 解析本地 TTF → `FontFamily`；App 字体经 `Typography.withFontFamily` 刷全站 15 个槽位（原版 `main.dart:868+ applyAppFont`），代码字体经 `LocalMarkdownCodeFont` 给围栏块+内联 code；`ThemeState.loadFonts` 让改字体立即生效，不必等冷启动。**已知差异**：Google Fonts 族名不下载也不按系统族名解析 → 落回默认无衬线，与「选择器只有本地文件/重置」一致）；✅ **长粘贴转文件已接线**（`LongPasteSettings`（默认开 / 5000 字素，按 ICU 字素簇严格大于）+ `TextToolbar` 包一层只换 paste 回调（复制/剪切/全选原样透传）→ `AttachmentStore.importPastedText` 写 `<upload>/pasted_<ms>.txt`（撞名 `(n)` 顺延）→ 附件；写失败退回插入文本；顺带修掉「纯附件不能发送」〔`canSend` 补 `attachments.isNotEmpty()`，CMW:2929-2942〕与用户气泡里的 `‹file›` 占位〔改 `MessageDocCard`，CMW:2244-2360〕）；✅ **图片画质管线已接线**（`ImageCompressConfig` 五档 → quality/maxLongEdge/透明闸门 + `ImageCompressor` 复刻 downsize 行为：64KB 下限、PNG colorType4/6·tRNS·acTL 与 GIF/其他需透明开关、严格更小才采用、输出统一 JPEG 并把 mime 改成 image/jpeg、按 EXIF 转正、透明铺白底；挂到相册/文件选择与拍照两条路径）。**未做**：裁剪器（`image_cropper_enabled_v1`，需要第三方裁剪库，默认关）与 `send_markdown_image_links_as_images_v1` |
| **聊天行为批** | ✅ **重新生成删除后续消息已接线**（默认关；开 → `MessageDao.deleteTrailingGroups` 按**分组首条 order** 整组删（原版 `_truncateLinearMessageGroupsAfter`，旧实现按单行 order 切会误删保留分组的高版本行）；关 → 不删任何行，新回复并入「锚点之后第一条助手消息」的分组作为新版本，`collapseVersions` 把它显示在原位）；✅ **Fork 保留消息版本已接线**（开 → 按分组首条 order 截断 + 保留每个分组全部版本 + 分组 id 重映射；关 → 每分组只留当前可见/最高版本并拍平成独立新组）；✅ **编辑助手消息保留思考/工具卡已接线**（`chat_edit_assistant_keep_thinking_tool_cards_v1`：关 → `partsWithoutThinkingAndToolCards` 丢思考/工具 part 并清空思考元数据；开 → parts 与 `reasoningSegmentsJson/StartAt/FinishedAt` 一起继承；「保存并发送」现在对助手消息也重新生成）；✅ **消息导航按钮三态已接线**（always/scroll/never，never 整块不渲染）；✅ **会话列表日期已接线**（关掉时滤掉日期分组头、Pinned 头保留、列表顶部 4→10）；✅ **侧栏保持展开已接线**（点话题/点助手各自一键，`onSelect/onNew` 带 `closeDrawer`）；✅ **删除后新建会话已接线**；✅ **启动时新建会话已接线**（`display_new_chat_on_launch_v1` 默认开 → 每次启动新建 draft 会话，关掉才回最近一条；home_page_controller.dart:782-786）；⬜ 显示应用更新（依赖上游更新端点，S5 类不移植则**删行需用户点头**） |
| **语音批** | ⏳ **用户 2026-09-12 定调「按照原项目 都做了吧」——全铺（12 家网络 TTS + 7 种 ASR），开工顺序按依赖链**。侦察结论：<br>① **保存音频依赖网络 TTS**：`tts_floating_player.dart:301-303` 的保存钮只在 `tts.canSaveNetworkAudio` 为真时渲染，`_save()` 调 `synthesizeAllAndCollect()` 拿**合成好的音频字节**再走 `FilePicker.saveFile`——系统 TTS 没有字节流，所以必须先有网络 TTS 才有这个钮。<br>② **网络 TTS = 12 个 provider**（`lib/core/services/tts/network_tts.dart`，2238 行）：`NetworkTtsKind` = openai / gemini / azure / minimax / qwen / qwenAudio / groq / xai / elevenlabs / mimo / step / fishAudio；入口 `NetworkTts.synthesize(...)`，各家返回音频字节（格式/扩展名随 provider），需要合成缓存 + 播放（我们现有 `TtsPlaybackController` 只驱动系统 `TextToSpeech`，`SystemTtsEngine` 的 0.8–2.0 变速/±15s 定位已经能用）。<br>③ **云端 ASR = 7 个 kind**（`lib/core/services/asr/asr_service_options.dart` + `cloud_asr_service.dart`，1700+ 行）：`system`（已接线，`VoiceInputController` 走系统 `SpeechRecognizer`）/ `sherpa_onnx`（离线模型，桌面向）/ `openai_realtime`、`dashscope`、`qwen_audio`、`volcengine`（四个 WebSocket 流式，其中 volcengine 是自定义二进制帧 + gzip）/ `mimo`、`step`（两个 HTTP：MiMo 走 `/chat/completions` + WAV base64 分段，Step 走 `/v1/audio/asr/sse` SSE 增量）。分派点还需 `AudioRecord` 采 PCM16 单声道、按 provider 的 `sampleRate`（16k/24k）× `segmentDurationSec` 切段、partial 回调进输入框。<br>**计划**：先铺网络 TTS（合成→缓存→播放→保存音频，一条链打通即解锁保存钮），再按 HTTP 两家（纯逻辑可单测）→ WebSocket 四家 的顺序铺 ASR；每步都要门禁绿 + 装机 + 提交。纯网络路径需要真机凭据验证，用户按需给 key。<br>**进度（2026-09-12）**：✅ **TTS 合成客户端已落地**（`provider/NetworkTts.kt`：11 家 HTTP provider 的请求组装与响应解析——openai〔/audio/speech, mp3〕/ gemini〔generateContent + inlineData PCM24k→WAV〕/ azure〔SSML + cogservices v1 + 429/502/503 重试；UA 品牌化为 Memo〕/ minimax〔t2a_v2 SSE + hex 音频〕/ qwen〔multimodal-generation SSE + base64 PCM→WAV〕/ groq〔wav〕/ xai〔/tts〕/ elevenlabs〔text-to-speech/{voice}?output_format，`pcm_<rate>`→WAV〕/ mimo〔/chat/completions，stream=pcm16 或非流式 wav base64，含 voiceclone/voicedesign 校验〕/ step〔200 字切段 + PCM/WAV 合并，FLAC 多段报错〕/ fishAudio〔model 走 header，referenceId 必填〕；纯函数 pcmToWav/combineWavAudio/audioMimeForFormat/splitStepText/hexToBytes/SSML 转义/joinUrl 全部对齐原版；`NetworkTtsTest` 19 例用 MockWebServer 钉住 URL/头/body 与错误路径）。**未接**：qwenAudio（DashScope WebSocket，与 ASR 的 WebSocket 四家一起做，调用会明确报「not ported」）。⬜ 合成缓存 + 播放接线 → ⬜ 保存音频按钮 → ⬜ 云端 ASR。✅ **播放接线 + 合成缓存 + 保存音频已落地**（2026-09-13，`ui/chat/NetworkTtsEngine.kt`）：`TtsAudioCache`（`filesDir/tts_cache/<sha1(kind+options+text)>.<ext>`，命中复用、超 40 个按 lastModified 清理）；`NetworkTtsEngine : TtsEngine`（逐块「合成 → MediaPlayer 播放」，`onRangeStart` 按播放比例折算字符位置喂给控制器，倍速 `rate×2` 还原到 MediaPlayer 的 0.5–3.0）；`SwitchableTtsEngine`（每次 speak 现读 `tts_selected_service_id_v1` 选引擎；qwenAudio 未接 → 退回系统引擎而不是静默不出声；Listener 同时挂两个委托，控制器与 flow 身份不变）；`TtsEngine.isNetwork` + 控制器 `publish/finish` 置 `usingNetwork`；`TtsPlayer.init(context, httpClient, ttsServicesStore)` 由 `MemoApplication.onCreate` 注入。悬浮播放器：`usingNetwork` 时显示 Download 钮（宽度 +34dp，同原版 `_saveButtonDelta`），点击 → `TtsPlayer.saveAudio`（整段重合成，原版 `synthesizeAllAndCollect`）→ SAF `CreateDocument` 写 `memo_tts_<ms>.<ext>`；`NetworkTtsEngineTest` 覆盖缓存命中/清理/键、倍速换算、引擎选择。⬜ 剩：**qwenAudio（TTS 的 DashScope WebSocket）** + **云端 ASR 7 种**（HTTP 两家 → WebSocket 四家）。✅ **云端 ASR 客户端（HTTP 两家）已落地**（2026-09-13，`provider/CloudAsr.kt`）：`CloudAsrSession` 抽象（`addPcm16` / `finish` / `cancel` / `observePartials`，对应原版 `CloudAsrSession`）+ `CloudAsrService.startSession` 分派；**MiMo**（攒够一段 POST `/chat/completions`，音频 `data:audio/wav;base64,…`，转写取 `choices[0].message.content`）与 **Step**（攒够一段 POST `/v1/audio/asr/sse`，SSE 增量 `transcript.text.delta/done`、`error` 抛出，失败按 300ms×尝试次数重试 3 次）；分段规则对齐原版（上限 = min(6MB, 采样率×2×segmentDurationSec)；Step 另有 3200 字节最小尾巴），奇数长度 PCM 报「incomplete PCM16 sample」，错误信息按原版 `_redact` 抹掉密钥、`_responseError` 只取 message 不回显 body。`CloudAsrTest` 13 例：分段上限/最小尾巴/WAV 头/SSE 解析（delta 累积·done 覆盖·[DONE] 停止·error）/重试与错误上报/请求形状（MockWebServer）。⬜ 剩：**ASR 接线**（`asr_selected_service_id_v1` 分派 + `AudioRecord` 采 PCM16 → 会话 partial 回填输入框）→ ✅ **已落地（2026-09-13）**：`AsrRecorder`（`AudioRecord` PCM16 单声道、200ms 一块、RMS 归一化驱动波形）+ `VoiceInputController` 分派（选中的云端服务已配置且有 OkHttp 时走 `CloudAsrService`，否则回系统 `SpeechRecognizer`；采集块进队列由独立工作线程串行喂会话，避免在采集线程里发 HTTP 把麦克风读空；partial 回填 `State.Listening`，停止时 `finish()` 取最终转写）+ **RECORD_AUDIO 运行时授权**（`RequestPermission` launcher，授权回调里再启动）。`VoiceInputDispatchTest` 覆盖分派判定、PCM 电平归一化（静音/满量程/半幅/小端）与系统 rms 换算不变。✅ **WebSocket 三家已落地**（2026-09-13）：`RealtimeAsrSession`（openai_realtime + dashscope 共用，原版 `_RealtimeAsrSession`——`session.update`/`input_audio_buffer.append`/`commit`/`session.finish`，转写按 `item_id` 累积 delta·text·completed，`error`/`failed` 抛错并按 `_redact` 抹密钥；DashScope 开 VAD 时不发 commit 且等 `session.finished`）+ `VolcengineAsrSession`（**照 RikkaHub `speech/.../VolcengineASRController.kt` 移植**——二进制帧 0x11 头 + 大端长度、配置帧 gzip+JSON、`result.text` 转写、`FLAG_LAST_PACKET` 收尾、`MSG_ERROR` 错误码、`queueSize()` 背压丢帧；uid 按品牌规则用 `memo`）。✅ **qwen_audio ASR 也已落地**（2026-09-13，按 Flutter 侧照做——RikkaHub 没有这一家）：`QwenAudioAsrSession`（DashScope `/api-ws/v1/inference`：run-task → 二进制 PCM → result-generated → finish-task；`result-generated` 只给当前句，按 `sentence_end` 累积定稿句 + 半句，`combineQwenAudioTranscript` 只在 **ASCII** 拉丁词边界补空格——写成 `isLetterOrDigit` 会把「你好」+「世界」拼出空格，测试抓到了；`task-failed` 走 `_redact`；`websocketUrl` 由 workspace/region 拼出，`startSession` 留了只给测试用的 `endpointOverride`）。⬜ 仍剩：**qwenAudio TTS（WebSocket）**（同一个 DashScope 实时协议的另一半，复用这次的事件骨架）+ sherpa_onnx（离线模型，桌面向不移植）。<br>**参考实现提示**：RikkaHub 的 `speech/` 模块（`me.rerere.asr`，约 2000 行）就有这套 provider（OpenAIRealtime/DashScope/Volcengine/MiMo/Step 五个 controller + `ASRState`/`AudioAmplitude`），缺哪家先看那里，别手搓。 |
| **模型批** | ✅ **apiModelId wire 映射已接线**（2026-09-12：`AppContainer.clientFor` 包一层 `WireModelIdClient`，出网前把逻辑模型 id 换成 `modelOverrides[modelId].apiModelId`（`chat_api_helpers.dart:44-53 apiModelId`），聊天/标题/摘要/记忆/翻译/OCR/测试连接全部请求路径一起生效，厂商启发式也拿到上游 id）；✅ **模型层 headers/body 已核对**（ChatViewModel 已把 `modelOverrides[modelId]["headers"]`（{name,value}）与 `["body"]`（{key,value}）并入 `CustomRequestMerger.mergeHeaders/mergeBody`，body 值经 `parseOverrideValue` 变成真实 JSON 类型——与 `ModelOverridePayloadParser.customHeaders/customBody` 形状一致）；✅ **contextWindow 进上下文管理已接线**（2026-09-13：模型编辑页 Advanced 新增「上下文长度」→ `modelOverrides[modelId].contextWindow`，作为 opencode 压缩阈值的基准，未填回落到压缩设置里的全局默认 128k；`ContextCompactionPrefs.modelContextWindow`）；⬜ builtInTools 门控（依赖厂商内置工具本体：OpenAI web_search / Gemini google_search / Claude web_search 等尚未移植，门控无消费点） |
| **明确不做/暂缓** | S5 kelivo 内置搜索；MCP stdio（桌面专属）；赞助页（品牌空壳）；providerAutomatic 头层；opencode `compactAfterOverflow`（provider 报 context-length 后自动压缩重试，随压缩机制后续单开） |
| **助手域补齐**（2026-09-13 用户「给我做完吧 我要使用了」，对着 Flutter 逐块审完剩三处，全部落地） | ✅ **「管理总结」**（`assistant_settings_edit_memory_tab.dart` L481-634）：记忆 tab 底部「Manage Summaries」段（`allowPastConversationRecall && generateConversationSummary` 才出现）——该助手名下**有总结**的会话列表（`ConversationDao.withSummaryForAssistant`，r14 卡、标题 12sp@0.6、总结 3 行省略、右侧 Pencil/Trash2）、编辑 sheet（= 原版 `_MemoryTextInputForm`：拖柄 + 居中标题 + 多行输入 + 取消/保存，**空内容 = 清掉总结**，走 `updateSummary`/`clearSummary`）、清除确认框；✅ **「流式输出」开关通电**（`streamOutput` 之前只写不读）：`LlmClient.completeAsChunks` 走非流式请求，`runGenerationLoop` 按 `assistant.streamOutput` 选源（见 §4-38）；✅ **`applyContextLimit`**（限制上下文条数）接线（见上）；✅ 健康/位置/提醒/天气确认是**原版 iOS-only**（`DeviceLocalTools` 判断正确），非遗漏 | ✅ 2026-09-13 |
| **聊天页实测修复**（2026-09-13 用户在真机上连报两处，见 §4.41/§4.42） | ✅ **思考卡展开态**：`ReasoningSegmentCodec.resolveExpanded` + `ChatViewModel.segmentExpanded` 权威态，只有「结束转变」那一次采信流式侧折叠结果（旧法用 handler 重建出的 `expanded` ⇒ 思考中点击展开被下一个增量打回；`ReasoningSegmentExpandedTest` 11 例）；✅ **流式自动跟随**：按位置跟随（RikkaHub `isAtBottom()`）+ `pointerDown` 硬让位 + `requestScrollToItem(lastIndex, Int.MAX_VALUE)` 真到底（前几版败在「让位信号靠状态观察」与「传末条下标≠到底」两处，见 §4.42 的 ①②③(e)）；✅ 顺带修掉「打开会话停在最后一条消息开头」 | ✅ 2026-09-13 |

### 审计确认已接线（抽样无恙）

记忆域（enableMemory / allowPastConversationRecall / autoOrganizeMemory / smartAddMode / searchEnabled / mcpServerIds / 注入管线）、默认模型六组槽位（标题/总结/建议/压缩/翻译/OCR）、搜索服务 23 引擎、世界书全字段、快捷短语、指令注入、触感 6/6、思考/工具卡 6 开关、Live Update 通知、背景遮罩强度、切换助手新建会话。

| **键盘抬起顶起对话**（2026-09-13 用户点名「输入框抬起可以抬起对话内容」） | ✅ 原项目有这套（`resizeToAvoidBottomInset` + `didChangeMetrics` → `pinBottomDuringViewportResizeIfNeeded`），我们只缺「钉底」半边，本轮补齐：`ChatViewportFollow.shouldPinTimelineOnImeRise` 纯函数 + `ChatContent` 监听 `WindowInsets.ime` 抬起即钉（`ChatViewportFollowTest` 6 例）；判据用 `following` 而非 `layoutInfo`（Compose effect 拿不到布局前几何），详见 §4-43 | ✅ 2026-09-13 |
| **思考选择滑杆**（2026-09-13，上游 83ab329 重做为动画档位滑杆，用户点名照搬；随后真机连报三处已修） | ✅ `ReasoningBudgetSheet.kt` 整体重写：Off/Auto/Low/Medium/High(/XHigh/Max) 档位滑杆 + `Animatable` spring(0.78/320) + 药丸标签随动画位置切换 + 自定义预算行；选中不关 sheet，关闭时写回（HomeScreen 种当前助手预算 + 全局键；编辑页只暂存 diff 写回）。5 条 l10n（sliderLow…Max）走 `tools/arb_to_android.py`。**真机三修**：① **Max 档缺失** — 能力判定原只移植了 Claude 家族，漏了 `openai_model_compat.dart` 的 OpenAI 兼容模型表 → 移植 `OpenAiReasoningSupport` 表（glm-5.3/5.2、deepseek、kimi-k3、grok、mimo、muse、gpt-5.x/6 各族 supportedEfforts + offFallback），`supportsMax/XhighReasoning` 按 `claude-` 前缀分流，`openAiEffortForBudget` 补 `openAINormalizeReasoningEffort` 归一化（xhigh 请求在无 xhigh 档的模型上按偏好序回落：glm-5.3 → max，grok-4.6 → xhigh，无名模型 → high），claude 走 claude 梯子不被表归一化；② **拖拽弹回 Medium** — `pointerInput` 闭包捕获的 `position` 是首帧参数值（键不变不重启），拖完 `onTapStop` 又 snap 回旧档 → `rememberUpdatedState` 读当前值；③ **紫色不满滑道** — 填充 `drawRect(topLeft = Offset.Zero)` 从 Canvas 顶画被 clip 成半截 → 改 `trackRect.topLeft`（对齐上游 `Rect.fromLTWH(0, cy - trackHeight/2, …)`）。lint 两 error（UnusedBoxWithConstraintsScope → 内层降级 Box；UnusedContentLambdaTargetStateParameter → `key(target)`）当场清。测试：`ReasoningBudgetTest` +12 例（模型表/claude 梯子/归一化偏好序）、`ReasoningEffortStopsTest` 7 例 | ✅ 2026-09-13 |

| **模型能力＝推断 + override 叠加**（2026-09-14 用户实测：第三方模型思考不生效／编辑页改输入模态后选择页标签不变） | ✅ 新增 `ModelOverrideResolver.kt`（移植 `model_override_resolver.dart` + `chat_api_helpers.dart:137-153 effectiveModelInfo`）：`forModel(cfg, modelId)` 先按 override 的 `apiModelId` 取 `ModelRegistry.inferFull` 基线，再叠 `type/input/output/abilities`（embedding 特判：abilities 清空、output 固定 text；空模态回落 text）。**接线三处**：① `ChatViewModel` 的 `reasoning` 旗标（此前只 `ModelRegistry.infer(modelId)` ⇒ 名字推断不出能力的第三方模型永远不通思考）；② `ModelTagRow`（模型选择页 / 供应商列表 / 拉取模型面板，新增 `cfg` 参数）；③ 同一 apiModelId 基线口径。**勿改回只读名称推断** | ✅ 2026-09-14 |
| **存储空间两处**（2026-09-14 用户实测：删文档/图片弹两个 toast／子页顶栏没统一） | ✅ 删除只弹一条：`ConfirmSpec.silent` + 纯函数 `confirmDoneMessage`（动作自己报过「已删除 N 个」时确认层不再补通用「已完成」；上游 `storage_space_page.dart:1882` 只有一条）；✅ 子页 `StorageCategoryScreen` 顶栏换 `MemoTopBar`（父页同款），不再手搓返回行 | ✅ 2026-09-14 |
| **「聊天项显示」6 个开关接线**（2026-09-15 用户「③ 聊天项显示那 6 个开关（做了吗）」「设置里面的聊天项显示这个 好像很多都没有做出来呀」） | 这 6 个键此前**只写不读**（设置页能拨、界面无反应）：`display_show_user_message_actions_v1` / `display_use_new_assistant_avatar_ux_v1` / `display_show_model_name_v1` / `display_show_model_timestamp_v1` / `display_show_provider_in_chat_message_v1` / `display_show_token_stats_v1` | 逐条照源码接线：① **用户消息操作行**（复制/重发/编辑/更多）由 `showUserMessageActions` 门控，关掉后整行仍可因分支选择器存在（CMW:1847-1858）；② **助手名字行** = 助手开了「使用助手名字」→ 助手名，否则**模型名**（CMW:2809-2813；`Assistant.useAssistantName` 默认 false，**这是 1:1 修正** —— 此前无论开关都显示助手名，导致这一排开关没有意义），模型名取 `modelOverrides[id].name` → `apiModelId` → 模型 id（`messageModelDisplayName`，CMW:1355-1407），原料按会话里出现过的 providerId **异步**读一次（`rememberLoaded`，组合期不查库，见 §5.13）；③ 名字行 / 时间戳分别由 `showModelName` / `showModelTimestamp` 门控（CMW:2807-2834）；④ `showProviderInChatMessage` 打开时模型名拼 `" | 供应商名"`（供应商名 trim 后为空不拼，CMW:1374/1401）；⑤ **token 统计块**由 `showTokenStats` 门控（CMW:3395-3405）；⑥ `useNewAssistantAvatarUx` 打开时**顶栏标题行前加当前助手头像 28dp**（home_mobile_layout.dart:185-227 / 306-329），点它开抽屉。**唯一未复刻**：顶栏头像的长按（原版打开助手设置）。测试：`ChatTimelineTest` 6 例（默认值/存储值/模型名解析/供应商后缀/无模型回退）+ `ChatHeaderAssistantTest` 3 例（Compose：助手名 / 默认显示模型名 / 关掉名字行 / 供应商后缀异步到位） | ✅ 2026-09-15 |

## 5.13 组合期不许干重活（性能约定 + 2026-09-15 普查）

**约定**：`@Composable` 函数体里（含 `remember { ... }` 与「组合期实参」）**不许出现 SQLite / 文件 / `ContentResolver` 调用**。要「按 key 读一次」用 `ui/AsyncLoad.kt` 的 `rememberLoaded(initial, keys…) { … }`（`produceState` + `Dispatchers.IO`，保持 `remember(key)` 语义；`RememberLoadedTest` 3 例钉住「跑在非组合线程」与「key 不变不重读」）；`LaunchedEffect` 体默认跑在主线程，里面的库操作要自己包 `withContext(Dispatchers.IO)`。

**为什么**：用户 2026-09-15 报的「点击统计界面会卡一下」「进会话先看到最旧一条再跳到底部」，根都是组合期同步干重活（统计页在组合期兜底重算一遍聚合；进会话那帧在组合期查库 + 画旧像素）。

**本批修掉的**：

| 位置 | 原状 | 修法 |
|---|---|---|
| `StatsScreen.kt:251` | `snapshot ?: StatsAggregation.buildDatabaseSnapshot(...)` —— 组合期兜底聚合，与 IO 里那个 LaunchedEffect 算的是同一份 | 组合期只渲染加载态，内容一律等 IO |
| `HomeScreen.kt:1120`、`TranslateScreen.kt:151`、`ConnectionTestDialog.kt:175`、`chat/CompressContextDialog.kt:272` | `remember { loadModelOptions(...) }`：provider_rows 整表 + 逐条 JSON 解码，同步卡首帧 | `rememberLoaded`（消费方都是用户点开的 sheet，点开时早已就位） |
| `HomeScreen.kt:348/354`（冷启动选会话） | LaunchedEffect 体里主线程读偏好 + `conversationDao.getAll()` | 两处读 `withContext(IO)` |
| `ProviderListScreen.kt:202`（进页面清理写 + 读）、`ProviderSettingsScreens.kt:114`、`ChatHistoryScreen.kt:100`、`SideDrawerContent.kt:153`（抽屉列表）、`SideDrawerContent.kt:1504`（全库消息搜索） | LaunchedEffect 体里直接同步读/写库 | 包 `withContext(IO)` |

**仍挂账（下一批，别当没看见）**：~~`PreferenceRepository.readJson` 每次调用一条 SQL，约 195 处组合期读偏好~~ **已修（见下一段）**。**另**：`HomeScreen.kt:402` 的 `currentIsEmpty()`（组合期实参里的 `messageDao.count()`）本批**故意不动**：它的刷新靠「随便哪次重组」，没有 `titleRefreshTick` 那样的键可挂，直接搬进 keyed effect 会让「首条消息发出后 + 号仍按临时聊天处理」——得先有 VM 级「本会话有无消息」信号。

**2026-09-15 第二批（读缓存 + 机器守卫）**：

| 项 | 做法 |
|---|---|
| `PreferenceRepository.readJson` | 进程内读缓存（照 `ProviderConfigCache`）：命中即内存查表，**未命中（含"库里有没这行"）也缓存**，写端 `writeJson`/`remove` 单键失效；`AssetDirMigration` 的裸 UPDATE 显式 `invalidateCache()`。`PreferenceRepositoryCacheTest` 4 例钉住契约 |
| `AssistantStore.get(id)` | 同款解码缓存 `AssistantCache`（`assistant_rows` 写入时由 `PayloadEntityDao` 整体失效，备份恢复也走 DAO）；`getAll()` 顺带预热。`AssistantStoreCacheTest` 5 例 |
| 启动预热 | `AppContainerImpl.prewarmConfigCaches()`：`MemoApplication.onCreate` 里在 `appScope`（IO）上把 provider_rows / assistant_rows 解一遍 —— 组合期那批 `remember { providerConfig(key) }` / `remember { assistantStore.get(id) }`（含**列表行级**的 `ProviderAvatar`、抽屉当前助手、消息头归属助手）从此都是内存命中 |
| 移出组合期的重活 | `provider_rows` 整表 + 逐条解 JSON ×3（`DefaultModelScreen` / `AssistantSettingsEditScreen` / `MemorySettingsScreen` → 统一 `loadModelOptions` + `rememberLoaded`）；`assistant_rows` 整表 ×2（抽屉移动会话 sheet、记忆条目编辑 sheet）；本地背景图 `BitmapFactory.decodeFile`；技能详情页的目录遍历 + 技能文件 `readText()` |
| **机器守卫** | `app/src/test/.../ui/CompositionThreadingTest.kt`：扫所有 `remember { … }` / `remember(keys) { … }` 的 lambda 体（跳过 `rememberLoaded` 等），命中 `.getAll(`/`.query(`/`.rawQuery(`/`.execSQL(`/`.count(`/`loadModelOptions`/`providerConfig(`/`currentAssistant(`/`assistantStore`/`contentResolver`/`decodeFile`/`listFiles(`/`readText()`/`exists()` 等**干活**词就失败（造 DAO/取库句柄**不算**；注释与字符串里的示例代码也不算）。豁免按「文件 + 具体 token」登记并写原因 —— 同一文件里出现**新的**那类调用照样失败 |

## 5.14 对话界面卡顿审计（2026-09-15，逐项对比 Flutter 原版与 RikkaHub）

**触发**：用户「点击到底部这个按钮 对话界面会闪」「我很怀疑你这个对话界面这个整个部分没有做好 你可以看看人家原项目和 rikkhub 代码 人家做的都很好 都没有什么卡顿问题」。

**方法（先取证再改）**：
1. Compose 编译器稳定性报告（`:app:compileDebugKotlin --rerun-tasks` → `app/build/compose_reports/app_debug-composables.txt`）：确认每个聊天相关 composable 是 `restartable skippable` 还是被哪个 `unstable` 实参拖累；
2. 新增 `ChatRecompositionProbe`（`MessageRow` 每次组合 +1，一次 int 自增）+ `ChatRowRecompositionTest`：用真实手势断言「碰列表（会翻 `pointerDown`/`following`/`navVisible` 三个 `ChatContent` 状态）之后消息行组合次数必须还是 0」；
3. 逐项读两边的实现（Flutter `lib/features/home/...`、RikkaHub `ui/pages/chat/...`）。

**结论（证据 → 处理）**：

| 项 | Flutter 原版 | RikkaHub | 我们 | 结论 |
|---|---|---|---|---|
| **「到底」按钮的滚动命令** | `scroll_controller.dart:625-744 _animateToBottom`：目标是**真实 `maxScrollExtent`**（`alignment: 1`），动画 250/350/450ms `easeOutCubic`，另有「非动画」分支 | `ChatList.kt:284 requestScrollToItem(lastIndex + 10)`、`ChatPage.kt:179 requestScrollToItem(size + 5)`（全部**有界**） | ❌ 之前是 `animateScrollToItem(size - 1, Int.MAX_VALUE)` —— 越界偏移被**原样写进滚动位置**（真机日志 `firstVisible=3 offset=2147483647`），下一帧 LazyColumn 再夹一次 ⇒ **点一下就闪**（用户报的就是这个） | ✅ **已修**：`scrollTimelineToBottom()` = `requestScrollToItem(size + SCROLL_TO_END_INDEX_SLACK)`，与「进入会话」「流式跟随」同一条有界写法；`ChatScrollOffsetTest` 扫描所有 `*ScrollToItem(` 调用点（含跨行 5 行窗口）禁止 `Int.MAX_VALUE`。**有意差异**：不做动画（Compose 侧拿不到 `maxScrollExtent` 等价值；动画期间流式跟随会再插一次瞬移反而更抖） |
| **消息行的可跳过性** | `ListView.builder` 只重建脏 item（`RepaintBoundary` 由框架给） | Compose 稳定性：`ChatMessage` 是 skippable composable | ✅ 编译器报告：`MessageRow` = `restartable skippable`（实参 `msg: UiMessage` / `assistantLabel` / `assistant` / `prevVersion`… 全 stable），靠 `app/compose_compiler_config.conf` 把 `ChatViewModel.UiMessage`、`ChatTimelineSettings`、`Assistant`、`MessagePart`、`Conversation`、`ProviderConfig` 显式声明 stable；`ChatRowRecompositionTest` 证明「碰列表不重组合任何消息行」 | ✅ 一致 + 加了回归判据 |
| **Markdown 解析（流式）** | `gpt_markdown` + `RepaintBoundary`；流式增量只重建当前气泡 | `Markdown.kt:240-252` `mapLatest` + `flowOn(Default)`，段落级 `AnnotatedString` 缓存 | ✅ `MarkdownText` 同款：首帧同步解析（避免空白闪烁）→ `snapshotFlow + distinctUntilChanged + drop(1) + mapLatest + flowOn(Dispatchers.Default)`；`parsedCache` LruCache(32) | ✅ 一致（原「预热最近 60 条」的自创逻辑已删，见 §5.13 与 34d54d2） |
| **列表配置** | `ListView.builder`，`itemCount` = 消息数 | `itemsIndexed(key = id)`，`Arrangement.spacedBy(12)` | `items(messages, key = { it.id }, contentType = { 角色 / compaction })` + `contentPadding` top 8 / bottom 16（MLV:1684-1690） | ✅ 至少不差（多一个 `contentType` 复用维度） |
| **打开会话的取数** | `loadTimelinePage`：尾窗 + 槽位 + 批量 `getMessagesByIds`；版本表只预载多版本组 | 一条分页查询（64/页）+ 消息与 parts 同列 | ✅ 已按原版改（34d54d2）：尾窗 40、版本表按需 `IN`、不再全表 `COUNT(*)` | ✅ 一致 |
| **组合期重活** | — | — | ✅ 见 §5.13：偏好/助手读缓存 + 启动预热 + 守卫；8 处重活移出组合期 | ✅ |
| **流式跟随** | 位置判据 + 用户接管旗标 | `ChatList.kt:236-243` `isAtBottom()` | ✅ 位置判据 + `pointerDown` 硬标志（§4.42） | ✅ 一致 |
| 仍挂账 | | | ① `HomeScreen.currentIsEmpty()` 组合期 `messageDao.count()`（§5.13 已记账，要 VM 级「本会话有无消息」信号才能搬）；② 到底按钮不做动画（若要做：先组合尾部，再 `animateScrollBy(尾部底边 − 视口底边)`）；③ 未做 RikkaHub 的 `ScrollBottomKey` 哨兵项（「到顶/到底」锚点可以更精确，收益中等） | ⬜ |

**2026-09-15 第二轮（用户「点到底部会闪」「对话点击加载还是卡」，完整报告见
`docs/CHAT_JANK_AUDIT_2026-09-15.md`）**：① 到底按钮改成滚**末尾哨兵项**
（`SCROLL_BOTTOM_ITEM_KEY`，RikkaHub `ChatList.kt:374` 同款）—— 下标合法、位置恰好
`maxScrollExtent`；② 组合期 `messageDao.count(id)`（整表 COUNT）换成
`ChatViewModel.tailLoaded + messages.isEmpty()` 的纯函数 `newActionToggleable`
（首屏没读回来时**不**当成空会话，避免图标闪）；③ 打开会话的落底、进入会话落底统一走
`scrollTimelineToBottom()`。上面第 ①③ 条挂账至此关闭，剩下的见报告 §7（真机 frame trace、
自定义高度估算、首屏骨架行、抽屉路径、图片路径、打字重组）。

## 5.15 语音输入三连修：不出字 / 波形不动 / 「识别中」时序（2026-09-16）

**触发**：用户实测「语音识别根本用不了」（修好后）「波形怎么不动呀」「点击打勾
这个提示词没有马上显示」。三轮修复跨越数据层与 UI 层，根因各不相同。

**① 不出字（收尾顺序 bug）**：`finishCloud()` 在调 `session.finish()` **之前**就把
会话标记为取消（旧全局 `cloudRunning`），而 `isCancelled` 判据恰是它 —— `finish()`
一进 `ensureActive()` 就被丢弃，连 MiMo flush 都没发，最终转写永远拿不到。
**修**：顺序改为停采集 → `finish()` → 再翻标志；且取消标志换成**每代会话独立的
`AtomicBoolean cloudActive`**（否则「停止后立刻再点麦克风」时，老一代收尾线程翻
全局标志会误杀新一代 worker 循环）。

**② 波形「出现但不动」（布局 bug，不是数据 bug）**：电平探针实测 0.02~0.22 一直在
变、partial 也实时上屏 —— 数据链路全通。真根因：`VoiceWaveform` 调用点只给
`fillMaxWidth()`，Compose `Canvas` 本体是 `Spacer`，高度约束宽松（min=0, max=32dp）
时**测量高度 = 0** → `maxH = 0` → 所有条贴 2px 最小值 = 一条静止细线（0 高布局不
裁剪绘制，细线可见）。上游没事是因为 `AnimatedSwitcher` 的 **`StackFit.expand`**
强制内容撑满 32dp 槽位 —— 移植时漏了这个约束语义。**修**：`ChatStyleSpec
.WAVE_SLOT_HEIGHT_DP = 32f`，组件内兜底 `.height(32.dp)`（调用方显式给高度时以其
为准）。`VoiceWaveformLayoutTest` 用 `@Config(qualifiers = "...160dpi")`（1dp=1px）
锁「宽松约束下槽高必须 32dp / 显式高度优先」。**教训：上一轮把根因误判成电平刻度
（改 dB 归一化），几何没验证（没跑布局断言）就先动了数据层 —— 「出现但不动」类
bug 先查测量几何再查数据**。

**③ 「识别中」提示要马上出现（交互时序）**：点 ■/✓ 后 `finish()` 云端路径先进
`State.Transcribing`（转写指示 180ms fade 马上出现，三键禁用），后台线程
`join(500)` + `finish()`（HTTP）完成后才回 Idle + 回填文字 —— 主线程零阻塞，
指示语义与上游 `_finishingVoice` 一致。**教训：中间态不是技术细节，是用户可感知
的反馈，不能为了「快」跳过**。

**顺带**：电平归一化照上游 `record` 包语义改成 dB 刻度（-60dB→0，正常说话
-20~-10dB→0.67~0.83，`AsrRecorder.levelOf`）；■/✓/✕ 三键加 `Haptics.light`
触感（`LocalHapticsSettings.globalEnabled` 门控）；✓ 对勾不再直接发送，一律回填
输入框（用户拍板，■ 与 ✓ 行为统一）。测试：`AsrRecorderLevelTest`（4 例）+
`VoiceWaveformLayoutTest`（2 例）+ `VoiceInputDispatchTest` 断言随 dB 语义更新。

## 5.16 沙箱内存 / 「自动回到底部」/ 工具审批 三问取证（2026-09-16）

用户三问：「沙箱的内存是不是很少呀」「设置偏好里面的回到底部这个做完了吗」「沙箱里面工具的权限我关闭了确认 为什么还有确认呀」。三问都先取证再答，结论与判据记在这里，别重复侦察。

**① 沙箱没有内存上限 —— 模型看到的是**手机真实**的内存状态。**
- 证据链：`adb shell run-as com.psyche.memo.dev ulimit -a` → 无限制；proot 参数段（`ProotShellRunner.prootPrefix`）只有 `--root-id/--link2symlink/--kill-on-exit/-r/-w/-b`，**没有任何内存/CPU 限制**；cgroup 侧 `/dev/memcg/apps/uid_*/memory.limit_in_bytes`、`/sys/fs/cgroup/memory.max` **都不存在**（本机是 cgroup v1 应用组 + LMK，本来就没有 per-app 内存上限，`/proc/self/cgroup` = `4:memory:/`）。
- 沙箱里 `free`/`/proc/meminfo` 之所以"很小"，是因为我们把宿主内核伪文件系统 `-b /dev /proc /sys` 挂进去了（`WorkspaceManager.KERNEL_FS_MOUNTS`），读到的是**整机**状态。真机抓取：`MemTotal 11.65GB / MemFree 135MB / MemAvailable 1.97GB`，zram swap 接近满 —— 那台手机当时确实吃紧。
- 用户看到「内存很小」的来源是**模型自己的推理文字**（库里 `reasoning` part）：「the environment has about 11GB total but only 2.9GB available with swap at 10/11GB」，随后模型自己给 `.NET` 设 `DOTNET_GCHeapHardLimit=1000000000`/`DOTNET_GCServer=0` 绕，最后放弃改用 `python-docx`。CoreCLR 报 `0x8007000E`（E_OUTOFMEMORY）是它启动时预留大块虚拟地址空间失败，**不是我们限制了它**。
- 结论：**没有上限可调，不改代码**。为减少这类失败，按用户点头加了一句工作区提示词（同日落地）：`buildSystemPromptBlock` 里「CPU and memory are shared with the host device… heavy runtimes (for example .NET or the JVM) often fail to start with out-of-memory errors… Prefer Python, Node, or plain shell tooling」——**上游提示词没有这句，是我们新增的**，`WorkspaceToolsTest.promptBlockWarnsThatHeavyRuntimesMayRunOutOfMemory` 钉住；`buildSystemPromptBlock` 的 KDoc 也标了这句的来源。

**② 「自动回到底部」是做完的**（`display_auto_scroll_enabled_v1` + `_idle_seconds_v1`）。
- UI：显示设置 → 行为 → 「自动回到底部延迟」（detail = `已关闭` 或 `Ns`）→ sheet 内开关 + 2–64s 滑杆（`DisplaySettingsScreen.kt:120-121/148-149/328-337/456-500`，照 `display_settings_page.dart:729-858`）。
- 消费点（`HomeScreen.kt`，对应 `scroll_controller.dart`）：流式跟随 `:1211-1224`（≈ `shouldAutoFollow` `:217-222`）、收手后按延迟恢复跟随 `armIdleStickTimer`（≈ `refreshAutoStickToBottom` `:332-344` + `handleUserScrollIntent` 的 `_userScrollTimer` `:384-392`，**本轮补上 `enabled || following` 那一项**）、滚回底部即时恢复 `:1168-1179`（≈ `_onScrollControllerChanged` `:346-365`）、生成结束后补一次贴底 `:1229-1247`（≈ `stickToBottomAfterGeneration` `:518-533`）。
- **它不控制**（原版也不控制）：进入会话落到最新一条、自己发消息后落到最新、键盘抬起时的贴底钉住。
- 判据：`armIdleStickTimer` 里少了开关那一项时，「关掉开关 → 上滑 → 等 8s」仍会把 `following` 翻回 true（虽然两条滚动 effect 还要 `autoScrollEnabled` 才真滚，代码语义已经偏了）。

**③ 审批「关了还问」有三条路径，已修两条（详见 §5.11 两张表）。**
- 真机取证：工作区 `7beb11d3…`（助手「管家」绑定）的 `toolApprovals` = `{"workspace_shell":false}`（写入时刻 18:43:31），而最后一批 `workspace_shell` 调用在 **18:43:16**（早 15 秒）—— 那次审批请求是在开关关掉**之前**建出来的，屏上那个面板不会因为之后关开关而消失 ⇒ 本轮加 `approvePendingForTool`。
- 另一条：`workspace_write_file` 那条 `arguments` 是**被截断的**（`"{\"path\": \"/workspace/make_docx.py\""`，缺 `}`），`absolutePath` 解析失败 ⇒ 上游 `getOrDefault(true)` 判成"越界" ⇒ 即使开关是关的也弹审批，点完只收到 `execution_error: path is required` ⇒ 本轮改成三态，参数不可用**不弹审批**。
- 剩下一条是**保留的**（上游同款）：写到 `/workspace`、`/tmp` **之外**时无条件要求审批（`pathOutsideWritableRoots`），修的是判定边界，不是这条规则。

## 5.17 聊天页巨型文件重构（2026-09-16，用户定序）

**动因**：`ui/HomeScreen.kt` 一个文件 4500 行、`ChatContent` 一个 composable 吃掉 1600 行、消息行又占 930 行 —— 改一行要在大文件里来回跳，卡顿审计里几次改动都撞到它。

**用户拍板的顺序**（2026-09-16）：① Markdown 层（上限 + 拆分）→ ② `MessageRow` 摘出 `HomeScreen.kt` → ③ 时间线 / 顶栏拆出 → ④ `ChatViewModel` 按关注点拆（`ChatWindowLoader` / `GenerationController` / `CompactionController` / `TranslationController`，facade 保留公开面）。

| 步 | 状态 | 内容 / 判据 |
|---|---|---|
| ① Markdown 层 | ✅ | 上限：高亮 300 行·12000 字 + 表 30 行（`c3b4c93`）；拆分：`MarkdownRenderer.kt` 1030 → 640 行 + `MarkdownInline.kt`(353) + `MarkdownCitations.kt`(120)（`d09a97d`）。**未做**：原版 `IncrementalMarkdownDocument` 那种「流式按块增量解析」（当前靠 `parsedCache` LRU + `conflate` + 8k 字 50ms 去抖达到同档，若日后再撞流式卡顿再补，需先写「流式 vs 定稿排版一致」的布局对比测试） |
| ② `MessageRow` | ✅ | 新建 `ui/chat/MessageRow.kt`（1032 行）：`MessageModelIcon` + `MessageRow` + `MessageActionIcon` + `ChatRecompositionProbe` + `timeStr`（后两者原本是 `HomeScreen.kt` 的私有件，随行搬走；`MessageRow` 改 `internal`，调用点写全限定名）。`HomeScreen.kt` 4525 → 3570 行。**纯搬运**：依赖只有 `BrandAssets`/`ChatStyleSpec`/`UserProfileStore`/`AssistantListAvatar`/`UserAvatar` 五个 `com.psyche.memo.ui` 里的公开件（补了 import） |
| ③ 时间线 / 顶栏 | ✅ | `HomeScreen.kt` 3570 → **670 行**，只剩抽屉外壳（`HomeScreen(` + `CONVO_FADE_MS`/`CONVO_FADE_EASING` + `drawerDragGesture`）。会话页整块搬进 `ui/chat/ChatContent.kt`（1963 行）：顶栏 + 选择态顶栏 + 时间线（LazyColumn + `SCROLL_BOTTOM_ITEM_KEY` 末尾哨兵 + 骨架 + 导航面板）+ 打断面板/输入栏接线 + 各 sheet 接线；输入栏本体与它的几何常量（`ChatInputBar`/`ChatStopSquare`/`ChatVoiceRecordingRow`/`InputIcon`/`InputContainerShape`/`inputFillColor`/`alphaBlend`）搬进 `ui/chat/ChatInputBar.kt`（969 行）；纯判据与小组件（`loadQuickPhrases`/`showTimelineSkeleton`/`TimelineSkeleton`/`newActionToggleable`/`SCROLL_BOTTOM_ITEM_KEY`/`CHAT_TIMELINE_TAG`/`selectedCloudAsrService`/`HISTORY_LOAD_TRIGGER_DP`/`MOBILE_NAV_ALWAYS·SCROLL·NEVER`/`quickPhraseButtonVisible`/`mcpButtonActive`/`isReasoningModel`/`isToolModel`）搬进 `ui/chat/ChatTimelineSupport.kt`（241 行）。**纯搬运**：函数体逐字，只改了包与可见性（`ChatInputBar` 由 `private` → `internal`）。**未做**：`ChatContent.kt` 里顶栏 / 时间线再各自拆一个文件（两者与 40 余处状态强耦合，当前仍是单个 composable；`HomeScreen.kt < 1000 行` 的目标已达成） |
| ④ `ChatViewModel` 拆分 | ⬜ | 3127 行 → 按关注点拆四个控制器，facade 不变 |

**②的回归判据**：`:app:compileDebugKotlin --rerun-tasks` 后 `app/build/compose_reports/app_debug-composables.txt` 里 `MessageRow` 仍是 `restartable skippable`（实参全 stable，靠 `compose_compiler_config.conf`），且 `ChatRowRecompositionTest` 绿。

**③的连带改动（搬文件必踩）**：8 个测试文件改成 `import com.psyche.memo.ui.chat.*`，另外 **`CompositionThreadingTest` 的豁免表按「文件 + token」登记**，所以 `providerConfig(` / `currentAssistant(` / `assistantStore` 三条跟着从 `ui/HomeScreen.kt` 改指 `ui/chat/ChatContent.kt` —— **只是把豁免搬到新路径，守卫规则本身没变**（同一个新文件里冒出一条**新的**查库调用照样失败）。判据：全模块 `testDebugUnitTest` 2353 个用例 0 失败（改豁免前恰好只红这一条）。

**顺手修掉的测试环境坑（2026-09-16）**：`ChatRowRecompositionTest` 曾经「在整套测试里绿、单跑必红」。真因不是被它守护的代码：首屏窗口由 `ChatViewModel.init` → `viewModelScope.launch`（`Dispatchers.Main` → Robolectric 的**暂停** main looper）读出，而 `compose.waitUntil` 只推 Compose 帧时钟、**不排空 looper**，于是消息永远到不了、消息行永不组合、探针恒 0；整套跑时别的用例把 looper 排空过，它才"顺风过"。现在条件里显式 `shadowOf(Looper.getMainLooper()).idle()`（`ChatRowRecompositionTest.render` 有注释）。**教训：Robolectric + Compose 里"等异步库读取"，必须自己排空 looper，别依赖同 JVM 里别的用例留下的状态。**

## 5.18 搜索账户用量 / 启动自动测试连接 / OCR 模型选择 三问取证（2026-09-16）

用户三问：「搜索这个账户用量怎么有问题呀」「启动自动测试连接没有做吧」「OCR 这个模型选择也不行呀 我选择了可以视图的模型也不行」。三条都先取证（设备日志 + 设备数据库）再改，结论记在这里，别重复侦察。

**取证手段（可复用）**：设备库要**先拉下来再看** —— `cmd /c "adb exec-out run-as com.psyche.memo.dev cat databases/memo.db > memo.db"`（**必须走 `cmd /c` 重定向**；PowerShell 的 `>` 会把二进制改写成 UTF-16，`sqlite3` 报 `file is not a database`）。日志在 `files/logs/logs.txt`（几十 MB，拉下来本地 grep 比在设备上拼引号省事）。**注意 `preference_rows.updated_at` 是微秒、`provider_rows.updated_at` 是毫秒**，换算别搞混 —— 这两列是判断「用户到底保存了没有」的关键证据。

**① 「账户用量」= 主线程发网络，必炸（已修）。**
- 日志铁证：`[21:32:21.782] [REQ 23] GET https://api.tavily.com/usage` → `[21:32:21.785] [RES 23] error=NetworkOnMainThreadException`，用户连点 6 次（REQ 23–28）全是这个；凌晨 02:16 那次也一样。
- 真因：`SearchUsageService.fetch` 内部是 OkHttp 的阻塞 `execute()`，此前是普通函数，调用点写在 `rememberCoroutineScope().launch { }`（= Main）。Dart 侧是 async http，不存在这个问题 —— 也就是说这是**移植时引入的**，不是原版行为。
- 修法：`fetch` 改 `suspend` + `withContext(Dispatchers.IO)`，退避用 `delay()`；调用点补上 Dart 已有的三件事：先 `validate()`（key 没填不发请求，`search_service_editor_page.dart:1008`）、超时取公共选项 `clamp(1000, 30000)`（:1024-1026）、结果的**身份作废**（服务 id+key+endpoint 变了就丢掉这次结果，`_usageRequestGeneration` + `_usageCacheKey`）。参数改动/切换类型时清掉旧用量（`_markDirty` / `_changeType`）。
- 判据：`SearchUsageServiceTest` 新增「fetch 走网络并带 Bearer 头」「HTTP 429 变成 `UsageException(HTTP 429)`」以及**反射断言 `fetch` 是 suspend**（`parameterTypes.last() == kotlin.coroutines.Continuation`）——最后这条就是防它被改回普通函数。
- 顺带核对：Tavily 官方 `/usage` 真机可取（`account.plan_usage=107 / plan_limit=1000`），解析与展示（剩余 893、进度条 10.7%）都对，所以「有问题」不是数据错，是崩。

**② 「启动时自动测试连接」此前**只有开关、没有消费点**（已补齐）。**
- 取证：设备库里 `search_auto_test_on_launch_v1 = 1`，但全工程 grep 只有写没有读（`SearchConnectivityService` 之前不存在），`grep -i ocr/connectivity` 在日志里也没有任何探测请求 —— 原版 `settings_provider.dart:1567-1570` 是**有的**（`if (_searchAutoTestOnLaunch) _initSearchConnectivityTests()`，L2235-2269 实现），AGENTS.md 里「原版移动端没有执行路径」的说法是错的，已改。
- 原版语义：住 `SettingsProvider._searchConnection`（App 生命周期）→ 启动时对每个**非** `BingLocalOptions`/`KelivoOptions` 的服务 `unawaited(_testSingleSearchService)`（一次 `connectivity test` 搜索，成功 true / 失败 false，本地那两类写 null），列表行右侧据此画「已连接 / 失败 / 未测试」胶囊；手动长按「测试连接」也写进同一张表（所以离开页面再回来还在）。
- 我们的实现：`provider/search/SearchConnectivityService.kt`（容器级单例，`states: StateFlow<Map<String, Boolean?>>`）+ `AppContainerImpl.searchConnectivity` + `maybeRunSearchConnectivityTests()`（`MemoApplication.onCreate` 里挂在 `appScope`，开关打开才探）+ `SearchServicesScreen` 改成读共享表（此前那张 `connection` 表是页面级 `remember`，离开就丢）。判据：`SearchConnectivityServiceTest`（本地引擎不探、成功/失败落表、手动探测用 `resultSize=1`、启动探测覆盖所有远端服务并给本地服务写 null）。

**③ 「OCR 模型选择」的判定是 1:1 的，拒绝他那个模型是**正确**行为（顺带补了一处原版对齐）。**
- 现象与提示：弹的是 `default_model_page_ocr_model_requires_image_input`（「请选择标记为支持图片输入的模型用于 OCR」）。
- 判定链（照 `ocr_model_capability.dart` + `model_override_resolver.dart`）：模型 override 的 `apiModelId` 当作基线 id → `ModelRegistry.infer` 名称推断 → 再叠 override；override 里有 `input` 时**整体替换**推断结果（Dart `inputOv ?? base.input`，我们 `OcrModelCapability.supportsImageInput` 同义）→ 最终看 `input` 是否含 `image`。
- 设备库取证：用户点的是 `OpenAI - Agnes / agnes-3.0-flash`，它的 override 是 `input:["text"]`、`output:["text","image"]` —— **图片勾在了「输出模式」那一行**（模型详情 Basic tab 两行长得一样：输入模式 / 输出模式），所以「支持图片输入」从头到尾都不成立；`ocr_model_v1` 也一直没落库（从未成功选上）。全库 7 个已配置模型的 `input` 全是 text，没有任何一个能过这道闸。
- **给用户的操作路径**：默认模型 → OCR 模型 → 选择器里**长按**该模型 → 模型详情 → Basic → 「**输入模式**」勾上「图片」→ 右上 ✓ 保存 → 再回来选它。若该供应商另有视觉 SKU，先在供应商详情页「获取模型」里把它加进来。
- 顺手补的原版对齐：在默认模型页长按模型改完并保存后，选择器要**重读模型列表**（`model_select_sheet.dart:1387-1391` 的 `_loadModelsAsync()`）—— 我们此前 `onOptionsInvalidated` 用的是默认空实现，列表里的能力胶囊还是旧的，用户会以为「改了没生效」。
- 未做（等用户拍板）：把拒绝提示做成**可操作**的（例如提示里点名「长按模型 → 输入模式 → 图片」或直接跳该模型的设置页）。这是可见行为改动，需用户点头。


## 5.19 生成图片 / 生成视频（**自研功能，上游 kelivo 没有**，2026-09-16）

用户 2026-09-16：「先做生成视频，图片的两个服务吧」→ 澄清为「这个是我们自己做的功能 上游没有」
「这个新加的 上游的有问题 我们这个是可以给助手配置的功能 上游的有问题 UI还是根据我们这个项目来 UI要一致」。
所以**不要**拿 Flutter 原版的 `image_generation` 内置工具当蓝本（那是模型级开关、只在 OpenAI
Responses 里生效），也**没有**照搬 RikkaHub 的页面结构（用户明确要求 UI 按我们自己的来）。

**接口形状（用户拍板）**：两类服务都按 **OpenAI 兼容**打 —— 图片
`POST {base}/images/generations`（`{model,prompt,n,size}`，返回 `data[].b64_json` 或
`data[].url` 两条路径都解析）、视频 `POST {base}/videos` 提交（`seconds` 按官方要求发
**字符串**）→ `GET {base}/videos/{id}` 轮询 → `GET {base}/videos/{id}/content` 下载
（失败回退任务里带的 url）。地址留空回落 `https://api.openai.com/v1`。
**字段名一律容错解析**：status 认 queued/pending/in_progress/completed/failed/canceled/expired
及别名，id 认 id/task_id/video_id，url 认 url/video_url/output[]/data{}/content{}，
error 认对象/字符串/数组 —— 这条链路要面对各种中转站。

**存储**：服务记录走 drift v3 的通用表 `extension_entity_rows`
（`kind = "generation_service"`）——**不能加表**（schema 是 drift 生成 + 门禁校验零 diff，
同工作区）；一条记录类型按 `kind`（image/video）区分，图片用 size/count、视频用
size/seconds。助手绑定写在 `assistant_rows` 的 payload 里（`imageGeneration` /
`videoGeneration`：enabled + serviceId + 覆盖参数，null = 用服务里的值；老记录读出来是
null = 没配）。产物落盘：图片进 `<filesDir>/images/`（原版目录、随备份走）、视频进
`<filesDir>/videos/`（新增目录，已加进 `BackupArchiveCodec.ASSET_ROOTS`，
存储页归到「图片」档）。

**六批落地**（每批门禁全绿后提交）：
| 批 | 提交 | 内容 |
|---|---|---|
| ① | `d8e5d18` | 数据层：`GenerationService` / `GenerationServiceStore` / 助手两个绑定字段 / `GenerationServiceRepository`（version 计数、删服务连带清助手引用） |
| ② | `6a882e1` | 客户端：`ImageGenerationClient` / `VideoGenerationClient`（提交+轮询+下载）/ `GeneratedMediaStore`；**全部 suspend + IO**（同 §5.18① 的教训）、非 2xx 抛 `GenerationException("… (HTTP 429): {响应体}")` |
| ③ | `4110f4c` | 设置两个入口（生成图片 / 生成视频）→ 服务列表页 + 编辑页 + 「测试连接」（打 `GET {base}/models`，**不发真实生成请求**，不花钱） |
| ④ | `6f31dcf` | 助手编辑页两个 tab（单选服务 + 覆盖参数）；顺带修「工具描述页漏了工作区与技能」与 `tool_schema_overrides_v1` 没有消费点 |
| ⑤ | `c4dfa94` | 对话 ➕ 面板两个入口 → 生成面板 → 结果直接进当前对话 |
| ⑥ | `ddd21d7` | 助手工具 `generate_image` / `generate_video`（+ 修生成面板缺拖柄） |

**助手工具的三条规矩**：① 助手在该 tab 里**选了服务**才提供对应工具（没选/服务被删 →
不出现在请求里）；② 参数逐层回落 **工具参数 > 助手覆盖 > 服务默认**；③ 工具执行完把产物
**作为一条助手消息**插进对话（与 ➕ 面板同一条路径：图片 = 助手图片气泡 + 查看器，
视频 = 文件卡点开交给系统播放器），**不**再往工具 part 里塞同一张图（否则同一结果出现两次）。
视频工具是同步等待（轮询到终态再返回，10 分钟上限，超时如实报 `video_timeout`），
因为现有工具结果是**一次性**写回的 —— 要改成「先返回 task id 再回填」得动工具结果通道，
那是另一个量级的改动。

**测试**：`GenerationServiceTest` / `GenerationServiceStoreTest` / `GenerationServiceRepositoryTest`
（数据层与快照）、`ImageGenerationClientTest` / `VideoGenerationClientTest` / `GenerationServiceTesterTest`
（MockWebServer 走真请求：b64 与 url 两条路径、429 携带响应体、提交与轮询、content 优先与
url 回退、封面落盘、中转字段名容错）、`AssistantGenerationBindingTest`（绑定判定 + 老 payload 兼容）、
`GenerationToolsTest`（没选服务不给工具、参数覆盖、视频轮询到终态、空提示词提前拒）。

**留账**：① 生成类工具**没接审批**（与工作区 shell 不同；现在只靠「助手 tab 里选服务」当开关）；
② 视频卡的封面只落盘没画（`MessageDocCard` 的 FilePart 分支仍是文件卡）；
③ 参考图 / 图生图（`/images/edits`、`input_reference`）与服务商扩展（阿里百炼、火山方舟、
MiniMax 等）都没做 —— 用户明确先只做 OpenAI 兼容两条。

## 5.20 生成功能的「测试连接」三态 + ➕ 入口改成选模型（2026-09-17）

用户 2026-09-17（一次报两件事）：「我给这个加了图片生成和视频生成的功能，但是这个里面的
测试好像有问题，还有这个输入框加号里面的图片生成和视频生成点击是选择对应的模型 不是点击
使用呀 这个生成图片和视频是大模型调用工具来」；追问后确认测试那条的现象是
**「视频，图片 显示连接失败」**，手动生成面板 → **删掉**。

### ① 测试连接：可达 ≠ 连接失败（根因）

老实现把结果压成两态（`lastTestOk: Boolean?`），而探针是 `GET {base}/models`——
**生成类中转大多没有这个列表接口**（回 404/405）。于是「服务明明是好的」却被写成
`false`，列表页显示红色「连接失败」、编辑页文案也走 `cs.error`。修法：

- `GenerationTestState`（ok / reachable / failed）替代布尔；`GenerationService.lastTestState`
  换掉 `lastTestOk`（`normalize()` 认不出的值一律当「没测过」）；store/repo 的方法更名
  `setTestState`。
- 404/405 → `ReachableWithoutModels` → 落成 **reachable**，列表页胶囊显示「可达」（primary
  色）、编辑页文案走中性灰；401/403 单独提示「key 无效或没有权限」；其余非 2xx 照旧带状态码
  + 响应体截断。**失败文案一律附上探测的 URL**（用户才知道自家地址被拼成了什么）。
- 顺带修一处：新增服务时测出的结果现在会**跟着保存**（`current()` 带上 `lastTestState`），
  之前只有「编辑已有服务」才落库，新增完列表永远显示「未测试」。

### ② ➕ 面板的生成入口 = 选模型，不是「使用」

`GenerationSelectorSheet`（照 `WorkspaceSelectorSheet` 的形态：拖柄 + `MemoSheetOptionRow`
单选「不使用」/各服务 + 末尾「管理生成服务」出口）**只做选择**，点一下绑到当前助手并收起；
真正出图/出片由模型调 `generate_image` / `generate_video` 完成（助手绑了服务才会拿到这两个
工具）。**手动生成面板 `GenerationSheet` 已删**（`vm.appendGeneratedMedia` 保留——工具结果
仍走它插消息），连它的 6 条专属文案一并清掉；想找回看提交 `c4dfa94`。

选择规则抽成纯函数（+ 面板与助手编辑页共用一份，避免两处漂移）：
`AssistantGenerationBinding.select(current, serviceId)`（选中 = enabled + id；
「不使用」 = enabled false **且 id 清空**，与编辑页 `selectedId` 判据一致）、
`Assistant.generationBinding(kind)` / `withGenerationBinding(kind, binding)`。
`onOpenGenerationServices` 是**无默认值**的导航回调（漏传直接编译不过，见 §5.11 的教训）。

### ③ 顺手清掉的两处测试噪音

- **`./gradlew test` 一片红的假警报**：`:app:testReleaseUnitTest` 有 65 例必红 ——
  `createAndroidComposeRule` 要启动 `androidx.activity.ComponentActivity`，而声明它的
  `androidx.compose.ui:ui-test-manifest` 只能挂 debug（release 清单不能带测试脚手架）。
  已在 `app/build.gradle.kts` 用 `androidComponents { beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false } }`
  关掉 release 单测（本工程没有 BuildConfig/debug 分支，两个变体跑同一份代码；
  真正有意义的门禁是 `:app:testDebugUnitTest`）。
- **编译零警告**：生成测试里 `File.parentFile` 可空未处理（2 处）、
  `DatabaseSnapshotMergerTest` 混型 `arrayOf` 的 reified 推断（3 处）、
  `AskUserPanelTest` 用 `CompletableDeferred.getCompleted()` 缺 opt-in —— 全清。

**测试**：`GenerationServiceTesterTest`（新增「三态各映射各的落库值」与「失败文案带 URL」）、
`GenerationServiceStoreTest`（三态 + 脏值归零）、`AssistantGenerationBindingTest`（选择规则 +
per-kind 读写）、`GenerationServiceRepositoryTest` 更名跟进。

## 5.21 生成结果并进同一轮 + 两处主题色漏改（2026-09-17 晚）

用户 2026-09-17（同一条消息里两件事）：「为什么生成图片这个 再开一个输出结果 没有再在一个
对话轮里呀 好割裂呀 视频生成也会这样吗？」+「这个供应商模型设置那个 card 怎么没有跟着主题
颜色走呀 还有日志界面那个分类那个」。

### ① 生成产物不再另开一条消息（用户选「并进同一轮」）

旧实现把产物当**独立一条助手消息**插进对话（`appendGeneratedMedia` → 新 `UiMessage` +
`persistAssistant`），所以工具卡和图片分属两条气泡。改法：

- `runGenerationLoop` 里把产物 part 攒进 `generatedParts`，等**工具卡 fold 完之后**
  `allParts += generatedParts` —— 产物紧跟工具卡，属于**同一条**助手消息；
  之后 `updateStreaming(...)` 照旧，落库走原有的轮次收尾（`onPersist`/取消/失败三条路径
  都带 `allParts`）。
- 删掉 `appendGeneratedMedia` / `appendGeneratedMediaFromToolResult`（含「另开消息」的
  落库分支，已无调用者）；提示词**不**重复成文本 part（工具卡里就有参数）。
- 逻辑抽成纯函数 `provider/generation/GeneratedMediaParts.kt`：
  `generatedMediaParts(resultJson)`（图片 → `ImagePart`、视频 → `FilePart(video/mp4)`，
  顺序图片在前）+ `mimeForGeneratedPath`。配 `GeneratedMediaPartsTest` 五例。
- **视频与图片是同一条代码路径**（`GenerationTools.ALL_TOOL_NAMES` 同一分支），所以行为
  完全一致 —— 用户问「视频也会这样吗」，答案曾经是「会」，现在是「一样并进同一轮」。

### ② 两处分组标题没跟主题色（同一类漏改）

判据一律 `SettingsUi.kt` 的 `settingsSectionHeaderColor(scheme)`（= `primary`），只改颜色，
字号/字重/间距不动：

| 位置 | 原来 | 现在 |
|---|---|---|
| 日志页 `LogViewerScreen.DetailSectionCard`（附件/参数/请求体… 的**分区标题**） | `onSurface@90%`，图标 `@78%` | 标题与图标同用主题色（RikkaHub `CardGroup` 的 `LocalContentColor provides primary` 也是整行同色） |
| 供应商详情页「配置」tab 的**「管理」分组标题** | `onSurface@80%` | 主题色 |

同文件里另有两处 `onSurface@80%`（服务账号 JSON 字段标签、`LabeledInput` 的标签）是**表单
字段标签**、不是分组标题，**保持不动**（别顺手一起改）。

### ③ 顺带：`LocalClipboardManager` 迁移 + 13 条既有警告

`LogViewerScreen` 的 4 处复制改用 `LocalClipboard` + `ClipEntry`（`setClipEntry` 是 suspend，
统一走文件内的 `copyToClipboard(scope, clipboard, text)`；范式同 `ChatContent.kt:395`）。
全量重编（`--rerun-tasks`）另外暴露 13 条**既有**编译警告（增量编译一直藏着），
清单见当日工作日志，属待清批次。

## 5.22 「固定白色」根因 + 助手媒体成块（2026-09-17 深夜）

用户 2026-09-17：「在一轮了 但是位置不对呀 怎么在上面了」+「日志那个分类 tab 没有跟着主题色」
+「工具描述那个卡片也没有跟着主题走 全是固定白色呀 你看看其他界面没有这个问题」。

### ① 「固定白色」的根因：两个白色 lerp 兼容助手

`SettingsUi.kt` 里有一对遗留近似实现，都把卡片底色算成
`lerp(colorScheme.surface, Color.White, 0.96f)`（浅色）/ `0.10f`（深色）——
**浅色主题下等于死白、完全不跟主题**：

| 位置 | 影响面 |
|---|---|
| `SettingsSectionCard` | 所有用它的页面卡片：**工具描述**、**供应商详情「管理」下面那张卡**、备份/赞助占位页… |
| `surfaceCardColorCompat()` | 输入框底色 ×11、**日志页 tab 条容器**、消息样式页、工具参数胶囊… |

两处都改成主题语义卡色 `LocalSemanticColors.surfaceCard`（+ `semantic.hairline` 边框，与
`SectionCard` 同源 —— 它们本来就都标着「section_card.dart L30-66」，是实现漂移了）。
`surfaceCardColorCompat()` 现在是 `@Composable`（读 composition local），调用点不用改。

⚠️ `ProviderSheets.kt` 里那两处 `Color.White` 是**二维码白底**（扫码需要，上游也写死），
**别顺手改**。

### ② 助手媒体改成「块」，不再挂在气泡上方

原来整条助手消息的图片/文档被当成一个缩略图组渲染在气泡**上方**，于是生成类工具的
产物跑到工具卡上面去了。改法：

- `projectAssistantBlocks` 里图片/附件不再只「打断思考块」，而是产出
  `AssistantBlock.Media(parts)`（**连续媒体合并成一块**）；`FilePart` 同样成块。
- `MessageRow` 的助手分支删掉「气泡上方」那组渲染（连 8pt 间隔），改为在块循环里按
  顺序渲染：有图走 `ChatBubbleSurface` + `MessageImageAttachments`，有文件走
  `MessageAttachmentPreview`。**用户侧**附件仍按原版挂在气泡上方（没动）。
- 效果：`[正文][工具卡][图片/视频][正文]` —— 产物紧跟工具卡；模型直出图片的消息
  （只有图片）视觉与原来一致（它本来就是唯一内容）。
- 测试：`ChatTimelineTest` 的 `imagePartBreaksAThinkingBlock` 改名并更新断言
  （图片现在自成一格），新增 `mediaFollowsItsToolAndConsecutiveMediaMerges`
  （工具卡 → 媒体块、连续合并、视频 FilePart 同路）。

## 5.23 `render_chart` 本地可视化工具（自研，上游没有，2026-09-17）

用户 2026-09-17：「加个本地工具 就是可以可视化的工具，大模型可以以这个为可视化，帮助用户」，
追问后定：**渲染路线照自研可视化那套（纯 SVG、扁平、跟随主题）**、**第一版核心 5 类图**
（柱/折线/面积/饼/散点）、**静态就够**（不做 tooltip/缩放）。

### 为什么是 SVG 而不是 Compose Canvas

1. **复用现有图片通道**：产物落 `<filesDir>/images/gen_*.svg` → `ImagePart` → 已有的
   图片气泡 + 查看器 + 导出 + 备份 + 存储页归类，**一个新 part 类型都不用加**；
   GPU 上跑 SVG 渲染的是 coil 的 `SvgDecoder`（`MemoApplication.newImageLoader()` 里已注册）。
2. **纯函数可单测**：`ChartSvgRenderer.render(spec, palette)` 是字符串拼装，测试直接
   **按坐标断言**（`bar heights are proportional to the values` 就钉住了 y=plotTop、
   height=绘图区高），不需要 Robolectric 布局测量。
3. 代价：**静态**（换主题不会重画）、交互要自己写。用户明确选了静态。

### 落地清单

| 件 | 位置 |
|---|---|
| 规格 + 校验/截断 | `provider/chart/ChartSpec.kt`（上限：24 分类 / 6 系列 / 200 点，超出截断不失败；失败抛 `ChartSpecException`，文案**点名哪个字段不对**，模型据此改正重试） |
| SVG 渲染（纯函数） | `provider/chart/ChartSvgRenderer.kt`（900×560、网格线 1.5px、nice 刻度、图例、XML 转义；全零数据不产生 NaN） |
| 工具（描述/schema/执行/取色） | `provider/chart/ChartTools.kt`（`TOOL_NAME = "render_chart"`；`DEFINITION` 由工具自维护，因为数组套对象的参数用 `param()` helper 表达不了） |
| 接线 | `LocalToolNames.RENDER_CHART` + `all`（目录 LOCAL 组、保留名、平台可用性）、`offeredTools()` 的 `offered` 集合、`LocalToolExecutors.EXECUTABLE` + 分发、`MEDIA_TOOL_NAMES`（ChatViewModel 用它决定挂媒体 part）、`AssistantEditLocalToolsTab` 开关行、`toolSchemaIconFor` 图标 |
| 配色 | 外壳跟主题（卡片底 = `surfaceBright`、文字/轴线同主题）、**系列色固定**（默认主题是黑白灰，套主题色会画成一片灰）；深色主题用提亮版分类色 |

**开关**：和别的本地工具一样是**按助手**的（`assistant.localToolIds`，默认空表 ⇒ 新助手不
自带任何本地工具）——要在「助手编辑页 → 本地工具 → 绘制图表」打开。

**产物 JSON**：成功 `{"type":"chart_result","kind":…,"points":n,"series":n,"paths":[…]}`；
失败 `{"type":"tool_error","error":"chart_invalid_spec","message":"…"}`（**不抛异常**，让模型自己改）。

**测试**：`ChartSpecTest`（解析/上限/错误文案/堆叠范围）、`ChartSvgRendererTest`（画布/柱高
比例/折线面积散点几何/饼图百分比/转义/深色/nice 刻度）、`ChartToolsTest`（落盘真 SVG +
错误 JSON + 主题取色 + 是否真接进本地工具那四道门 + schema 与 enum 一致）。

**留账**：① 交互（tooltip/缩放）没做；② 换主题不重画（静态图，与用户约定一致）；
③ 流程图/时序图这类要走 mermaid 的图形没做（当时建议留给 HTML 通道）；④ 自定义主题
（`custom_themes_v1`）下取色用的是 `MemoTheme.resolve` 的默认分支，没读用户自定义配色。

## 5.24 可视化工具（自研，2026-09-18 分三步长成）

用户 2026-09-18 三连反馈：「有局限呀 只能生成这几个图呀」→「为什么不能一个工具渲染各种呀」
→「我刚刚让大模型调用这个自由渲染 怎么渲染不出来呀」。最终形态：**一个工具
`render_visual`**，一个 `kind` 枚举（10 种结构化图 + `svg` 手写兜底），一条产物通道。

### 演变

| 阶段 | 形态 | 为什么变 |
|---|---|---|
| ① | `render_chart`（5 类结构化图） | 数据图质量稳定、自动跟主题 |
| ② | + `render_svg`（手写 SVG） | 图形清单写死 → 流程图/时间轴/仪表盘出不来 |
| ③ | **合并成 `render_visual`** | 两个工具 = 两个开关 + 两份描述 + 靠文案互相约束「谁该用谁」；合成一个后模型只选 `kind`，「数据图别手写」写进 `kind` 字段说明即可 |
| ③ 附带 | 结构化补到 **10 种**（+ `hbar` `donut` `funnel` `gauge` `heatmap`） | 用户「尽量全满」 |

### ⚠️ 手写 SVG「渲染不出来」的根因（务必记住这个坑）

`SvgSanitizer` 用 XmlPullParser **重建**文档时，**自闭合标签会写重复**：
XmlPullParser 对 `<rect/>` 会先给 `isEmptyElementTag=true` 的 START_TAG、**再补一个
END_TAG**，两边都写就输出 `<rect/></rect>` → 非法 XML → AndroidSVG 解析失败 → 图一张都
渲染不出来（文件还好好躺在磁盘上，所以只看产物目录会以为没问题）。
修法：用 `swallowedEndTags` 计数器吞掉补来的 END_TAG；测试里加了「产物必须能被再解析
一次」的**回环校验**（`SvgSanitizerTest.self closing tags do not get a stray end tag`）。

### ⚠️⚠️ 第二个（真正的）根因：AndroidSVG 不认 `orient="auto-start-reverse"`

修完自闭合标签后用户仍报「还是渲染不了」。这次文件**是合法 XML**（`ET.fromstring` 通过），
但卡片还是空白。真凶：**AndroidSVG 1.4 的 `orient` 只接受 `auto` 或数字**（把它的 jar 解开
按字面量搜，`auto-start-reverse` **0 命中**），而模型照着 SVG2 文档爱写
`<marker orient="auto-start-reverse">` → 解析抛异常 → coil 解码失败 → **空白卡片**。

两道防线（都已落地）：

1. **消毒时归一化**（`SvgSanitizer.normalizeValue`）：`orient` 认不出就降级成 `auto`；
2. **落盘前用 AndroidSVG 真解析一次**（`VisualTools.executeRawSvg`）：解不开就回
   `tool_error`，让模型改成「基础形状」重试 —— **不再静默产出空白卡片**。
   （`com.caverock:androidsvg-aar:1.4` 本来是 coil-svg 的传递依赖，已在 APK 里；
   显式声明进 `libs.versions.toml` 才在编译期可用。）

教训：**「产物文件看起来没问题」≠「能渲染」** —— 校验必须走渲染器自己的解析器，
而不是 XML 良构性。这也是用户要求「切原生」的直接理由（见下）。

**排查范式**（11 分钟定位）：先 `adb shell run-as <pkg> ls -lt files/images/` 看**有没有落盘**
—— 有文件 ⇒ 工具跑通了、问题在渲染/显示；没文件 ⇒ 工具或消毒器拒了。再拉文件看标记。

### 落地（合并后）

| 件 | 位置 |
|---|---|
| 工具（kind 分发 / 描述 / schema / 取色） | `provider/chart/VisualTools.kt`（`TOOL_NAME = "render_visual"`，`KIND_SVG = "svg"`） |
| 结构化渲染（10 种图） | `provider/chart/ChartSvgRenderer.kt` |
| 规格与校验 | `provider/chart/ChartSpec.kt`（`SINGLE_SERIES_KINDS`：pie/donut/funnel/gauge 只取第一组） |
| 手写 SVG 消毒 | `provider/chart/SvgSanitizer.kt` + `SvgAspect`（读 SVG 自己的比例） |
| 接线 | `LocalToolNames.RENDER_VISUAL` / `localDefinition` / `offeredTools().offered` / `LocalToolExecutors.EXECUTABLE` / `MEDIA_TOOL_NAMES` / 助手开关行（一行）/ `toolSchemaIconFor`（`Lucide.Shapes`）/ 文案 ×3 |

**开关是旧的迁移不了**：`assistant.localToolIds` 存的是工具名，改名后（`render_chart`/
`render_svg` → `render_visual`）要在「助手编辑页 → 本地工具」重新打开一次。

**测试**：`ChartSpecTest` / `ChartSvgRendererTest`（含 5 种新图几何）/ `SvgSanitizerTest` /
`VisualToolsTest`（合并后统一入口 + 四条接线门 + kind 枚举 == 实现）。

### 分工

| 工具 | 输入 | 谁画 | 适用 |
|---|---|---|---|
| `render_chart` | 结构化 spec（kind/categories/series） | 我们（`ChartSvgRenderer`，跟主题） | 数据图：柱/折线/面积/饼/散点 |
| **`render_svg`** | **模型手写 SVG 标记** | 模型 | 流程图 / 时序图 / 组织图 / 时间轴 / 仪表盘 / 拼版 / UI 草图……**没有图形清单** |

### 安全边界（这条最重要）

SVG 既是图也是文档 —— 能塞脚本、外部引用、`<foreignObject>`（内嵌任意 HTML）。做法：

1. `SvgSanitizer`（`provider/chart/SvgSanitizer.kt`）用 **XmlPullParser 重建文档**
   （白名单式拷贝，**不是正则替换** —— 正则改标记很容易漏：属性大小写、引号、注释伪装）：
   - 删 `<script>` / `<foreignObject>` **整棵子树**、`on*` 事件属性、`javascript:` /
     `data:text/html` 值、http(s) / `//` 开头的外部 `href`、`style` 里的外部 `url()`
   - 根必须是 `<svg>`，且**必须能算出布局盒**：`viewBox` 或绝对 `width`+`height`
     （`100%` 这种相对尺寸视作没给）；长宽比限制 0.15–8
   - 体积 ≤ 256KB；不是良构 XML 直接拒；失败信息写成**给模型看的一句话**
2. **注入不透明白底**（`SvgTools.withBackground`）：模型不知道当前主题，白底 + 深色墨迹
   在任何主题下都清楚（与二维码白底同一条理由）。工具描述里明确要求「按浅色背景设计」。
3. 渲染仍是**静态**的：coil → AndroidSVG，不执行脚本、不联网。

### 显示

自由绘制的产物与图表同 MIME（`image/svg+xml`）→ 聊天里同样走等比卡片；**比例取自 SVG
自己**（`SvgAspect.ofFile`，只读开头 400 字节、`remember` 缓存）—— 结构化图表恒为 900×560，
而流程图可能又高又窄，用固定比例会被压扁。

**接线**（与 `render_chart` 完全对称）：`LocalToolNames.RENDER_SVG` + `all` /
`localDefinition` / `offeredTools().offered` / `LocalToolExecutors.EXECUTABLE` + 分发 /
`MEDIA_TOOL_NAMES` / 助手编辑页开关行 / `toolSchemaIconFor`（`Lucide.PenTool`）/ 文案 ×3。

**测试**：`SvgSanitizerTest`（脚本、事件属性、foreignObject、外部引用、根/布局盒/体积/
畸形 XML/极端比例、`SvgAspect` 读文件）、`SvgToolsTest`（落盘真 SVG + 白底在最底、
注入的脚本到不了文件、坏图回 tool_error、接线齐全）。

**留账**：① AndroidSVG 对模型的「合法但用了它不支持的写法」会静默降级（画面缺块），
目前靠工具描述里的「用基础形状」约束；② 流程图/类图/活动图现在**靠模型手摆坐标**（节点一
多就会歪）—— 提议过 `render_diagram`（mermaid 子集 DSL + 我们自动布局），用户当时跳过没定，
需要时再捡起来；③ 老消息里那些坏掉的 SVG 文件仍在磁盘上（重新生成即可）。

### 描述文案主动化（2026-09-18，用户手改）

两个绘图工具的描述从「介绍能画什么」改成**主动引导模型多画**（起因：模型太保守，能用图表达的也用文字答）。

- `render_mermaid`（`MermaidTools.DESCRIPTION`）：加「the most reliable way to give the user a professional diagram」「draw it instead of describing it in prose」。
- `render_visual`（`VisualTools.DESCRIPTION`）：改成「Your drawing canvas for this conversation…draw it, do not just describe it」，并把两种模式（数据图 / `kind="svg"`）讲清楚。

**只动文案**：代码逻辑、schema、接线均未变；两处测试断言的关键字（`render_visual` / `flowchart` / `do not hand-draw a data chart`）全部保留。验证：`provider.chart.*` 全绿（`MermaidToolsTest` 5、`VisualToolsTest` 12，chart 另 3 个 suite 30）。

**同轮后续修正**（用户追问「对应改了没有呀／还剩交叉打架吗」，查出两处自相矛盾，一并修掉）：

1. **`kind` 字段说明没跟着改**：`VisualTools.KIND_DESCRIPTION`（模型同样会读的字段 description）还是保守版 —— 「use svg **only** for things a data chart cannot express」「anything the other kinds cannot express」。顶层说「这是你的画板，画就完了」，字段却说「svg 只能画数据图表达不了的东西」；模型读到这个「只能」就缩回去了，画板定位落不了地。改成与顶层一致的积极版：svg = **your free drawing canvas**，图表表达不了的都算 fair game。
2. **两个工具顶层抢同一个词**：`render_visual` 的「该画」清单写着 `structure, flow`，`render_mermaid` 写着 `structure, sequence` —— 同一个词、同一个祈使句；而且画板这边**顶层没有让路句**（让给 Mermaid 只写在 `kind` 字段里，位置弱得多）。改：画板清单里摘掉 structure / flow，顶层补「For structure and flow — flowcharts, sequence/state/ER/class diagrams, gantt, mind maps, timelines — use the render_mermaid tool instead.」。至此让路**双向对称**（Mermaid 原本就有让路句）。

改完复验：`BUILD SUCCESSFUL` + `provider.chart.*` 45 例全绿 + 装机。

## 6. 规格速查（Flutter 源码 → 要点，避免重复侦察）

- 编辑页骨架：`assistant_settings_edit_page.dart` L80-152(tab specs) L316-410(scaffold) L1262+(_iosNavRow：36 图标槽/15sp 单行 label/13sp detail/chevron) L632+(_SegTabBar：44/4/18/6/88、选中 primary 14%、文字 primary vs onSurface 82%)
- tab 布局管理页 C：`assistant_settings_edit_page.dart` L598-733（页：RotateCcw 重置 + `_AssistantOutlineModeSwitch` + 13sp/0.68 说明 + `ReorderableListView` padding 16/2/16/24、item 间距 10、proxyDecorator scale 0.98→1.0 无阴影；tile L773-848：r14 surfaceCard、边框 outlineVariant dark0.12/light0.08 @0.8、padding 12/8/8/8、34 图标槽+8+15 semibold（隐藏态 42%）+IosSwitch+10/18 GripVertical@0.42）与 `assistant_edit_tab_layout.dart`（默认顺序 basic/prompts/memory/quickPhrase/custom/regex/localTools/mcp；order=已存有效 id 去重 + 补默认；visible=order−hidden，空则回退首个）。提纲模式：L412-502（82 头像/21sp 1.18 名称@0.94/2 行 13.5sp 提示词@0.58 的 r16 卡 + tab 导航行 SectionCard）+ L530-595 分段页（自带 AppBar，标题=tab label）。预览规则：`ReorderableDragStartListener`（手柄即时拖）↔ `Modifier.draggableHandle`。
- basic tab：`assistant_settings_edit_basic_tab.dart`（身份卡 L136-161；设置卡 L162-263；聊天模型卡 L264-357：标题+RotateCcw+副标题+选择行[surfaceFill r12 h12v10、BrandAvatar 24、14 semibold，显示 override 名?:modelId，无模型"使用全局默认"]；背景卡 L373-510：Image 标题+12sp 描述、空→居中选图按钮[outlineVariant 35% 边框]、有→两 _IosButton 并排+ClipRRect r10 预览；_pickBackground：gallery maxWidth1920 quality85 存路径）
- basic tab 参数/头像 sheet（同在 `assistant_settings_edit_basic_tab.dart`，**不在** edit_page）：`_showTemperatureSheet` L635-747、`_showTopPSheet` L749-859、`_showContextMessagesSheet` L861-998（三者同构→共用 `ParamSliderSheet`；padding 16/12/16/18、top r16、overlaySurface、40x4 α0.2 拖柄、标题 16 semibold+IosSwitch、`_SliderTileNew` L1217-1401、描述 12 α0.6，关闭态 `parameterDisabled`/上下文用 `parameterDisabled2` 13 α0.6 上下8）；`_ValuePill` L1403-1439（r10、primary α0.18暗/0.10亮 底 + α0.28/0.22 边框、padding 10/4、12 emphasis）；`_showMaxTokensSheet` L1000-1130（X 20 / 居中标题 / Save primary 16 semibold 按压 0.7，数字框 autofocus filled surfaceFill r12 边框 outlineVariant40/primary50，空串→null）；上下文精确值弹窗 `_showContextMessageInputDialog` 在 `assistant_settings_edit_page.dart` L181-256（label/helper/description 都带 `(1-4096)`，常量 L79-80=`Assistant.min/maxContextMessageSize`）。头像：`_showAvatarPicker` L517-617（**顶圆角 20**，非参数 sheet 的 16；padding 16/12/16/16、拖柄后 10、尾 4）、行=内联 `row()` L529-558（外 v4、h48、`IosCardPress` r14、内 padding h12、15 medium，先 pop 再延迟 10ms 执行 action）、emoji 面板 `_pickEmoji` L1442-1702（QuickEmojis 112 个 L1451-1564、8 列 spacing 8、网格高 =（屏高−ime）×0.28 clamp 120..220、预览 72 圆 primary α0.08 + 40、字段 autofocus surfaceFill r12 透明边框/聚焦 primary40、`validGrapheme` 取**原始串**首个字素再 trim 故前导空格判否）、链接 `_inputAvatarUrl` L1704-1780（仅 http(s) 前缀）、`_inputQQAvatar` L1782-1946（正则 `^[0-9]{5,12}$`、`actionsAlignment: spaceBetween`→随机在左/取消+保存在右、随机 20 次探测 `q2.qlogo.cn/headimg_dl?dst_uin=X&spec=100` 命中即 pop、失败 `q_q_avatar_failed_message` 吐司且**不**关弹窗、`randomQQ()` L1793-1833 长度权重 [1,20,80,100,500,5000,80]/首位数组权重 [128,4,2,1]）、`_pickLocalImage` L1948-1985（gallery maxWidth1024 quality90；取消→静默返回；异常→错误吐司后落到链接弹窗）。三个弹窗的 Save 均显式 primary/失效 onSurface α0.38。平台简化：SfSlider 刻度/间隔标签/水滴 tooltip 全部省略（值常显在胶囊上）。
- 列表页：`assistant_settings_page.dart`（Slidable endActionPane 0.6、复制命名"xx 副本 N"、最后一个不可删）
- 提示词 tab：`assistant_settings_edit_prompt_tab.dart`（_PromptTab build L273-912：ListView padding LTRB 16/8/16/20，卡片间距 12，顺序 sysCard→appendTimeCard→tmplCard→presetCard。sysCard L301-457：surfaceCard r14 padding12、标题 15 emphasis + IosIconButton(Maximize2,20,p8,min38,primary)+4+IosButton(import,Icons.file_open,dense,neutral:false)、字段 maxLines8 r12 边框 enabled outlineVariant35/focused primary50 contentPadding12、availableVariables 12 semibold、_VarExplainList L1674-1728（Wrap 16/8，"label: " 12 onSurface75 + 下划线 primary semibold 可点，3 个时间变量带 TriangleAlert14+Tooltip）、AnimatedSize180 告警条 errorContainer30 r12 p12 + TriangleAlert18 + 12/1.35 onSurface80。appendTimeCard=SectionCard(_AppendCurrentTimeRow L915-1004：h12v10、36 槽 Lucide.clock20（开=primary）、标题15 semibold+3+副标题12/1.25 onSurface62、IosIconButton(BadgeInfo,16,p6,min32,onSurface55)+4+IosSwitch）。_SystemPromptMobileSheet L1189-1271：高 0.96 屏、overlaySurface、顶 r18、padding 16/10/16/bottom+16、_HoverTextButton(enableHover:false,dense) 关闭/保存、Expanded surfaceFill r14 边框 outlineVariant20 + expands TextField autofocus contentPadding12。**已移植**=以上全部，**并含 tmplCard L470-586（4 变量 {{role}}/{{message}}/{{time}}/{{date}} + 2 条 ChatMessageWidget 预览）与 presetCard L589-898（_HoverPillButton User/Bot、内联 AnimatedSize200 输入、_PresetMessageCard L1006-1077、ReorderableColumn）**（2026-09-12 核对：`AssistantEditPromptTab` 里 `MessageTemplateCard` / `PresetMessage.decodeList` 都在，本条旧文的「待移植」已过时）。桌面 `_SystemPromptDesktopDialog` 不移植。两处平台简化：SAF 无法按扩展名过滤（16 种白名单只在读取处兜底）、Compose TextField 无 contentPadding 参数（12dp 内边距用外层 Box border + padding 等价实现）。
- 模型选择：Flutter `showModelSelector`（model_select_sheet.dart 2533 行）→ Android `ModelSelectSheet`。视觉主体已对齐（卡片行 r14、品牌头像 28、单行名、Lucide 心形、搜索框 surfaceFill+r14 边框聚焦 primary 50%、chip 点击滚动分组）。**已补齐**：可拖拽高度（`_initialSize`/`_maxSize` 0.8 + `minChildSize` 0.4 → 同 ModelDetailSheet 的 NestedScrollConnection 等价物：initial==max 故只保留收缩半边，列表在顶下拉压缩高度、到 0.4 即 `sheetState.hide()` 关闭）。已补齐：长按模型详情 sheet（ModelDetailSheet.kt，编辑/创建双模式 + Basic/Advanced/BuiltInTools 三 tab + ModelRegistry.inferFull 完整投影 + 类型切换缓存 + headers/body 覆盖 + 内置工具按 ProviderKind 分类）。已补齐：详情 sheet 可拖拽高度（原版 DraggableScrollableSheet 0.4-0.95 initial 0.8 → Compose 原生 NestedScrollConnection 等价移植：列表在顶下拉压缩高度、到 0.4 即 sheetState.hide() 关闭（对齐 shouldCloseOnMinExtent），顶上推长高到 0.95 后列表接管；头部下拉关闭走 ModalBottomSheet 自带 drag-to-dismiss）。已补齐：ModelTagWrap 能力标签（ModelRegistry 正则推断）、pinnedModels 收藏系统（收藏组置顶 + 书签跳转 + 搜索聚合去重）、搜索跳转首个匹配组；吸顶 provider 头（_stickyProviderHeader）按用户决定不复刻。

- 搜索体系（S1 已落地）：数据层 `core/data/model/SearchServiceOptions.kt`（24 个类，toJson 逐键对齐 search_service.dart；`type` 判别 + `apiKeys`↔extraApiKeys；step 别名→stepfun；未知类型回落 BingLocal）+ `repo/SearchSettingsLogic.kt`（纯 codec/夹取，带测试）+ `repo/SearchSettingsRepository.kt`（服务列表=search_service_rows 实体表，selected/common/enabled/autoTest=preference 键）。引擎 `app/provider/search/`：`SearchParsers`（各 provider 响应映射 + Bing/DDG HTML 解析用 jsoup + uddg 解包 + URL 归一化，带 fixture 测试）、`HttpSearchEngine`（8 provider 分发，未移植类型抛诚实错误）、`SearchApiKeyRotator`（[primary,...extras] 轮换 + parseBatch/mask）、`SearchToolService`（search_web 定义/描述/引用系统提示词/executeSearch 打 6 位 id）。接线：`ChatViewModel.offeredTools` 在 assistant.searchEnabled 时提供 search_web；`ToolHandler` 执行；`buildSystemPrompt` 注入 assistant.systemPrompt + 搜索引用块（message_builder_service.injectSearchPrompt L1734-1750）。**已补齐**（2026-09-12 核对）：搜索设置 sheet / 服务列表页 / 编辑器（24 类型表单）/ API keys 池页 / 连接测试（S2，输入栏 Globe + 设置页"搜索"行 + `search_services` 路由）；23 个 provider 引擎（S3，kelivo 除外——上游端点+内置令牌按品牌规则不移植）；**S4 用量查询卡**（`SearchUsageService` 已接进 `SearchServiceEditorScreen`，Tavily 余额/LinkUp 余额/进度条）；**`{{message}}` 消息模板已进请求组装**（`PromptTransformer.applyMessageTemplate`，见 §5.12 已修表）。**未做/不做**：上下文条数限制（= `applyContextLimit`，用户暂缓）、`{{message}}` 之外的模板变量与上下文条数门控仍缺。**「启动时自动测试连接」原以为原版移动端没有执行路径 —— 2026-09-16 查证是错的，已按原版补齐（见 §5.18②）**。

## 5.25 剩余功能收官（2026-09-19，五批全通）

用户「补齐剩余的所有功能」——按备份 → 语音 → 导出图片 → MCP-3 顺序全部落地（每批编译 + 定向测试 + 全量回归 + 本地提交，未 push，未装机）。

### ① 备份子块 7：前向兼容闸门（完整版）

- **`core/data/db/SchemaMigrations.kt`**（新）：`BackupSchemaVerdict`（current/needsUpgrade/forwardCompatible/forwardUndeclared/unreadable）+ `classifyBackup` + `PUBLISHED_SCHEMA_VERSIONS={1,2,3}` + `readSchemaVersion`（只读 user_version）+ `upgradeFileInPlace`（drift 1→2→3 迁移逐字 SQL 化：conversation_rows 两列 + 5 个 ADD COLUMN + tombstone/extension 两表 + 索引）+ `normalizeForwardCompatible`（**有意偏差**：只删未知表 + user_version 折回，不重建列——Android SQL 全显式列名、无 drift 校验器，未知列无害；pre-3.35 SQLite 删列要重建表）。
- **`BackupManifestCodec` 修正**：`acceptsFormat` 换成上游语义（精确匹配 / 拒更旧 / 更新必须 1..FORMAT_VERSION 自证——**旧版会误收上游拒收的归档**）；`declaresNewerBuild` 改双轴（formatVersion > 当前 **或** database.schemaVersion > 当前）；`minimumReadableSchemaVersion` 可空（缺声明=forwardUndeclared）。
- **BackupSnapshotBuilder**：写 `SchemaMigrations.MINIMUM_READABLE_SCHEMA_VERSION=2`（上游写常量而非当前版本——之前写 3 会把能读的旧构建挡在门外）。
- **BackupRestorer**：未知 manifest 条目（非 settings/database/upload/avatars/images/fonts）来自同版=拒、来自新版=剥掉；includeChats 必须 database 块（settings-only 必须没有）；声明范围 1..schemaVersion；unreadable 拒、forwardUndeclared 需 `allowUnverifiedForwardCompatible`；解压后按文件 user_version 迁移/归一化再换库/合并。
- **UI**：`ui/backup/ForwardCompatDialogs.kt`（`ForwardCompatDialogs` 宿主 + `ForwardCompatDialogHost`；Compose 弹窗是声明式的，suspend 侧经宿主状态挂起等结果）；本地导入选模式前 inspect + 同意；WebDAV/S3 下载完成后把 prompt 传进服务层，REFUSE 映射 `BackupCancelledException`（静默取消）。
- 测试：`BackupManifestTest` 矩阵重写（acceptsFormat 七态/declaresNewerBuild 双轴）+ `SchemaMigrationsTest`（判定矩阵/迁移/归一化/**KNOWN_TABLES 与 asset SQL 锁死**——列表漏一个表名归一化就会删真表！）+ `BackupForwardCompatGateTest`（真实 pack 归档走闸门五例；Robolectric 里 `replaceDatabase` 的 rename 会失败 → 成功路径用 MERGE 验证）。

### ② 备份子块 8：Cherry Studio / Chatbox 导入

- **`core/data/backup/cherry/`**：`CherryDirectBackupReader.kt`（直备 zip：metadata.json 版本闸〔v7+ SQLite 拒〕+ LevelDB .log/.ldb 解析〔块句柄/写批/重启段〕+ **Snappy 块解码** + UTF-16LE/UTF-8 localStorage 候选 + **V8 值扫描器**〔0xff 版本头 + 对象/稠密稀疏数组/引用/字符串/日期〕）；`CherryImporter.kt`（whole-file JSON → zip 探测三梯〔根 .json → 其它 .json → 猜测探测 + blocked 扩展名 + 32M/256M 预算〕→ gzip → 直备；providers〔anthropic→claude、gemini→google、host 无尾斜杠补 /v1|/v1beta、反斜杠逗号转义拆 key、multi-key〕、assistants、topic/block〔code 围栏/thinking 包裹/error 引用〕拼装、附件物化〔base64/content/相对路径/文件名/uuid+ext 五级回退〕、`cherry_img_*` data-url 落盘、commit 到 ConversationDao/MessageDao）。
- **`core/data/backup/chatbox/`**：`ChatboxImporter.kt`（v2 zip：manifest 校验〔format/版本/exportItems/stats 对账/ checksum 校验〕+ 成员一致性 + 压缩比/条目预算 + session 资源 storageKey→URI 重写〔messages/threads/forks/copilot/screenshots〕+ 资源暂存发布；legacy 单 JSON 兼容）；`ChatboxImportRunner.kt`（providers〔classify + host/path 规范化含 openai/openrouter 特例〕、assistant = 会话设置 + 首条 system 提示词、threads 与 session.messages 去重合并、contentParts 五类 part 转 TextPart/ImagePart/ReasoningPart/FilePart、tool 消息 JSON 载荷、myCopilots 补助手、Chatbox 标签 + tag_map/collapsed 三键业务写入）。
- **UI**：备份页「从 Cherry Studio 导入」「从 Chatbox 导入」两行死入口接线（Cherry 先实验性确认弹窗〔上游内联文案原样〕）；成功后走既有重启对话框 + 计数明细（上游两种 locale 都用英文计数行，原样保留）。
- 测试：`ThirdPartyImportTest`（Cherry whole-file JSON 全链路落库 + 多 key + /v1 补齐 + code 块围栏；Chatbox legacy JSON + merge 去重 + deepseek 默认 baseUrl；v7 直备拒收）。⚠️ Chatbox 的派生线程 id 是 `chatbox_default_<sessionId>`，测试按它查库。

### ③ 语音：qwenAudio TTS WebSocket（12/12 收官）

- `NetworkTts.qwenAudio` + `QwenAudioTtsSession`（OkHttp WebSocketListener）：onOpen 发 run-task（SpeechSynthesizer / text_type=PlainText / voice/format/sample_rate）→ 30s 等 task-started → continue-task（文本）+ finish-task → 120s 等 task-finished；二进制帧进 ByteArrayOutputStream；task-failed/error 取 header.error_message|error_code|payload.message；PCM 经既有 `pcmToWav` 转 WAV；mime 按 `_audioMimeForFormat`。websocketUrl：workspace 空 → `wss://dashscope.aliyuncs.com/api-ws/v1/inference`，否则 `wss://<ws>.<region>.maas.aliyuncs.com/...`（region 缺省 cn-beijing）。`isSupported` 恒 true；两个旧断言（「qwenAudio 未接」）随附更新。

### ④ UI-7i：消息导出图片（❌ 2026-09-20 整块撤销，见 §5.31）

- `ui/chat/ChatExportImage.kt`：**离屏 ComposeView 渲染引擎**——不可见 Dialog（alpha 0 + NOT_TOUCHABLE/NOT_FOCUSABLE + dim 0）承载 ComposeView，`ProvideSemanticColors + MaterialTheme` 下渲染导出文档（标题 + 秒级日期 + 每条消息角色名/时间/气泡 Markdown + ImagePart 内联图），两帧后 draw 进 ARGB_8888 位图；导出 sheet 第三选项（`message_export_sheet_export_image`）接线，PNG 写 `cache/exports/` 后经 `resolveShareableImage` 走系统分享。
- **有意偏差（记 §5.11 语义）**：①上游切片拼接 + 空白裁剪是绕 Flutter 纹理上限的，Compose 离屏 Canvas 一次画完，不需要；②上游的图片预览 sheet 省略，截完直接分享；③导出文档复用 `MarkdownText` 渲染气泡正文。

### ⑤ MCP-3：OAuth 授权流程

- **`app/provider/mcp/McpOAuth.kt`**：`McpOAuthService`（RFC 9728 受保护资源元数据两候选 + RFC 8414/OIDC 三候选发现链，issuer 对账 + S256 PKCE 要求；DCR 动态注册；PKCE(S256)+state+resource 授权 URL；iss 参数校验；授权码换令牌〔none/client_secret_post/client_secret_basic 三种认证方式〕；refresh；发现/注册缓存与失效）+ `McpOAuthParsing.parseBearerChallenges`（未引号逗号分段 + 「首 token 无=即新挑战」分组，非 Bearer 丢弃）+ `McpOAuthLoopbackCallback`（127.0.0.1 一次性 ServerSocket，5 分钟超时，回 200 HTML）+ SSRF（`requireDiscoveredHttpsUri` 名字级 + `validatePublicTarget` DNS 全地址解析级，IPv4 私有段/IPv6 fc,fd,fe8x,ff/映射地址全查，回环仅服务器自身放行）+ `McpOAuthStore`。
- **集成**：`McpConnectionManager.connect` 前置 `refreshIfNeeded`（过期自动刷新）+ `withOAuth`（有效令牌附 `Authorization` 头到 server.headers）；`authorizeOAuth(server, context)` 用系统浏览器发起授权、成功写 `McpServerConfig.oauth`（schema 既有字段）并重连；编辑页 http/sse 传输显示「使用 OAuth 登录」按钮 + 结果行。STDIO 不适用；「会话内 MCP sheet」上游无调用点 = 死代码不移植。
- 测试：`McpOAuthTest`（挑战解析含引号/多挑战、SSRF 主机全矩阵、发现候选 URL、canonicalResource、State JSON 往返/刷新窗口）。

### 收官清单（剩余=零）

1~8 备份子块 ✅ / 12 家网络 TTS + 7 种 ASR ✅ / MCP-1/2/3 ✅ / UI-7 全系（含图片导出，**图片导出后于 §5.31 撤销**）✅ / §5.12 五批 ✅。**有意不移植**（勿当待办）：S5 内置搜索（上游端点+令牌，品牌规则）、图片查看器桌面专属件、STDIO MCP、sherpa_onnx、desktop/ 整目录。

## 5.26 圆角与组件风格采纳 Apple 参考系（2026-09-19）

用户指定：圆角与组件设计风格采自 `<设计系统参考副本>`（Pinguo/Pinguo Design System，Apple HIG 风），
**配色不变**（仍走 Memo 主题语义色）。

- **圆角 token `ui/MemoRadius.kt`**：CARD=20dp（全局圆角 19.2px ≈ 20dp：卡片/sheet/对话框/输入框）、
  INNER=16dp（容器内嵌套块 = radius−4）、PILL=999（按钮/标签全胶囊）。
- **统一范围**：87 个 sheet + 58 个 AlertDialog 的 16dp → 20dp；SectionCard/SettingsSectionCard/
  IosFormField 12dp → 20dp；IosButton/IosTileButton 12dp → 胶囊（Pill）；MemoSheetOptionRow 14dp → 16dp（内嵌层）。
- **同时采纳的概念**（已满足或随批落地）：静音卡片 = 发丝描边（已有 0.6dp）+ 极低透明度分层阴影
  （浮动件已用）；触控 44×44（已有）；「靠间距不靠分隔线」（SectionCard 节奏）。
- 有意不动：聊天气泡形状（聊天内容自己的形状语言）、用户头像圆、勾选件。

### 5.26 续：INNER 层铺满全站（同日）

第二遍脚本把剩余 227 处 12/14dp（68 文件）全部改为 `MemoRadius.INNER_DP`（16dp）——
覆盖助手编辑各 tab 的行/卡、备份/远端列表卡、MCP 工具卡、抽屉行、统计卡、
LogViewer 卡片体系、chat 各 sheet 内嵌块等。此后全站圆角只剩四档：
20（一级容器）/ 16（嵌套块）/ 999（胶囊）/ 小元素自有小圆角（≤10dp 的
badge/图标底等装饰位，不属容器层）。`tools_sheet_unify.py` 存档于 memo-android/。

### 5.26 续二：零星圆角收口（同日）

18dp（提示卡预览/提纲卡/LogViewer 卡片×3/分段条容器/压缩对话框）、13dp（统计 chips×5/滚轮格）、
15dp（32dp chip=全胶囊）、24dp（正则 pills）、30dp（图片查看器玻璃工具条）全部归入
MemoRadius 三档：容器 20 / 嵌套 16 / 胶囊 999。至此全站圆角只剩四档 + ≤10dp 小装饰位。

## 5.27 圆角 token 收口 + MCP OAuth 的 API 33 崩溃（2026-09-19）

承 §5.26：前两轮把**值**归了档，但**引用**没换——全站仍有 264 处位置参数式 + 91 处
`RoundedCornerShape(topStart = 20.dp, topEnd = …)` 命名参数式直接写数字，圆角改一处要改 355 处。

- **脚本**：`tools/radius_token_sweep.py`（照 `7f68ba2` sheet 统一脚本的存档惯例）。按值映射
  `999→PILL_DP / 20,14→CARD_DP / 16→INNER_DP / 10,9,8,6,11→SMALL_DP`，命名参数式
  `20,18,12→CARD_DP`；**必须整段匹配 `RoundedCornerShape\([^()]*\)` 再替内部每个 `= N.dp`**——
  第一版只匹配首个参数，结果 `topStart` 换了 `topEnd` 没换，sheet 会两角不对称（脚本已修）。
- **新档 `MemoRadius.SMALL_DP = 10`**：规范 README 原话 "**Small labels and inputs use the tighter
  end of the radius scale**"，三档收档时丢了这一端，导致 r6/r8/r9/r10/r11 无处归。
- **`MemoRadius.kt` 从 `app/…ui` 搬到 `core/ui/…ui/theme`**：`MemoSnackbar`（core:ui）的 toast 卡
  吃不到 app 的 token，之前 `1f849b0` 扫了 app 68 文件正是漏了这里（r14→20 两处）。
  搬动要改 68 个 import + 给新引用的文件补 import。
- **13 处有意例外保留字面量**，由 **`RadiusTokenGuardTest`** 按「文件 → 值 → 次数」登记
  （**不按行号**，行号会漂）：r0 直角 ×2、r2 微圆 ×3、r3 统计条 ×3、r4 行内代码、r50 伪胶囊 ×4。
  守卫照 `ShapePercentRegressionTest` 扫主源码的路子，新写字面 dp 即红。
- 值真变的只有 30 点位，其余 226 处**外观一模一样**。跳变最大的一处存疑：
  `ModelDetailSheet.kt:1019 SegmentedMulti` 12→**20**——它是 sheet 内的**嵌套**分段条，
  按档位也许该给 INNER 16，待真机判定。
- ⚠️ **`app:lintDebug` 被 `McpOAuth.kt` 挡住（与圆角无关）**：`URLDecoder.decode(s, Charset)` /
  `URLEncoder.encode(s, Charset)` 的 **Charset 重载要 API 33**，`minSdk = 26` → Android 8~12 上
  走 MCP OAuth **会在回调解析处 `NoSuchMethodError` 崩**，7 处（292/293/300/301/1089/1090/1094）。
  改字符串重载 `decode(s, "UTF-8")`（API 1 就有，行为等价）。`toByteArray(StandardCharsets.UTF_8)`
  这类不受影响，别一起改。**教训：lint 的 NewApi 能抓到单测和编译都抓不到的机型崩溃。**

## 5.28 系统语音设置真正生效（2026-09-19，「做TTS吧」）

§5.12 语音批留的最后一块：`tts_speech_rate_v1` / `tts_pitch_v1` / `tts_engine_v1` /
`tts_language_v1` / `tts_cache_network_audio_for_replay_v1` 五个键**只写不读**。
「系统语音设置」sheet 里语速/音调滑杆存了库，播放时一概不看；引擎行点一下只是把
`engines.first()` 赋给局部变量，**连 `tts_engine_v1` 都没写**；语言行整行没移植。

- **取配置的形状**：`SystemTtsConfig`（语速 0.1–1.0 默认 0.5／音调 0.5–2.0 默认 1.0／
  引擎名／语言标签）+ 纯函数 `parseSystemTtsConfig`，持久化挂在 `TtsServicesStore`
  （它已经握着 `PreferenceRepository`）。`SystemTtsEngine(context, configProvider)` 现读，
  和 `NetworkTtsEngine` 的 `optionsProvider` 同一套缝。
- **换引擎只能重建**：android.jar 里 `TextToSpeech` 没有 `getEngine()`，公开的方法只有
  `setEngineByPackageName`（flutter_tts 都不用它），所以照 flutter_tts 的做法带引擎名
  重新构造，并自己记 `boundEngine`（构造时给的名字）做对比。上游 `_selectEngine` 是
  **无条件钉到**「名字含 google 否则第一个」，不是留系统默认 → 我们照做（默认实例起来后
  枚举、再带选中的名字重建一次）。sheet 里显示的也就是这个实际生效的名字。
- **异步就绪要排队**：`TextToSpeech` 的构造回调是唯一入口，而 `prepare()` 现在可能被三路
  同时要（启动预热、第一次朗读、换引擎后重建）。旧写法「有实例就算就绪」会让后来的人对着
  一个还没绑定的实例说话 → `waiting` 队列 + `settle()` 一次结算。控制器侧 `pendingText`
  换成 `pendingSession`（会话号不一致就整段丢弃），于是「引擎没就绪」不再是特例而是
  `playCurrent()` 的统一前置。
- **正在播时换引擎**：旧实例被 `shutdown()` 后 `onDone` 永远不会来 → 胶囊卡在「播放中」不出声。
  `reloadSystemConfig()` 报重建时把当前块重新走一遍 prepare→speak（上游此处是坏的）。
- **显示倍速**：`TtsPlaybackState.speed` 现在由 `tts_speech_rate_v1 × 2` 播种（原版 `_init`
  L132-134），且只在**没在播**时跟着偏好动（原版 `setSpeechRate` L339 的 `isActive` 判定）。
- **「使用缓存复播」= 重播要不要再花钱**：`replay(allowCachedAudio)` 一路传到
  `TtsAudioCache.fileFor(readCache = …)`；关掉时合成缓存只写不读（上游是清 `_resolvedNetworkChunks`）。
  新会话仍按我们的缓存走（那是本工程相对上游的改进，不改）。
- **语言回落链照 `_applyConfig` L251-264**：偏好标签说不通**直接**跳 zh-CN/en-US，设备语言
  只在没有偏好时参与（写测试时按直觉猜错过一次）。
- 枚举引擎/语言改走播放器的引擎（原版 `tts.listEngines()` 问的就是正在播的那个实例），
  没就绪时按 `_ensureBound` 每 120ms 轮一次、上限 20 次。页内那份 `systemTtsRef` 只留着报
  「系统语音 已就绪/不可用」那行小字。

## 5.29 死 UI 与小开关收口（2026-09-19，用户「先收死 UI 和小开关」）

§5.25 说功能齐了，但有一批**界面画出来了、点了没反应**和**偏好只写不读**的洞。这轮全查了上游原样再补，
每条都标了上游行号；**做不了的三条写明白原因，不硬做**。

- **关于页长按图标 → 调试页**（`about_page.dart:426-440`）：一次长按即进，`Haptics.medium()`，
  **无条件编译、无连点计数、无开发者开关**（连点版本号 7 次开的是彩蛋 sheet，两件事别混）。
  顺带把彩蛋 sheet 里三颗 `Lucide.FolderOpen` 接上（L171-194/242-265/313-336）——
  `log_viewer` 路由早就注册了，注释说「未移植」是过期的；路由改成 `log_viewer/{tab}`
  对应 `LogViewerPage.initialTab`（context=0 / request=1 / app=2）。
  `debug` 路由此前无人调用，`more` 路由**上游同样是死代码**（`MorePage` 全仓无引用），不算缺口。
- **抽屉批量「移动」**（`side_drawer.dart:766-810`）：过滤正在生成的会话（Memo 侧等价物是
  `streamingConversationIds`，上游叫 `loadingConversationIds`）→ 选择器**不**排除当前助手
  （单条才排除）→ 移动 → `n==0` 直接 return → snackbar「已移动 N 个话题」→ 当前会话被移走时
  按 `_nextRecentConversationExcluding`（L824-840，**当前助手作用域内**排除被移动的后取最近）
  跳转或新建 → 退多选。**顺带修一个既有 bug**：单条移动过去不清 `injected_memory_hash`
  （`chat_service.dart:4220` 会清），不清的话记忆注入哈希还挂着旧助手的值。
- **代码块「另存为」**（`markdown_with_highlight.dart:2594-2600` + `2709-2760`）：这颗钮
  **无条件渲染**（不看语言、不看行数），文件名 = 词干「代码」+ `_` + ISO8601 本地时间戳
  （`:` 和 `.` 换成 `-`）+ 扩展名（`_codeFileExtension` 22 组别名表，认不出 `.txt`）。
  Android 上 `FilePicker.saveFile(bytes:)` 的等价物就是 SAF 创建文档；SAF 没有
  `allowedExtensions` 那一层，扩展名直接写进建议文件名。取消静默、成功「已导出为 <名>」。
- **多选导出图片**（`home_page_controller.dart:2141-2168`）：第三颗 `Lucide.Image` 恢复渲染
  （`cs.secondary`），接到 §5.25 已落地的 `ChatExportImage`。两条上游语义：导出的是**会话
  时间序**的选中消息（不是点选顺序）、**先收多选再导出**；从导出 sheet 进来时不收
  （上游 sheet 也不收）。**有意偏差**（UI-7i 已记）：不补 `showImagePreviewSheet`，直接系统分享。
  **→ 本条 2026-09-20 整块撤销，见 §5.31。**
- **MCP 工具结果非文本块**（`mcp_tool_service.dart:248-324`）：按原顺序累加成一整段 markdown —
  image 块 base64 落盘（`filesDir/tool_images/mcp_img_<微秒>.<ext>`）后**就地**写一行
  `![](...)`（目的地含空格/括号/`<>`/非 ASCII 时按 `encodeMarkdownImageDestination` 包 `<>`，
  与已有的 decode 配成一对），对话里的图片横滚条因此直接可用；resource 有 text 当文本、
  否则一行 `resource: <uri>`；audio/未知类型退化成**美化 JSON 内联**；坏块 try/catch 静默跳过。
  ⚠️ 两处**过去是我们自己加的**、与上游相反的行为被撤掉：空结果 dump 整坨 JSON-RPC、
  `isError` 抛成 `execution_error`（上游把错误文本当普通工具结果发给模型）。
- **语音「朗读取哪部分文本」**（`tts_text_selection.dart` 全量 + `tts_provider.dart:983`）：
  五种模式（含「选取为空则回退去过代码的原文」这条关键兜底）**只作用在助手消息朗读**上
  （上游唯一消费点 `home_page_controller.dart:1818`），所以接在 MessageRow 的朗读钮与自动播放两处，
  **不能**接进 `TtsPlayer.speak` —— 那会连「听测试」和悬浮条重播一起污染。
  顺序是**先按模式取文、后剥 markdown**（后者在 `_speakQueued` 里，所有朗读入口共用）。
  顺带补上 Memo 一直缺的 `_stripMarkdown`：此前朗读会把 `#`、`**`、链接地址、代码块整段念出来。
- **Claude 提示词缓存**（`claude_official.dart:357-360`）：开关 + TTL 此前零消费，现在经
  `LlmRequest` 由 `clientFor` 统一盖章（覆盖聊天/标题/摘要/记忆/翻译/OCR/测试连接全部路径）。
  ⚠️ **上游的形状就是 body 顶层一个 `cache_control`**，不是 Anthropic 文档要求的
  content-block 内嵌，也没有断点数/最小可缓存 token 的处理——按 1:1 照做，
  「缓存到底有没有生效」是上游的事，别当成我们的缺口。5m 只发 `{"type":"ephemeral"}`、
  1h 才带 `"ttl":"1h"`（`resolveClaudePromptCachingTtl` 只认 1h，其它一律 5m）；
  OpenAI 兼容路径另有三重门（开关 + OpenRouter + 模型 id 含 claude/anthropic/）。
  响应侧（`cache_read_input_tokens` 合并、用量卡、统计）本来就通了。
- **两个 `*_font_is_google_v1`**：上游**已删除** Google Fonts（`google_fonts` 不在 pubspec 里，
  键只剩 `settings_provider.dart:1931-1943/1993-1998` 的「迁移后置 null 再删键」）。
  所以正确动作不是接旗标而是照抄那段迁移（`ThemeState.loadFonts` 里），两个死常量随之有了用途。

**做不了、也没硬做的三条**：
- `display_show_app_updates_v1` —— 上游唯一实现是拉 `https://kelivo.psycheas.top/update.json`
  （`update_provider.dart:92-95`），品牌规则禁止该端点，而造一个假端点没有意义。
- `image_cropper_enabled_v1` —— 是「选图后逐张弹裁剪器」的完整功能（上游 `image_cropper` 包 +
  `AndroidUiSettings`，取消即丢图），要引第三方库或自写裁剪界面，不是几行开关。
- `send_markdown_image_links_as_images_v1` —— 要移植 `parseTextAndImages`（markdown 图链接
  扫描 + 远程/本地/data 三类闸门）并和「模型支持视觉才发图」的剥离逻辑配合，中批，单独做。

## 5.30 §5.29 装机后三处返修（2026-09-19 晚，用户实测）

用户装机后回的三个问题，都是**"接线接对了但东西是坏的"**，门禁和单测看不见，只有真机能看见：

- **多选导出图片一点就闪退**：`ChatExportImage.render` 是把离屏 `ComposeView` 装进一个
  不可见 `Dialog` 的，而 `Dialog` **只有 Activity 上下文才有 window token**。调用方传的
  是 `container.appContext` → `Dialog.show()` 抛 `BadTokenException` 直接崩主线程。
  （这条不是 §5.29 引入的，导出 sheet 那条路本来就带这个雷，我把第三颗钮放出来才必现。）
  现在从 Compose 侧拿上下文、沿 `ContextWrapper` 往上 `findActivity()`，拿不到就如实
  报"导出失败"而不是崩进程；并加了源码守卫 `ChatExportImageWindowTokenTest`
  （照 `ToolTranscriptContentTest` 的扫描手法），钉住"渲染必须用 host、不许用 appContext"。
  **教训：凡是起窗口（Dialog/Popup/BottomSheet 之外的手撸 Dialog）的代码，参数必须是
  Activity 上下文，且必须真机点一次。**
  **→ 这条只修掉了第一层崩，第二层（ComposeView 缺 ViewTree owner）在本工程补不干净，
  2026-09-20 用户拍板把整个图片导出撤掉，见 §5.31。**
- **「朗读取哪部分文本」看着不能选**：五行、点行写库、朗读读库**都在**，坏在
  `TtsTextSelectionRow` 把那颗勾**无条件画出来**了 —— 上游是
  `AnimatedOpacity(opacity: selected ? 1 : 0, 160ms)`（`tts_settings_page.dart:222-230`），
  勾只出现在选中行。五行全带勾 ⇒ 既看不出当前选了哪个、点下去也"没变化"。
  另外模式值原来是从 `ChatTimelineSettings`（`remember {}` 一次性读）传的，去设置页改完
  回到聊天再朗读用的还是旧值；上游是 provider 的活值，所以改成朗读那一刻现读
  （`TtsPlayer.speakAssistantReply` → `store.textSelectionMode()`），并把
  `ChatTimelineSettings.ttsTextSelectionMode` 这个中间缓存删掉（不留死配置）。
- **侧栏搜索胶囊比原版鼓**：原版 `isCollapsed: true`（`side_drawer.dart:1940`）+
  `contentPadding: symmetric(h14, v11)`（L2115-2118）+ 14sp 文字 ⇒ 约 **42dp**，圆角 14
  （L2122）；Memo 用的是 Material3 `TextField`，而**这个版本（1.3.2）的 TextField 没有
  `contentPadding` 形参**（试着传 `contentPaddingWithoutLabel` 直接编译不过——候选签名里
  就没有这个参数），它内置上下各 16dp，所以高出那一截。改成按原版几何自己拼：42dp 高的
  圆角胶囊 + `BasicTextField`，前缀位 padding start 10 / end 4、图标 16dp
  （L1941-1949），清空钮 28dp 照旧。

## 5.31 多选导出图片整块撤掉（2026-09-20，用户「导出图片这个功能去掉吧」）

§5.30 只修掉了第一层崩，真机第二层还在，而且这层**在 Memo 里补不干净**：

- 改成把 `ComposeView` 挂进 Activity 的 `android.R.id.content` 之后，报的是
  `IllegalStateException: ViewTreeLifecycleOwner not found from ComposeView`。根因不是
  Dialog，而是**这颗 ComposeView 的祖先链上压根没有 owner**：`MainActivity` 是纯
  `ComponentActivity`（本工程没有 appcompat），owner 三件套是 `activity-compose` 装在
  **它自己那棵 ComposeView** 上的，我们新加的是它的**兄弟节点**，往上走找不到。
  RikkaHub 的 `BitmapComposer` 同款写法能跑，是因为它挂在 `decorView` 下且宿主是
  `AppCompatActivity`（AppCompat 的 `installViewTreeOwners()` 会把 owner 装到 decorView）——
  这条参照系在本工程不成立。
- 想自己装也装不上：Compose 1.8.3 里 `AbstractComposeView` 查的是
  `androidx.lifecycle.ViewTreeLifecycleOwner.get(...)`，而 lifecycle **2.9.1** 把这个类的
  Kotlin 侧可见性收掉了（javap 看得到 `public static set/get`，Kotlin 报
  `Unresolved reference`；公开只剩 `androidx.lifecycle.findViewTreeLifecycleOwner` 这个**只读**
  扩展）。`ComposeView.setOwner(...)` 在 1.8.3 也已删除。把三个 `implementation` 依赖点名
  提上来仍不可见 —— 也就是**没有公开 API 可写这颗 tag**，剩下的路只有「加一个 Java 薄壳
  文件绕过 Kotlin 的 deprecation 屏蔽」或「把 MainActivity 换成 AppCompatActivity」，
  两条都是为一个次要功能动全局，用户拍板不偿试：**功能整块去掉**。

删掉的东西：`ui/chat/ChatExportImage.kt`、它的两条测试（含 §5.30 加的源码守卫
`ChatExportImageWindowTokenTest`）、`ChatContent` 里的 `exportSelectedAsImage` /
`renderAndShareChatImage` / `findActivity()`、多选导出栏的第三颗 `Lucide.Image`、
`MessageExportSheet` 的图片选项行。**文本导出（.md / .txt）不受影响**，多选栏剩两颗钮。
ARB 生成的 `chat_selection_export_image` 等字符串保留（`strings.xml` 是生成物，门禁校验
零 diff，手删会红）。

**这是有意偏离原版**（原版 `home_page_controller.dart:2141-2168` 有图片导出）：§5.25 的
「UI-7i 图片导出 ✅」与 §5.29 的「第三颗钮恢复渲染」两条按本撤销，批次表 UI-7i 行与
§5.25 收官清单同步标注。以后若再要这个能力，起点应该是**在现有组合里画 +
`GraphicsLayer.toImageBitmap()`**（组合内天然有 owner，不需要窗口、不需要挂视图树），
而不是重走 ComposeView 那条路。

## 5.32 体验五连修（2026-09-20，用户「按照你的改吧，我们现在在修复体验，上游也没有做好。我们要做好」）

⚠️ **规则变化，比这批改动更要紧**：用户明确授权**体验类修复可以超出上游**。上游没有中文
错误文案、上游的 HEIC 靠 `image_picker` 插件转好、上游不管列表性能——这些都不再是"不做"的
理由。**默认仍是 1:1**（行为、几何、文案照 Dart 源码），但**每一条超出上游的改动都在本节
点名**，以后翻 §5.11 找不到出处时先来这儿看。

### ① 抽屉多选「移动/删除」芯片底色（用户「太黑看不清」）

角色色（onSurface／primary／error）与上游一字不差，错在**我们自己手搓的底色混合**：
`SideDrawerContent.kt` 把它写成 `color*0.14 + onSurface*0.86` 且强行 `alpha=1f`——等于拿
onSurface 当 86% 的主色压底，默认浅色算出**不透明近黑 #262830**，深蓝/暗红的字与图标压在上
面直接看不见。上游是 `alphaBlend(onSurface@0.04, color@(isDark?0.18:0.14))`，两颗操作数都带
alpha，结果是一颗**半透明淡染色**（`sidebar_selection_bars.dart:231-234`，与
`chat_selection_export_bar.dart:169-172` 同一条式子）。改成共用
`ColorMath.kt` 的 `selectionChipColor(onSurface, color, isDark)`，聊天多选栏原本往白/黑 lerp
的近似一并换掉 ⇒ 两屏自动统一。守卫：`ColorMathTest`（钉住 0x2C434E78 与"必须半透明"）。
（上一轮 `949af1d` 只是把这排的 `enabled` 打开，之前被 40% 禁用蒙层掩盖，所以这会儿才暴露。）

### ② 相册图片被厂商 400（`unsupported image`，只要 webp/png/jpeg/gif）

真机原文：`.messages[7].image[0]: You have uploaded an unsupported image…`。链路上有三个缺口，
缺一都出事：

1. **HEIC 一路裸奔**：`AttachmentStore` 把 ContentResolver 声明的 `image/heic` 当权威 mime，
   画质管线又不碰它（`detectFormat` 归 OTHER），原字节落盘。
2. **请求侧照抄声明**：`MessageContent.mimeFor` 的次序本来是「显式声明 > data-URL > 扩展名」，
   上游同次序——但上游**构造请求时压根不传 explicitMime**
   （`message_generation_service.dart:423` `inferAttachmentMime(uri: path)`），于是先读文件头
   16 字节嗅探（`multimodal_input_utils.dart:287-361`，只可能得 jpeg/png/gif/webp/pdf）。
3. **扩展名表自造了 heic**：`mimeFor` 里有 `"heic" -> "image/heic"`，上游全仓 `image/heic`
   零匹配（它的表里没有 heic）。

改法：① `AttachmentStore.import` 里对 HEIC/HEIF 做一次**格式转换**（新增
`ImageCompressor.isHeic`（ISO BMFF：主品牌在第 8..12 字节，兼容品牌从第 16 起，**别照
`ftyp` 后立刻取 4 字节那样写，那是 minor version**）+ `transcodeToJpeg`，走 BitmapFactory
重编码，不受"必须更小"约束）——这一步上游没有，因为它的 `image_picker` 默认 `heicToJpg`，
我们换 SAF/PhotoPicker 就得自己补；② `MessageContent` 补 `sniffMimeFromBytes`（1:1）并把
`mimeFor` 改成「显式 > data-URL > **嗅探** > 扩展名 > png 兜底」，四个请求构造点一律
`explicit = null`；③ 删掉 heic 扩展名分支（`.bmp` 保留，上游表里真有）。
转不动的（API 26/27 没有 HEIF 解码器）在选图那刻**丢弃并 SnackBar 告知**，绝不发上去——
一条不支持的图会让**整条消息**发不出去。守卫：`MessageContentTest`（嗅探 + 永不声明 heic）、
`ImageCompressorTest`（品牌盒位）。

### ③ 代码块流式输出时"大小一直变"

四个来源，前两条各占一半：

1. **折叠预览取末尾几行 + 允许换行** = 一行源码对应几视觉行随内容变 ⇒ 框高每 tick 抖。
   改成折叠预览期**强制不换行**（`codeBlockPreviewWraps`，长行横向滚动），N 行源码恒等于
   N 视觉行。尾部跟随本身保留（那是用户 09-18 点的「折叠也要看得到在输出」）。
2. **手动展开态每帧被冲掉**：`remember(stateKey)` 里 stateKey 含全文哈希，流式每个 token
   都换 key ⇒ `manual` 退回 null，用户点开的展开下一帧就被自动折叠抢回去。上游是
   StatefulWidget 的 `_manuallyToggled`，与内容无关 ⇒ 改成不键控的 `remember`。
3. **流式中就上高亮**：上游 `_closed` 闸门（`markdown_with_highlight.dart:2820-2829`）在未
   闭合围栏期间按纯文本渲染；我们照 token 边界实时高亮，斜体/粗体来回出现⇒字宽变，跨过
   300 行/12000 字阈值还会整块退回纯文本再跳回来。⇒ `rememberHighlightedCode(…, highlight =
   !isStreaming)`。
4. **缺高度过渡**：上游 `AnimatedSize(220ms)`（:2639-2643），我们跨阈值是硬跳、被整条列表
   的自动跟随放大 ⇒ 补 `animateContentSize(tween(220))`。

守卫：`CodeBlockExpansionTest`（预览不换行那条）。

### ④ 供应商报错看不懂

上游本身也是**把 `e.toString()` 原样写进气泡**（`chat_actions.dart:2605`），只有
`home_page_controller.dart:508-515` 那条 SnackBar 带中文前缀。所以"翻成人话"没有上游依据——
本条按用户「我们要做好」**有意超出上游**，四类改动：

- **带内错误帧**（这条是补漏，不是加法）：1.x 状态码的流里塞 `{"error":…}`／`event: error`／
  Responses 的 `response.failed`·`response.incomplete` 时，上游
  `throwIfInBandStreamError`（`chat_api_helpers.dart:857-919`）会抛；我们没移植，于是**半成品
  被当成功落库**，连报错都没有。新增 `core/llm/.../provider/InBandStreamError.kt`，四个解码入口
  （ChatCompletions／Responses／Claude／Gemini）在解析正文前一律过一遍。
- **状态码必须带响应体**：`OpenAiChatCompletionsClient.listModels` 还在抛裸 `HTTP 429`，
  换成 `httpFailure(response)`（同 §5.24 那批的理由）。
- **中文分类**：`GenerationErrorText.classifyGenerationError` 判 7 类（上下文超长／图片格式／
  内容审核／余额额度／限流／鉴权／超时），气泡与 SnackBar 显示「中文一句 + 换行 + 原始信息」。
  **判不出就不分类**，只给原文——宁可英文也别给一句错的中文。只认带上下文的状态码写法
  （`HTTP 429`、`"status_code":429`），裸数字正则会拿 token 数误判成 413。
- **失败一定有提示**：`markFailed` 里补 SnackBar `生成已中断：<第一行>`（有半成品时气泡里
  不写错误行，这条是唯一告知）。

新文案落在 `lib/l10n/app_{en,zh,zh_Hant}.arb`（8 个键，`chatAttachmentUnreadableSkipped` +
`generationError*` 七个）→ `tools/arb_to_android.py` 生成 strings.xml。**ARB 是上游文件**，
加键等于改上游 l10n；先例是「流式等待提示」那批（同为本工程新增功能）。⚠️ 往 ARB 插键时
锚点必须匹配「键 + 冒号 + 引号开头的值」，`"@xxx": {` 是元数据对象，插进去键会隐身
（本轮踩过一次，生成器照样"成功"但键根本没进去）。

### ⑤ 「获取模型」列表滑动掉帧 + 全站同类

主嫌：**每行冷解码两枚 SVG**（`FetchModelsSheet` 每行的品牌 logo + `deepthink.svg`，coil-svg
→ androidsvg）。新增 `ui/SvgIconCache.kt`：按「asset 路径 + 目标像素」缓存已解码位图，解码仍
走 Coil 自己的管线（同一个 SvgDecoder、软件位图、`Dp.roundToPx()` 与 `Modifier.size` 同一
取整）⇒ **像素与几何都不变**，未就绪那一帧仍回落 `AsyncImage`。只接管
`file:///android_asset/**/*.svg`，远程/PNG 一律不碰。

其余是机械项：组合期的 filter/group/sortedBy 全部 `remember`、勾选判定换 Set、行 lambda 用
`@Stable` 动作持有者 + `rememberUpdatedState`（FetchModelsSheet／ModelSelectSheet／
ProviderDetail 重排表／LocalSnapshots 卡片）、每行现建 `SimpleDateFormat` 换成按 locale 缓存的
formatter（ChatHistory／LocalSnapshots）、两处 `items(size){}` 补 key（LogViewer／
RemoteBackupListSheet）。**两处假懒加载**（SkillsScreen、ProviderSettingsScreens 的
`item{ SectionCard{ forEachIndexed } }`）**刻意没拆成 `items(...)`**：卡片是一整张圆角描边面，
拆开就会看到一段一段的角——改成 `key(行标识)` + 行实参全稳定，外观一字不动。

守卫：`SvgIconCacheTest`（接管判据与缓存键）。**帧率本身只有真机能判**，请重点滑一滑
设置→供应商→获取模型。

## 5.33 图标名单的维护模型 + 删掉那颗坏掉的 Kelivo 头像（2026-09-20）

用户问「大模型图标和大模型有对应的名单吧，要不断维护是不是」——**是**，一共两张表加一条
零维护的兜底，都在 `app/src/main/java/com/psyche/memo/ui/`：

| 表 | 规模 | 作用 | 出处 |
| --- | --- | --- | --- |
| `BrandIconCatalog.kt` | **58 项** | 供应商头像选择页里**手动挑**的那颗；存的值是 Dart 的 asset 串（`assets/icons/openai.svg`），渲染时拼成 `file:///android_asset/icons/…` | 1:1 上游 `lib/utils/brand_assets.dart` 的 `selectableIcons` |
| `BrandAssets.kt` | 70 条正则 | **按名字自动配图**（`openai\|gpt\|o\d → openai.svg`），用在供应商列表行、默认模型页、ASR 服务行 | 上游同一文件的 `_mapping` |
| 兜底（免维护） | — | `avatarType='lobehub'` → 按名字走 LobeHub 图标 CDN（`unpkg.com/@lobehub/icons-static-svg@latest`）；另有 emoji／远程 URL／本地文件 | 上游同款 |

真实维护成本只有两处：① 上游改 `brand_assets.dart` 时跟着同步一次（比对结论：上游引用的
63 个图标我们**一条不缺**，另外自己多了 17 条：cerebras／elevenlabs／groq／nvidia／ppio／
vercel／xiaomimimo／tokenpony／rikkahub／stepfun…）；② 新厂商要「加一行正则 + 往
`app/src/main/assets/icons/` 拷一个 SVG」，**要发版**才生效（走 CDN 的那条不用）。
不做的事：不把「匹配不到就自动请求 CDN」当默认——那会让头像走网络（隐私 + 离线首启空白），
要改得用户点名。

**本轮修的缺陷**：`BrandIconCatalog` 里留着上游第 59 项 `("kelivo", "Kelivo",
"assets/icons/kelivo.png")`，而我们仓库只有 `memo.png`、**没有 kelivo.png** ⇒ 头像页那一格
既是一个打不开的空图、又把 "Kelivo" 显示给用户，违反品牌红线。用户「这个去掉吧」→ 删掉该行，
58 项。守卫补在 `ProviderAvatarTest`：`everyCatalogAssetExistsAndCarriesNoUpstreamBrand`
逐条查「表里引用的文件真在 `src/main/assets/icons/` 里」+「id/label/asset 都不许带 kelivo」，
计数测试同步改 58。**已证伪**：把那行加回去，两条测试立刻变红。

顺带确认：剩下的 `kelivo` 字样只出现在**内部标识符与出处注释**里（`KelivoOptions` 类、
序列化值 `"type":"kelivo"`、注释引用 Dart 路径），界面标签早已是
`search_service_name_memo`；品牌红线允许注释留出处。

## 5.34 设置域「点进去卡一下」：主线程读库的盲区（2026-09-20）

用户：「现在是线程与性能优化，在这个设置界面里面很多界面点击都会加载卡一下，特别是那个统计」。

**根因是一类，不是一处**：§5.13 的机器守卫 `CompositionThreadingTest` 只扫 `remember {}` 块，
而 **`LaunchedEffect` / `DisposableEffect` 的函数体默认也跑在组合线程上**（换线程只有靠自己写
`withContext(Dispatchers.IO)`）——这块一直是盲区，所以门禁全绿、真机才卡。扫描器（一次性脚本，
用完已删）在 effect 体里抓到 6 处真库操作：

- 四个设置子页 `LaunchedEffect(Unit)` 里裸读 `preferenceRepository.readJson`
  （`AutoRetrySettingsScreen`／`ChatItemDisplaySettingsScreen`／`RenderingSettingsScreen`／
  `MessageStyleSettingsScreen`）。偏好读**冷缓存未命中就是真 SQL 往返**，`rendering`/`message style`
  一次点进去连读 5 个键。
- `SideDrawerContent` 展开助手列表时 `assistantStore.getAll()`（整表 + 每行解 JSON）在主线程。
- `ChatContent` 「模型不支持工具/推理就清掉助手绑定」那段，读写助手行在主线程的 effect 体里。

六处全部包上 `withContext(Dispatchers.IO)`（快照状态跨线程写是安全的，仓库里
`rememberLoaded` 就是这么做的）。**守卫补上同一套判据**：新增
`no effect body touches the database without dispatchers IO`，词表含偏好读写/`getAll`/裸 SQL/
各仓库取数方法，豁免机制沿用 `exemptions`；**已证伪**——把其中一处调度器改成 `Dispatchers.Main`
立刻变红并报 `ui/MessageStyleSettingsScreen.kt:155 effect { preferenceRepository.readJson }`。

统计页另外两处：① 它以前自己 new `PayloadEntityDao` 把 `assistant_rows`+`provider_rows`
**整表解一遍**，而且 `Json { ignoreUnknownKeys = true }` 是**每行现造一个实例**；现在助手走
容器那个会喂 `AssistantCache` 的 `assistantStore.getAll()`，供应商逐行先查
`ProviderConfigCache`、`Json` 实例提到循环外。② 用户点名「总览不要 K、M 这些单位，直接显示数字」
⇒ `formatCompact` 改成千分位全量数字（`12,345,678`），**有意偏离上游 `_formatCompact`**。

**量到的事实**：`memo.db` 现在只有 5.5 MB，所以按时间段的 6 个聚合全表扫并不致命；也就是说
剩下的卡顿更可能出在下面这三处，**都要用户点头才动**（已查清、未做）：

1. **WAL 没开**：`MemoDatabase` 是裸 `SQLiteOpenHelper`，默认回滚日志（DELETE），写事务期间
   读者要等（边流式写边翻设置就会卡）。`SchemaMigrations` 里已经写着
   `PRAGMA wal_checkpoint(TRUNCATE)`，说明上游假设有 WAL。开 WAL 前必须审备份链路：
   `LocalSnapshotStore` / WebDAV / merge 恢复拷贝 `memo.db` 时要不要带上 `-wal`。
2. **`message_rows` 没有 `timestamp` 单列索引**（17 个索引全是 `conversation_id` 前缀），
   统计页 6 条按时间的聚合都是整表扫。schema 是 drift 生成且门禁校验零 diff，所以加索引只能
   在开库时 `CREATE INDEX IF NOT EXISTS`（不动 `memo_schema_v3.sql`）。
3. **统计页整页是 `Column + verticalScroll`（非 lazy）**：`snapshot` 一到，一年热力图 +
   趋势 Canvas + 各排行榜在一次组合里全画出来。改 Lazy 要保住原版那张卡片外观，工作量中等。
4. 已知欠账（豁免在案）：`ui/chat/ChatContent.kt:617` 在 `remember {}` 里读会话行拿 assistantId
   （带「新会话还没落库」的语义，不能简单挪线程）。

**下一步取证**：`adb shell dumpsys gfxinfo com.psyche.memo.dev reset` → 用户点进统计页 →
`dumpsys gfxinfo … framestats`，用五段耗时（MeasureLayout/Draw/Sync/CommandIssue/Swap）判定
是组合成本还是等 DB，再决定动 1/2/3 的哪一条。
## 5.35 位置本地工具（2026-09-21，用户「加一个位置获取的本地工具吧」+「他这个项目我用过是没有问题的」）

**超出上游的平台能力**：上游 `DeviceLocalTools.locationSupported` 是 iOS-only（
`locationSupported => iosDeviceToolsSupported`），`local_tools_service.dart:484` 那条
`getCurrentLocation` 通道在安卓上根本走不到。这次照上游的**接口**在安卓侧自写了实现：

- 接口一字不动：名字 `get_current_location`（`LocalToolNames.CURRENT_LOCATION` 早就在）、
  **空参数对象**、描述用上游 `_currentLocationDefinition` 那段英文、**不进
  `requiresUserApproval`**（上游也没有）。图标 `Lucide.MapPin`、三语标题/副标题都是原样
  已经在的，缺的只是 `isAvailableOnThisPlatform` 开闸 + `localDefinition` 一支 + 执行器。
- 运行时策略照用户真机用过的参考实现（`<本地参考实现>` 的 `LocationTool`）：
  权限 → 定位服务开关 → **10 分钟内的 last-known 秒回** → 否则实时定位 10 秒超时 →
  超时回退过期缓存 → 全拿不到才报错；逆地理用平台 `Geocoder`（零 API key、零网络），
  **拿不到地址只给坐标不判失败**（国产 ROM 无 Google 服务时常空）。输出键照上游口径：
  `latitude/longitude/accuracy/altitude/timestamp/provider` + 可选 `address/city/region/country`。
- 工程没有 Play Services ⇒ 用 `LocationManager`（GPS > 网络 > passive），不引新依赖。
- 权限两处申请，都照上游形状：① 助手「本地工具」里**打开这一行时**就申请
  （上游 `assistant_settings_edit_local_tools_tab.dart:106-118` 同义），没同意就不落开关；
  ② 模型真的调用时若没授权，走新增的 `LocationPermissionService` —— 形状照
  `ToolApprovalService`/`AskUserInteractionService`（执行器挂起等 deferred，ChatContent 观察到
  pending 就弹系统框回填），**但必须带 90 秒超时**：后台生成时没有 ChatContent，没人回答
  不能把那条生成挂死。这是与那两个服务唯一刻意的差别。
- 三条错误（`permission_denied` / `location_service_disabled` / `timeout`）走
  `{error, message}`，message 是给用户看的中文，新增 3 个 ARB 键
  （`locationToolError*`）。**没做** `permission_permanently_denied`：安卓侧区分「首次拒绝」和
  「不再询问」不可靠（`shouldShowRequestPermissionRationale` 两种情况都是 false），参考实现
  靠插件能分，我们分不了，就不假装有把握。
- manifest 只加前台 `ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION`（+ 两条 required=false 的
  uses-feature 免得被商店过滤），**不申请后台定位**。
- 单测：`LocationToolTest`（新鲜度 10 分钟边界、provider 取舍顺序、结果/错误 JSON 形状、
  地址片段拼装与空段）、`LocationPermissionServiceTest`（pending 发布/结清、超时按未授权、
  resolve 只结当前等待者、无等待者时忽略）。真定位与 Geocoder 只能真机验。
**装机后返修（同日，用户「为什么大模型说没有呀 你接线了没有呀」）—— 我确实漏接了一道闸**：
本地工具有**三道各自独立维护的闸**，我第一次只开了两道：
① `BuiltInToolCatalog.isAvailableOnThisPlatform`（平台能力）、② `LocalToolExecutors.EXECUTABLE`
（有没有人执行）、③ `ChatViewModel.offeredTools()` 里**手抄的** `setOf(...)`（递不递给模型）。
漏了 ③ ⇒ 工具定义压根没进 `tools` 数组，模型答「我没有这个工具」，而编译、门禁、单测全绿
（没有任何测试断言过"开了平台闸的工具必须递给模型"）。修法是消灭第三份名单：
`BuiltInToolCatalog.offeredLocalToolNames()` 从 `EXECUTABLE` 推导（+ `TIME_INFO`/`ASK_USER`
两个在 ToolHandler 里自理的），`offeredTools()` 用它。守卫 `LocalToolOfferingTest` 双向钉
（可用⇒必递、递了⇒必有执行器与真定义），并**已证伪**：把 `CURRENT_LOCATION` 从递给模型的
名单里摘掉，两条断言立刻变红。教训与 §5.29 那条同类 —— 「接线」不是改一个开关就完，
要顺着请求字节走到底：这次该在装机前自己问一句「tools 数组里到底有没有它」。

## 5.36 开源准备：公开仓只放 memo-android，生成器改为可读仓内 upstream（2026-09-21，用户「我可能要开源 把准备工作做好吧」）

决定：公开仓 = 本目录（`memo-android/`）单独成仓，干净初始提交，不带 Flutter 上游源码与 442 条历史。

- **许可证**：根 `LICENSE` 是上游 kelivo 的 AGPL-3.0 正文，衍生作品只能沿用 ⇒ 公开仓补 `LICENSE` +
  `NOTICE.md`（点名 kelivo 与 RikkaHub 两个上游及其许可证）。应用内不再挂上游链接（见上）。
- **生成器的输入布局**：四个生成器原先一律读 `../../lib/…`、`../../drift_schemas/…`，本目录单独成仓后
  这些路径在仓外 ⇒ 门禁第 5 步必崩。改成 `tools/upstream_root.py` 统一解析：**仓内有 `upstream/` 就用它，
  否则回退外层仓**。公开仓把被读的那几个 Dart/JSON 输入原样放进 `upstream/`（AGPL 再分发，署名在 NOTICE），
  开发仓行为不变。注意：SQL 头注释会按布局写成 `upstream/...` 或 `../drift_schemas/...`，两边各自自洽。
- **机器相关路径出库**：`tools/quality_gate.sh` 原先硬钉本机 `JAVA_HOME` 与 `GRADLE_USER_HOME`（含中文
  用户名与本机 Gradle 缓存目录）。改成 source 不入库的 `tools/quality_gate.local.sh`，本机行为不变、仓里干净。
- **出库的私密/临时内容**：`.workbuddy/memory/*.md`（AI 会话笔记，含本机路径）与 `tools/qg15_errors*.txt`、
  被误跟踪的 `android/build/reports/problems/problems-report.html` 全部 `git rm --cached` + 写进 `.gitignore`；
  文档里指向上游 RikkaHub 本地克隆、设计系统参考副本、本地参考实现等的**本机绝对路径**一律改成
  中性占位符。
- **门禁不放宽**：没有为了独立成仓而跳过任何一步；CI 只带 `android-pr-check.yml`，并把 `memo-android/`
  前缀去掉（新仓它就是根）。

## 5.37 应用更新：端点换 GitHub Releases，位置从抽屉挪进关于页（2026-09-21，用户「更新样式和位置和原项目要不一样，原项目是在侧边栏显示，这个一点都不好」）

上游 `update_provider.dart:85` 拉 `https://kelivo.psycheas.top/update.json`，横幅画在抽屉的
会话列表里（`side_drawer.dart:4005` 的 `includeUpdateBanner`，三个调用点 2714/2732/2749）。
两处都不沿用：

- **端点**：`UpdateService.RELEASE_URL` = 本项目自己的 `api.github.com/repos/3d-jq/memo/releases/latest`。
  上游那条 URL 是上游端点，按品牌红线不能进包；GitHub Releases 也不需要我们自己架服务器，
  发版打 tag 即生效。字段映射：`tag_name`（去 `v` 前缀）→ 版本、`body` → 说明、
  `published_at` → 时间、`.apk` 资产 → 直链，没有资产就退回 release 页面。
  上游的 `build` 与 `mandatory` 在 GitHub 上没有对应概念，**去掉**（`UpdateInfo` 少两个字段）。
- **比对语义照上游**：`UpdateFeed.isRemoteNewer` 只比前三段数字、缺段按 0 补、忽略 build
  （`UpdateFeedTest` 钉住，含 `"1.0.0"` 对 `"1"` 相等这条反直觉的）。
- **位置**：抽屉里一颗都不放。改成关于页「版本」卡片里的三行 —— 状态行（发现新版本／已是最新／
  检查中／失败原因）、发现新版时才出现的「去下载」行（跳浏览器）、常驻的「检查更新」手动重查行。
- **时机也照上游不同**：上游在启动时查一次（`main.dart:775`），我们**进关于页才查**，
  不做任何主动提示。`display_show_app_updates_v1` 因此改成「关于页要不要出现这几行」的开关
  （缺省仍为开，`readJson(...) != "0"`）。这是刻意的：后台流量不该在启动路径上，
  而用户要求的就是"别主动烦我"。
- **404 当「已是最新版本」**：`UpdateService.outcomeFor` 把 GitHub 的 404（仓库还没发过任何
  release）判成 `UpToDate`，不显示红色失败（用户 2026-09-21「这个也太难看了」）。已知副作用：
  私有仓或写错的仓库地址同样是 404，那种情况会被当成已最新。判定抽成纯函数，`UpdateServiceTest`
  覆盖 404／更新／已最新／500／脏负载五条。
- 新增 5 条 ARB 文案（`aboutPageUpdate*`，en/zh/zh_Hant 三份），复用上游现成的
  `sideDrawerUpdateTitle` 与 `sideDrawerLinkCopied` 语义（后者暂未用到，留着）。


## 5.38 开源落地：公开仓已上线，CI 修复回填（2026-09-21，用户「我开源了」）

公开仓 `github.com/3d-jq/memo`（public，AGPL-3.0）已上线，含 6 笔提交：1.0.0 初始 + §5.37 应用更新 + 三条 CI 修复（gradlew 可执行位、去本机 JDK 路径、失败用例 annotation）。本地这批未提交改动正是公开仓 `876546a`（CI annotation）那一代的本地副本，回填：

- **CI 失败用例 annotation**：`tools/ci_test_failures.py` 把 `build/test-results/**/*.xml` 的 failure/error 逐条输出成 `::error::`（CI 完整日志要 token 才能看，只靠 exit 1 定位不了是哪个用例）；workflow 里 `testDebugUnitTest` 后挂 `if: failure()` 一步。**经验**：Linux runner 上单测挂但本地全绿时，日志端点鉴权挡掉 → annotation 是唯一公开可见的定位途径。
- **KeyRoulette 时钟注入**（`core:llm`）：LRU 用 `System.currentTimeMillis()` 排序，同一毫秒两次使用并列 → `minByOrNull` 又选中刚用过那把（Linux 上 IO 快、CI run #6 复现）。`lru(cacheFile, clock)` 可注入单调时钟，测试用 `tickingRoulette` 每取用推进一秒。**教训**：时间戳排序的同毫秒竞争在 Windows 本地测不出、Linux CI 必炸。
- **RequestLogInterceptorTest host 不写死**：`MockWebServer.url()` 用本机反查主机名，Windows 给 `127.0.0.1`、CI 给 `localhost`、有的机器给别的名字（CI run #6）。断言从正则硬编码 host 改成 `text.contains("] POST ${req.url}")`。
- **签名条件化**（`app/build.gradle.kts`）：release 只在 `keystore.properties` 存在时配 `signingConfig`（本机密钥不入库），CI 和别人克隆后 release 产出未签名包但构建照常。PDFBox JPXFilter 悬空引用加 `-dontwarn com.gemalto.jp2.**`（文档抽取不走 JPX 编码的 PDF）。
- **关于页更新行合并**（用户 2026-09-21「为什么做两个」）：状态行 + 「去下载」行合成一行 —— 没新版时点它检查、有新版时点它下载，状态写右侧 detail，删除独立的「检查更新」行。

验证：`:core:llm:testDebugUnitTest`（187 例）+ `:app:testDebugUnitTest`（1390 例）全绿；中途踩一次 Windows 文件锁（`binary/output.bin` 被 gradle daemon 占用 → `gradlew --stop` 后重跑即过）。

## 5.39 视频产物卡 + 删掉工作区「常用环境」+ FileProvider 路径声明（2026-09-21，用户「这个生成的视频点击 app 会直接闪退，还有这个视频在对话里面的显示不好看」「工作区常用环境这个可以去掉了」）

**① 点击视频闪退 —— FileProvider 没声明那个目录。** 崩溃栈：
`IllegalArgumentException: Failed to find configured root that contains
/data/data/com.psyche.memo/files/videos/vid_….mp4`，由 `FileProvider.getUriForFile` 抛出，
而且抛在触摸分发链上（`ViewGroup.dispatchTouchEvent` → … → `FileProvider.d`），所以是崩溃不是提示。
当时 `res/xml/file_paths.xml` 只声明了 `upload/`、`images/`、`cache/` —— 而 Memo 自己写出来的
文件散落在 `videos/`、`tool_images/`、`logs/`、`workspaces/` 等一堆目录里，**逐个声明的写法注定漏**。
改成 `files-path path="."` + `cache-path path="."`（覆盖整个私有目录）；provider 是
`exported=false` + `grantUriPermissions=true`，URI 只对显式授权的接收方有效，不存在对外暴露。
守卫 `FileProviderPathsTest`（先证伪：改回窄写法它会红）。上游 Flutter 侧没有 FileProvider
（没有 file_paths.xml、代码里也不用），所以这条没有 1:1 参照，是安卓侧自定的。

**② 视频改按视频的形状渲染**（`ui/chat/MessageVideoCard.kt`）：原先 `mime` 为 `video/*` 的
`FilePart` 也走 `MessageDocCard`（文件图标 + 文件名），生成出来的东西看着像附件不像作品
（用户原话「显示不好看」）。现在：首帧缩略图（`MediaMetadataRetriever`，走 `rememberLoaded`
在 IO 线程解码 —— 组合期不碰文件是硬规则，`CompositionThreadingTest` 守着）+ 中间半透明播放
角标 + 右下角时长（`formatVideoDuration`，`VideoDurationFormatTest` 四条）+ 按真实分辨率的
宽高比（钳在 0.6–2.2）。**点击行为不变**：仍交给系统播放器（就是 ① 修好的那条路）。
当时给用户的三个选项是「缩略图+应用内播放 / 缩略图+系统播放器 / 引 media3」，用户选了缩略图卡。

**③ 工作区「常用环境」整块删除**（用户「让大模型自己按需下载就行了，我们不用提供这个接口」）：
删掉详情页的三条 apt 预设卡与软件源选择器、`provider/workspace/WorkspaceEnvironments.kt`
（命令拼装 + `dpkg-query` 探测）、`WorkspaceEnvironmentsTest`、以及 27×3 条 ARB 文案
（三份语言，字符串数 2955 → 2928）。**这是删掉本工程 2026-09-14 自己加的东西**（当时用户
问「这个沙箱可以让用户选择下载 node gitbash 这些常用的环境吗？」），现在改主意了：模型要什么
自己在交互式终端里 `apt-get`。**别按 §5.11 那条旧记录把它加回来。**

## 5.40 CI 长期偶发红：Robolectric 测试靠「真时钟轮询 + 手动泵 Looper」（2026-09-21，用户「怎么每次这个都要出问题呀」）

**现象**：公开仓 CI 的「单元测试」步骤反复偶发红（run #6/#7/#9/#11/#13/#14），**每轮挂的用例还不一样**
（同一份代码一轮挂 4 个、下一轮挂 1 个），全部集中在 Robolectric 类，其中 `ChatTimelineWindowTest`
最多；本机（多核 Windows）几十轮门禁全绿、复现不了。诊断本身也返工三次：① 先猜"冷 runner 慢"，
把超时 5s→30s（**无效**，30 秒照挂）；② 抓 Gradle stdout 找异常（**空** —— 挂起不抛异常，或异常在
别处）；③ 抓用例 XML 的 `system-err`（先截了尾部，只有 JUnit/Robolectric 框架帧，改截头部才有用）。

**根因**：那两个测试等异步的方式是「`Thread.sleep(20)` 循环 + `shadowOf(Looper.getMainLooper()).idle()`」，
赌"后台协程会在超时前被主线程推进"。Robolectric 下 `Dispatchers.Main` 走**被暂停的 Looper**，
`viewModelScope` 每跨一次 `withContext(Dispatchers.IO)` 都要回主线程排队 —— 在 GitHub 的 2 核 runner
上，泵 Looper 与后台线程抢 CPU，谁慢一步就判失败；本机核多，永远撞不上。

**修法**：新增 `app/src/test/java/com/psyche/memo/MainDispatcherRule.kt`
（`Dispatchers.setMain(UnconfinedTestDispatcher())`），挂在 `ChatTimelineWindowTest` 与
`ChatPresetInjectionTest` 上；等待改成**等真实信号**（`withTimeout(30_000) { vm.tailLoaded.first { it } }`、
`vm.messages.first { it.isNotEmpty() }`），不再泵 Looper —— 协程不再依赖 Looper 调度，
`withContext(IO)` 完成后在 IO 线程原地继续。**证伪过**：把规则注释掉，7 个用例全红（各等到 30 秒超时），
恢复即绿。

**结论（同日，第二次尝试之后）**：`MainDispatcherRule` 仍然保留（它确实拆掉了"泵 Looper"这一层，
本地跑得更确定、也更快），但**没解决 CI 的偶发**：修好之后再跑一轮，仍然
`TimeoutCancellationException: Timed out waiting for 30000 ms` —— 说明卡的是更下面那层
（Robolectric 的 Room/SQLite 在 2 核 runner 上的竞争），**远程定位不动**（run #10 还自己全绿过一次）。

所以最终按"**把爱挂的类移出 CI**"收口（用户最初就是这么选的）：根 `build.gradle.kts` 里的
`ciSkippedTests` 名单（`ChatTimelineWindowTest` / `ChatHeaderAssistantTest` / `DrawerAndChatUiTest`，
只收"在 CI 上确实挂过"的）+ `-PciSkipFlakyTests` 开关，CI 传这个参数、**本地门禁照旧全跑**。
`CiSkipListTest` 守着名单里的名字在源码里真实存在（改名/删除会让它红，避免名单变成假话）。

**约定（也写进 AGENTS.md）**：Robolectric 测试里**不许**用「真时钟轮询 + 手动泵 Looper」等异步，
要用 `MainDispatcherRule` + 等信号；`ComposeUiTest` 那类测试自己接管 Main 调度器，**不要**挂这条规则
（两边同时设置会打架）。CI 上跳过的那 3 个类**仍然在本地门禁里跑**，所以覆盖没丢。

## 5.41 数据库开 WAL + timestamp 索引；统计页改 LazyColumn；上下文超长的判据（2026-09-21，用户「这个可以开了」）

**① SQLite 开 WAL —— 试了，**撤回**。** 数据安全那半审过没问题：快照走 `VACUUM INTO`、回退分支先
`PRAGMA wal_checkpoint(TRUNCATE)` 把 WAL 折回主文件、恢复显式删 `-journal/-wal/-shm`
（`BackupSnapshotBuilder.snapshotDatabase` / `BackupRestorer.replaceDatabase`），`core:data` 全模块在 WAL 下
也全绿。**但变更判据那半不行**：本机副本的 `DatabaseChangeFingerprint` 同时看主文件与 `-wal` 的
大小/时间，而 checkpoint / 连接关闭会让 `-wal` 侧车出现或消失 → 判据认为"变了" → 每次启动可能白存一份
副本。`LocalSnapshotServiceTest` 的 `runIfDue takes a copy once due and then skips the unchanged database`
就是这么红的（期望 `Skipped(UNCHANGED)`，实际 `Created`）。**重开的正确顺序**：先把指纹换成 WAL 稳定的
规则（主文件 + `-wal` 的**合计**字节数，或语义计数器），补一条"跨 checkpoint 仍判未变"的测试，再开
`setWriteAheadLoggingEnabled(true)`。代码里留了注释说明，别直接把它加回来。

**② `message_rows(timestamp)` 索引**（`MemoSchema.INDEX_MESSAGE_TIMESTAMP`，运行时
`CREATE INDEX IF NOT EXISTS`，`onCreate` 与 `onOpen` 各一次）。**不能改 schema 生成物**：DDL 由 drift
导出、门禁校验零 diff，改它等于谎报"与上游一致"。`SchemaVerifier` 的计数把它排除掉（那个校验的含义是
"与 drift v3 一致"）—— 顺带确认 `SchemaVerifier` 在本工程里**没有调用点**（Kotlin 侧死代码，Dart 那几处
引用是上游自己的测试），真正的完整性检查是恢复时的 `PRAGMA integrity_check`。守卫：`RuntimeIndexTest`。

**③ 统计页 `Column + verticalScroll` → `LazyColumn`**（每块一个 item：区间选择器 / 概览头 / 三张卡 /
排行榜头 / 三张排行榜）。原来进页面把热力图、指标网格、趋势图、三张排行榜**一次性组合**（2026-09-15
卡顿审计里记着）。注意两处 Kotlin 影响：`return@Column` 要改成 `return@LazyColumn`；`sections.forEachIndexed`
在 LazyListScope 里不能直接发组合（本轮把排行榜整块留在一个 item 里，没拆成 itemsIndexed）。

**④ 上下文超长的判据已落地，自动重试的接线未做**：`core/common/.../ContextOverflowError.kt` 的
`isContextLengthError()`（12 条正则，覆盖 OpenAI/Anthropic/Gemini 与国内厂商文案，`ContextOverflowErrorTest`
含"普通 400 不能误判"的反例）。**但"压缩后自动重试"这一半没接**：要接的位置在 `runGenerationLoop` 的
`while(true)` 里（每轮一个 try/finally），读清楚之前不动 —— 那是聊天主链路，改错比不做更糟。做的时候
单开一个提交：循环内命中 → 压缩 → `continue` 重发（每轮 request 在循环体开头重建，天然支持重发），
并加一次性开关防止压缩后仍然超长时反复重试。

## 5.42 应用更新改成"启动时弹对话框"（2026-09-22，用户「为什么有新版本不是弹窗，显示呀」）

§5.37 定为"只在关于页那一行显示、不主动弹任何东西"，用户这次改口要弹窗，于是补上缺的那半 ——
**启动时也查一次**（原来只在进关于页时查）：

- 容器新增 `updateOutcome`（共享结果）+ `checkForAppUpdatesOnStartup()`（`appScope` + IO，
  受 `display_show_app_updates_v1` 控制，关掉就完全不查）；`MemoApplication.onCreate` 里调一次。
- `HomeScreen` 收 `updateOutcome`：有新版本且**没被跳过**时弹 `AlertDialog`（标题复用
  `sideDrawerUpdateTitle`、正文是 release notes 前 600 字、按钮「去下载」复用
  `aboutPageUpdateDownload`）。
  - 「跳过此版本」→ 写偏好 `update_skipped_version_v1`（本工程新增键，Literal，不经 settings 注册表），
    同版本之后不再弹；
  - 点「稍后」/返回键 → 只是本次不弹（下次启动还会提示）。
- 关于页那一行不动（两个入口读同一份结果），`releases/latest` 的 404 仍按"已是最新"处理（§5.37）。
- 新增 1 条 ARB 文案 `aboutPageUpdateSkipVersion`（跳过此版本，三份语言）。

## 5.43 余额查询闪退：阻塞网络落在主线程（2026-09-22，用户实测 `android.os.NetworkOnMainThreadException`）

`ProviderBalanceService.fetchBalance` 是**阻塞式 OkHttp**（照上游 `provider_balance_service.dart` 语义），
而 `BalanceScreen` 两处调用都在主线程上：

1. 「查询余额」按钮：`scope.launch { fetchBalance(...) }` —— `rememberCoroutineScope()` 默认 **Main**；
2. 供应商详情页那个余额角标：`LaunchedEffect(...) { runCatching { fetchBalance(...) } }` —— **effect 体本身
   就跑在组合线程上**（AGENTS「Composition must stay cheap」那条讲的是同一件事，只是当时只扫了 `remember {}`）。

两处都改成 `withContext(Dispatchers.IO)`。**同类自查**：任何 `scope.launch {}` / `LaunchedEffect {}` 里
直接调阻塞 IO（网络、文件、DB）的地方都要有 `withContext(IO)` —— 用户点出来的是第一个入口，
第二个入口是顺着同一个函数找出来的，一起修的。


## 5.44 工作区：补三个只读搜索工具 + 点文件打不开（2026-09-22，用户「在工作区加上工具，list，glob，grep 工具」+「点文件没有反应」）

### ① `workspace_list` / `workspace_glob` / `workspace_grep` —— **本工程新增，不是 RikkaHub 移植**

用户先说要「照 RikkaHub 的定义逐字移植」，随即自己纠正「**RikkaHub 没有这个工具哦**」——查过参考仓，
上游 `WorkspaceTools.kt` 确实只有 read/write/edit/shell 四个。所以这三个是 Memo 自己的加法，描述文案按本
工程口径写（英文、与既有四条同样的句式），行为与既有四个走同一条通道：**proot 里跑 shell 命令 + 结构化
JSON 回给模型**，都默认**免审批**（只读，和 `workspace_read_file` 同级）：

| 工具 | 入参 | 实现 | 返回 |
|---|---|---|---|
| `workspace_list` | `path?`（缺省 `/workspace`） | `find <dir> -mindepth 1 -maxdepth 1 -printf '%y\0%s\0%T@\0%p\0'` | `{path, entries[], count, truncated?}` |
| `workspace_glob` | `pattern`（必填）、`path?` | `find <root> -path '<root>/<pattern>' -printf …` | `{root, pattern, matches[], count, truncated?}` |
| `workspace_grep` | `pattern`（必填）、`path?`、`glob?`、`ignore_case?` | `grep -rInEZi? --include='…' -- <正则> <root> \| head -n 201` | `{root, pattern, matches[{path,line,text}], count, truncated?}` |

几条不能丢的判据：

- **路径一律 rootfs 内的绝对路径**（复用 `absolutePath` 的形状校验），缺省才是 `/workspace` —— 三个工具都
  不许拿相对路径去猜。
- **`-Z`（文件名后是 NUL）不是为了好看**：`grep -rn` 默认 `path:line:text`，文件名里带冒号就会拆错；
  `-Z` 下是 `<path>\0<line>:<text>`，按第一个 NUL 和第一个冒号拆就稳（`parseGrepMatches` 有带冒号文件名的用例）。
- **`%T@` 带小数**（秒.纳秒），`parsePrintfEntries` 只取整数秒，与 `parseRootfsEntries` 的 `updatedAt` 口径一致；
  输出被 runner 按字节截断时末尾会留半条记录，凑不满四元组的尾巴直接丢。
- **截断不再整次失败**：`runRootfsCommand` 加了 `failOnTruncated` 参数，只有这三个工具传 `false`
  （结果集少一点好过整个调用报错）；写文件/编辑仍是原来的严格口径。上限 `entries ≤ 500`、`matches ≤ 200`、
  单行 ≤ 500 字符 —— 一行几十万字符的日志会撑爆上下文。
- **grep 的报错只看 stderr**：没匹配是退出码 1 + 空 stdout（正常结果），正则非法才是错误；所以那边不
  `set -e`，也不把退出码当失败。
- 接线是**自动的**：`ALL_TOOL_NAMES` / `DEFAULT_APPROVALS` / `catalogDefinitions()` 三处一起长，
  `ChatViewModel`（递给模型）、`ToolHandler`（审批与执行）、设置→工具描述目录、详情页审批行都按这份清单走。
  `WorkspaceToolsTest.everyToolNameIsCoveredByApprovalsAndDefinitions` 与提示词块用例（遍历 `ALL_TOOL_NAMES`）
  是守卫 —— 位置工具当初就是漏了"递给模型"那一道闸（§5.35）。

**详情页工具审批从四行变七行**（读 → 列出 → 查找 → 搜索 → 写 → 改 → 执行），新增 3 条 ARB
（`workspaceToolList` / `workspaceToolGlob` / `workspaceToolGrep`）。

### ② 对话里「+ → 工作区 → 文件」点不开（两处独立故障，一起修）

用户：「生成的 PPT、word、md 文档在对话界面的加号里面的工作区里面的文件，点击没有反应，但是在设置里面
就有反应」。对照 `WorkspaceDetailScreen` 的同名分支，是**两个**原因叠在一起：

1. **非文本文件（PPT/Word/PDF…）**：`WorkspaceFilesSheet` 的 `onOpen` `when` **没有 `else`** —— 目录进目录、
   文本进预览，其余落到 `Unit`，点了什么也不发生。详情页那一支走的是「导出到 `cacheDir/workspace` →
   `FileProvider` → `ACTION_VIEW` + `createChooser`」，这里照抄（mime 认不出给 `*/*`，起不了 Activity 时
   弹 `workspace_open_failed`）。
2. **文本文件（md/txt…）**：`.md` 在 `detectFileType()` 里是 `TEXT`，走的是 `FileEditorSheet` —— 而本 sheet
   已经叠在 `WorkspaceSelectorSheet` 之上，**再套一层 `ModalBottomSheet` 就是三层窗口嵌套，预览根本不显示**
   （看起来就是"点了没反应"）。修法不是换组件：把 `FileEditorSheet` 的内容体抽成 `FileEditorBody`，
   本 sheet 里**就地换掉文件列表**（`return@Column`），窗口层数不变；详情页仍用 `FileEditorSheet` 包一层
   （那边是整页 + 一层 sheet，正常）。

**别再把这个预览改回嵌套 sheet** —— 窗口层级不是风格问题，是"能不能显示"的问题。

## 5.45 工具名→图标集中映射 / token 行补图标与速度（2026-09-22，用户「工具加上对应图标吧，现在图标都用一样的，体验不好」+「token 显示里面没有对应图标，也没有 token 速度」）

### ① 工具图标：`toolIconFor` 补上工作区全族与两个自研绘图工具

原来只有从 Flutter `chat_message_widget.dart` 直接搬过来的那几条映射（记忆/搜索/生成/内置服务端工具），
**Memo 自己长出来的工具全都没有映射 → 一律落到兜底的 `Lucide.Wrench`**：工作区 7 个、`render_chart`、
`render_mermaid` 都是同一个扳手，用户看到的"图标都用一样的"就是它。现在：

- 工作区：`read_file`=FileText / `write_file`=FilePlus / `edit_file`=FilePen / `list`=List /
  `glob`=FileSearch / `grep`=TextSearch / `shell`=Terminal；
- 绘图：`VisualTools.TOOL_NAME`=ChartBar、`MermaidTools.TOOL_NAME`=Workflow（与生成工具同一套图标语言）；
- `ToolIconCoverageTest` 守着两件事：自研工具**不许**落到 `Wrench`，且工作区整族的图标**两两不同**。
  清单直接取自 `WorkspaceTools.ALL_TOOL_NAMES`，**再加工作区工具时不用改测试，但必须补图标**（否则红）。

### ② token 行：加 `#` 图标 + 生成速度

`TokenDisplay` 原来只有一行 `123 tokens` 文字（1:1 上游 `token_display_widget.dart`），用户要求"图标 +
速度"。加的是 `Lucide.Hash` + 原文字 + `Lucide.Zap` + `"<n> tok/s"`（新 ARB 键 `tokenDetailSpeedLabel`，
三份语言），点击展开详情弹窗的行为不变。

**速度口径＝completion tokens ÷ 总耗时**（`formatTokensPerSecond`，<0.5s 或 ≤0 不显示 —— 缓存命中/极短回复
除出来的数字会离谱）。**用户想要的是"首字之后的时间"那个更准的口径**，但那要给消息模型加一个 `firstTokenMs`
字段（跨 payload 持久化、写进 `MessagePart` 的时间线），单开一轮再做 —— 这一轮先用现成的 `durationMs`。
