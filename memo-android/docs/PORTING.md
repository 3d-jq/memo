# Memo Android 移植规格文档（PORTING.md）

> **本文件是移植工程的唯一事实索引。每轮开发开工先读、收工必须更新（进度表 + 新坑）。**
> 目标：Flutter 原项目（`D:\program\memo`，lib/）→ Android 原生（`D:\program\memo\memo-android`，Kotlin + Jetpack Compose），**严格 1:1**，禁止自创 UI/文案/图标/布局。

## 0. 构建与门禁（全部命令行，无 Android Studio）

```bash
cd /d/program/memo/memo-android
JAVA_HOME="C:/Program Files/Java/jdk-21.0.10" GRADLE_USER_HOME="D:/DevCache/.gradle" ./gradlew :app:compileDebugKotlin   # 快速编译
... ./gradlew :app:assembleDebug        # 出包（装机必须！只 compile 装的还是旧包）
... ./gradlew :core:data:testDebugUnitTest --tests "..."  # 单测
bash tools/quality_gate.sh              # 全量门禁（compile+test+lint），提交前必跑
adb install -r app/build/outputs/apk/debug/app-debug.apk   # 装机（包名 com.psyche.memo.dev）
```

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
| `SectionCard {}` | SettingsUi.kt | iOS 分组卡 r12（Flutter r16 版另有 `Surface16Card`@AssistantSettingsEditScreen） |
| `SettingsRow(icon,label,onTap,detailText)` | SettingsUi.kt | iOS 行（label 会换行——单行版用 `EditNavRow`@AssistantSettingsEditScreen） |
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
| Lucide 图标 | `com.composables.icons.lucide.Lucide.*` | **Wand2 叫 `WandSparkles`**；RTL 图标必须 `Icons.AutoMirrored` 变体 |

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

## 5. 批次进度（收工更新）

