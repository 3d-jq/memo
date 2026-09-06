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
| `ModelSelectSheet(container,options,onSelect,onDismiss)` + `ModelOption(providerId,providerName,modelId,selected)` | ModelSelectSheet.kt | 模型选择 sheet（provider→model 两级+搜索），DefaultModel/Memory 已用 |
| `ProviderAvatarSmall(providerKey,displayName,size)` | ProviderListScreen.kt | 品牌头像（=Flutter _BrandAvatarLike） |
| `AssistantListAvatar(item,size)` | AssistantSettingsScreen.kt | 助手头像四态（http/本地/emoji/首字母） |
| `EditSegTabBar(tabs,selected,onSelect)` | AssistantSettingsEditScreen.kt | 44dp 胶囊分段条（88dp 最小宽+滚动）；**ProviderSheets.kt 另有一个 weight 平分版 `SegTabBar`，勿混淆勿重名** |
| `SwipeRevealRow` | AssistantSettingsScreen.kt | 左滑操作 pane（0.6W 右对齐、按钮撑满高） |
| `ReorderableColumn` | core/ui/ui/reorder/ | 长按拖拽列表（已带 animateItem+zIndex） |
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

## 5. 批次进度（收工更新）

| 批次 | 范围 | 状态 |
|---|---|---|
| A1 | 助手列表页 + AssistantStore + seed + assistant_rows PK 修复 | ✅ ee13be1 / 3668933 |
| A1.5 | 拖拽 animateItem+zIndex；滑动 pane 修复 | ✅ 2bbb691 |
| A2 | 编辑页骨架 + EditSegTabBar + basic tab 静态行 + 路由 | ✅ cf19bcd |
| A2b | basic tab：聊天模型选择 + 聊天背景（选图/清除/预览） | ✅ 本轮 |
| A2c | basic tab：5 个参数 sheet（Temperature/TopP/上下文/思考预算/MaxTokens 滑块）、头像选择 sheet | ⬜ |
| A3 | 提示词 tab（prompt tab 1728 行） | ⬜ |
| B | 记忆/本地工具/MCP tab | ⬜ |
| C | 快捷短语/自定义请求/正则 tab + tab 布局管理页（AppBar Settings2 按钮） | ⬜ |

## 6. 规格速查（Flutter 源码 → 要点，避免重复侦察）

- 编辑页骨架：`assistant_settings_edit_page.dart` L80-152(tab specs) L316-410(scaffold) L1262+(_iosNavRow：36 图标槽/15sp 单行 label/13sp detail/chevron) L632+(_SegTabBar：44/4/18/6/88、选中 primary 14%、文字 primary vs onSurface 82%)
- basic tab：`assistant_settings_edit_basic_tab.dart`（身份卡 L136-161；设置卡 L162-263；聊天模型卡 L264-357：标题+RotateCcw+副标题+选择行[surfaceFill r12 h12v10、BrandAvatar 24、14 semibold，显示 override 名?:modelId，无模型"使用全局默认"]；背景卡 L373-510：Image 标题+12sp 描述、空→居中选图按钮[outlineVariant 35% 边框]、有→两 _IosButton 并排+ClipRRect r10 预览；_pickBackground：gallery maxWidth1920 quality85 存路径）
- 列表页：`assistant_settings_page.dart`（Slidable endActionPane 0.6、复制命名"xx 副本 N"、最后一个不可删）
- 模型选择：Flutter `showModelSelector` → Android `ModelSelectSheet`（已对齐）