| 批次 | 范围 | 状态 |
|---|---|---|
| A1 | 助手列表页 + AssistantStore + seed + assistant_rows PK 修复 | ✅ ee13be1 / 3668933 |
| A1.5 | 拖拽 animateItem+zIndex；滑动 pane 修复 | ✅ 2bbb691 |
| A2 | 编辑页骨架 + EditSegTabBar + basic tab 静态行 + 路由 | ✅ cf19bcd |
| A2b | basic tab：聊天模型选择 + 聊天背景（选图/清除/预览） | ✅ 本轮 |
| A2c | basic tab：4 个参数 sheet（Temperature/TopP/上下文滑块 + MaxTokens 输入）+ 上下文精确值弹窗 + 头像选择 sheet（相册/emoji/链接/QQ/重置）；偏差：相册图按原字节拷进 filesDir，未做原版 maxWidth1024/quality90 降采样（同 A2b 背景） | ✅ 本轮 |
| A2c.5 | 思考预算行 → `showReasoningBudgetSheet`（reasoning_budget_sheet.dart 342 行，未移植） | ⬜ |
| A3 | 提示词 tab 1/3：系统提示词卡（全屏编辑 sheet + 文件导入 + 变量表 + 缓存告警）+ 追加当前时间行 + 两弹窗；tab 顺序对齐 `defaultAssistantEditTabIds`；`PromptTransformer.applyMessageTemplate`（core:llm） | ✅ 本轮 |
| A3b | 提示词 tab 2/3：消息模板卡（4 变量 + 实时预览）+ 预设对话卡（pill/内联输入/_PresetMessageCard/拖拽/编辑 sheet）+ `PresetMessage` 模型 | ✅ 本轮 |
| S1 | 搜索体系 1/3：`SearchServiceOptions` 24 选项类（JSON 逐键对齐）+ `SearchSettingsRepository`（search_service_rows + preference 键）+ 引擎（bing_local/tavily/searxng/brave/serper/bocha/zhipu/duckduckgo 8 个 provider + key 轮换）+ `search_web` 工具（定义/引用提示词/执行）+ 系统提示词注入（assistant.systemPrompt + 搜索引用块） | ✅ 本轮 |
| S2 | 搜索体系 2/3：`search_settings_sheet`（输入栏 Globe 入口 + 设置页"搜索"行）+ 搜索服务列表页（连接状态胶囊/长按测试与删除/通用选项步进器）+ 服务编辑器（类型 chips + 24 类型表单 + 多 Key 入口 + 连接测试）+ API keys 池页；路由 `search_services` | ✅ 本轮 |
| S3 | 搜索体系 3/3：其余 15 个 provider 引擎（exa/linkup/metaso/ollama/jina/perplexity/querit/stepfun/firecrawl/tinyfish/anysearch/doubao/parallel/you/grok）——共 23 个可运行 provider | ✅ 本轮 |
| S4 | 搜索收尾：用量查询卡（Tavily 余额/进度条 + LinkUp 余额 + 自动查询；`SearchUsageService` 纯解析带测试） | ✅ 本轮 |
| S5 | 搜索剩余：kelivo 内置搜索（上游端点 + 内置令牌，按品牌规则不移植）、启动自动测试（原版移动端也只有存盘开关，无执行路径） | ⬜（低优先/不移植） |
| F1 | 多模态输入引擎：`MessageContent`（图片 part → OpenAI content 数组 / Claude image block / Gemini inline_data+file_data；data:/本地文件 base64、远端 URL 分协议处理、去重、file part 暂跳过）+ 三客户端接入 + ChatViewModel 历史带图片 | ✅ 本轮 |
| M1 | 记忆工具执行：`MemoryTools`（memory_read / memory_update / memory_search_profile / memory_edit / memory_delete / update_user_profile 六个定义 zh/en 逐字对齐 + 执行；写入走 assistant.memoryWriteScope 解析，临时会话拒写，重复内容 SKIP/NEW 回退路径；未移植 Smart Add LLM 合并与 chat_search）+ ChatViewModel 在 enableMemory 时提供工具 + ToolHandler 分派 + AppContainer.memoryProviderV2 单例 | ✅ 本轮 |
| M2a | 记忆摘要注入：`MemoryBlockBuilder`（`<user_profile>`/`<user_memory>` 块、summary 模式 mode/total/shown + moreHint、global 优先排序、escape/flatten、SHA-256 前 16 位哈希）+ ChatViewModel 把快照前缀加到本轮最后一条用户消息（enableMemory 且有内容时） | ✅ 本轮 |
| M2b | 助手编辑页记忆 tab（`AssistantEditMemoryTab`：总开关 + 自动整理/整理频率/去重模式/写入范围 + 过往回忆/生成摘要/摘要频率 + 记忆设置入口；选择 sheet 与数字弹窗）+ 路由接线 | ✅ 本轮 |
| M2c | `chat_search` 工具：定义（zh/en）+ MessageDao.searchMessagesForAssistant（按助手范围/排除当前会话/指定会话、tokens AND、按时间倒序）+ 片段窗口 + 记忆规则注入（`MemorySettingsState.prompt(RULES)` 与 `rulesPastConversationRecallFor`，各自独立门控） | ✅ 本轮 |
| M2d | 记忆收尾：哈希冻结/自愈（§7.6）、Smart Add LLM 去重合并、tab 内记忆条目列表与整理按钮、legacy 记忆模式工具 | ⬜ |
| F4 | 图片 OCR：`OcrService`（读 ocr_enabled/ocr_model/ocr_prompt/thinking 设置；OCR 模型跑图 → 文本；`<image_file_ocr>` 块前置；SHA-256 内容哈希 + LRU 缓存）+ 底部面板 OCR 行（开关 + 长按提示词 sheet） | ✅ 本轮 |
| F3 | 文档文本抽取：`DocumentTextExtractor`（PDF=PDFBox-Android、DOCX=zip+document.xml、.doc 不支持、其余 UTF-8 兜底；path+stat 缓存）+ `UnicodeSanitizer` 移植 + 用户消息把文件文本按 `## user sent a file` / `<content>` 块前置进请求 | ✅ 本轮 |
| F2 | 附件选择 UI：底部工具面板（bottom_tools_sheet.dart 三张 72dp 卡：相机/相册/文件）+ `AttachmentStore`（URI 拷贝到 filesDir/upload）+ 附件预览条（64dp 图缩略图 r10+scrim 删除角标 / 48dp 文档 chip）+ ChatViewModel 待发附件并入用户消息 parts；相机走 FileProvider（新增 provider + file_paths.xml）；原版面板里的指令注入/世界书/OCR/上下文管理行因功能未移植暂不渲染 | ✅ 本轮 |
| B | 记忆/本地工具/MCP tab | ⬜ |
| C | 快捷短语/自定义请求/正则 tab + tab 布局管理页（AppBar Settings2 按钮） | ⬜ |

## 6. 规格速查（Flutter 源码 → 要点，避免重复侦察）

- 编辑页骨架：`assistant_settings_edit_page.dart` L80-152(tab specs) L316-410(scaffold) L1262+(_iosNavRow：36 图标槽/15sp 单行 label/13sp detail/chevron) L632+(_SegTabBar：44/4/18/6/88、选中 primary 14%、文字 primary vs onSurface 82%)
- basic tab：`assistant_settings_edit_basic_tab.dart`（身份卡 L136-161；设置卡 L162-263；聊天模型卡 L264-357：标题+RotateCcw+副标题+选择行[surfaceFill r12 h12v10、BrandAvatar 24、14 semibold，显示 override 名?:modelId，无模型"使用全局默认"]；背景卡 L373-510：Image 标题+12sp 描述、空→居中选图按钮[outlineVariant 35% 边框]、有→两 _IosButton 并排+ClipRRect r10 预览；_pickBackground：gallery maxWidth1920 quality85 存路径）
- basic tab 参数/头像 sheet（同在 `assistant_settings_edit_basic_tab.dart`，**不在** edit_page）：`_showTemperatureSheet` L635-747、`_showTopPSheet` L749-859、`_showContextMessagesSheet` L861-998（三者同构→共用 `ParamSliderSheet`；padding 16/12/16/18、top r16、overlaySurface、40x4 α0.2 拖柄、标题 16 semibold+IosSwitch、`_SliderTileNew` L1217-1401、描述 12 α0.6，关闭态 `parameterDisabled`/上下文用 `parameterDisabled2` 13 α0.6 上下8）；`_ValuePill` L1403-1439（r10、primary α0.18暗/0.10亮 底 + α0.28/0.22 边框、padding 10/4、12 emphasis）；`_showMaxTokensSheet` L1000-1130（X 20 / 居中标题 / Save primary 16 semibold 按压 0.7，数字框 autofocus filled surfaceFill r12 边框 outlineVariant40/primary50，空串→null）；上下文精确值弹窗 `_showContextMessageInputDialog` 在 `assistant_settings_edit_page.dart` L181-256（label/helper/description 都带 `(1-4096)`，常量 L79-80=`Assistant.min/maxContextMessageSize`）。头像：`_showAvatarPicker` L517-617（**顶圆角 20**，非参数 sheet 的 16；padding 16/12/16/16、拖柄后 10、尾 4）、行=内联 `row()` L529-558（外 v4、h48、`IosCardPress` r14、内 padding h12、15 medium，先 pop 再延迟 10ms 执行 action）、emoji 面板 `_pickEmoji` L1442-1702（QuickEmojis 112 个 L1451-1564、8 列 spacing 8、网格高 =（屏高−ime）×0.28 clamp 120..220、预览 72 圆 primary α0.08 + 40、字段 autofocus surfaceFill r12 透明边框/聚焦 primary40、`validGrapheme` 取**原始串**首个字素再 trim 故前导空格判否）、链接 `_inputAvatarUrl` L1704-1780（仅 http(s) 前缀）、`_inputQQAvatar` L1782-1946（正则 `^[0-9]{5,12}$`、`actionsAlignment: spaceBetween`→随机在左/取消+保存在右、随机 20 次探测 `q2.qlogo.cn/headimg_dl?dst_uin=X&spec=100` 命中即 pop、失败 `q_q_avatar_failed_message` 吐司且**不**关弹窗、`randomQQ()` L1793-1833 长度权重 [1,20,80,100,500,5000,80]/首位数组权重 [128,4,2,1]）、`_pickLocalImage` L1948-1985（gallery maxWidth1024 quality90；取消→静默返回；异常→错误吐司后落到链接弹窗）。三个弹窗的 Save 均显式 primary/失效 onSurface α0.38。平台简化：SfSlider 刻度/间隔标签/水滴 tooltip 全部省略（值常显在胶囊上）。
- 列表页：`assistant_settings_page.dart`（Slidable endActionPane 0.6、复制命名"xx 副本 N"、最后一个不可删）
- 提示词 tab：`assistant_settings_edit_prompt_tab.dart`（_PromptTab build L273-912：ListView padding LTRB 16/8/16/20，卡片间距 12，顺序 sysCard→appendTimeCard→tmplCard→presetCard。sysCard L301-457：surfaceCard r14 padding12、标题 15 emphasis + IosIconButton(Maximize2,20,p8,min38,primary)+4+IosButton(import,Icons.file_open,dense,neutral:false)、字段 maxLines8 r12 边框 enabled outlineVariant35/focused primary50 contentPadding12、availableVariables 12 semibold、_VarExplainList L1674-1728（Wrap 16/8，"label: " 12 onSurface75 + 下划线 primary semibold 可点，3 个时间变量带 TriangleAlert14+Tooltip）、AnimatedSize180 告警条 errorContainer30 r12 p12 + TriangleAlert18 + 12/1.35 onSurface80。appendTimeCard=SectionCard(_AppendCurrentTimeRow L915-1004：h12v10、36 槽 Lucide.clock20（开=primary）、标题15 semibold+3+副标题12/1.25 onSurface62、IosIconButton(BadgeInfo,16,p6,min32,onSurface55)+4+IosSwitch）。_SystemPromptMobileSheet L1189-1271：高 0.96 屏、overlaySurface、顶 r18、padding 16/10/16/bottom+16、_HoverTextButton(enableHover:false,dense) 关闭/保存、Expanded surfaceFill r14 边框 outlineVariant20 + expands TextField autofocus contentPadding12。**已移植**=以上全部；**待移植**=tmplCard L470-586（4 变量 {{role}}/{{message}}/{{time}}/{{date}} + 2 条 ChatMessageWidget 预览）、presetCard L589-898（_HoverPillButton User/Bot、内联 AnimatedSize200 输入、_PresetMessageCard L1006-1077、ReorderableColumn）。桌面 `_SystemPromptDesktopDialog` 不移植。两处平台简化：SAF 无法按扩展名过滤（16 种白名单只在读取处兜底）、Compose TextField 无 contentPadding 参数（12dp 内边距用外层 Box border + padding 等价实现）。
- 模型选择：Flutter `showModelSelector`（model_select_sheet.dart 2533 行）→ Android `ModelSelectSheet`。视觉主体已对齐（卡片行 r14、品牌头像 28、单行名、Lucide 心形、搜索框 surfaceFill+r14 边框聚焦 primary 50%、chip 点击滚动分组）。**仍未移植**：ModelSelectSheet 的 DraggableScrollableSheet 可拖拽高度（原版 min 0.4 / initial=max 0.8，现固定 0.8）。已补齐：长按模型详情 sheet（ModelDetailSheet.kt，编辑/创建双模式 + Basic/Advanced/BuiltInTools 三 tab + ModelRegistry.inferFull 完整投影 + 类型切换缓存 + headers/body 覆盖 + 内置工具按 ProviderKind 分类）。已补齐：详情 sheet 可拖拽高度（原版 DraggableScrollableSheet 0.4-0.95 initial 0.8 → Compose 原生 NestedScrollConnection 等价移植：列表在顶下拉压缩高度、到 0.4 即 sheetState.hide() 关闭（对齐 shouldCloseOnMinExtent），顶上推长高到 0.95 后列表接管；头部下拉关闭走 ModalBottomSheet 自带 drag-to-dismiss）。已补齐：ModelTagWrap 能力标签（ModelRegistry 正则推断）、pinnedModels 收藏系统（收藏组置顶 + 书签跳转 + 搜索聚合去重）、搜索跳转首个匹配组；吸顶 provider 头（_stickyProviderHeader）按用户决定不复刻。

- 搜索体系（S1 已落地）：数据层 `core/data/model/SearchServiceOptions.kt`（24 个类，toJson 逐键对齐 search_service.dart；`type` 判别 + `apiKeys`↔extraApiKeys；step 别名→stepfun；未知类型回落 BingLocal）+ `repo/SearchSettingsLogic.kt`（纯 codec/夹取，带测试）+ `repo/SearchSettingsRepository.kt`（服务列表=search_service_rows 实体表，selected/common/enabled/autoTest=preference 键）。引擎 `app/provider/search/`：`SearchParsers`（各 provider 响应映射 + Bing/DDG HTML 解析用 jsoup + uddg 解包 + URL 归一化，带 fixture 测试）、`HttpSearchEngine`（8 provider 分发，未移植类型抛诚实错误）、`SearchApiKeyRotator`（[primary,...extras] 轮换 + parseBatch/mask）、`SearchToolService`（search_web 定义/描述/引用系统提示词/executeSearch 打 6 位 id）。接线：`ChatViewModel.offeredTools` 在 assistant.searchEnabled 时提供 search_web；`ToolHandler` 执行；`buildSystemPrompt` 注入 assistant.systemPrompt + 搜索引用块（message_builder_service.injectSearchPrompt L1734-1750）。**已补齐**：搜索设置 sheet / 服务列表页 / 编辑器（24 类型表单）/ API keys 池页 / 连接测试（S2，输入栏 Globe + 设置页"搜索"行 + `search_services` 路由）；23 个 provider 引擎（S3，kelivo 除外——上游端点+内置令牌按品牌规则不移植）。**待办**：用量查询卡与启动自动测试（S4）、`{{message}}` 模板与上下文条数限制仍未接入生成。
