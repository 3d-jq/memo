# kelivo Flutter → 原生 Android (Kotlin) 全量移植计划

> ⚠️ **本计划中的品牌与兼容性前提已作废。** 原生应用现为独立产品 **Memo**
> （包名 `com.psyche.memo`、库名 `memo.db`、目录 `memo-android/`），与 Flutter 原应用
> **不做数据兼容**，且任何用户可见文案/标识符/资源键/素材都不得出现 kelivo 字样。
> 下文「兼容性铁律」「包名沿用 com.psyche.kelivo」等条目仅为当时的计划记录，
> 现行约束以 `AGENTS.md` 为准。

## Context

用户无法使用 VPN（但直连 Google Maven / Maven Central / services.gradle.org 均实测可达），要把开源的 **kelivo**（Flutter 跨平台 LLM 聊天客户端 v1.2.5，584 个 Dart 文件，已浅克隆到 `D:\program\memo`）**全量移植为原生 Android 应用**，最终交付签名 release APK，并用 Android 模拟器逐阶段验收。用户已确认：多会话分阶段推进、本会话尽量做到 P2、Markdown 渲染走"原生 Compose 为主 + Mermaid/公式/HTML 特殊块 WebView 兜底"、验收用新建模拟器。

移植以移动端形态为目标；桌面专属（tray/hotkey/多窗口/stdio MCP/桌面布局）明确裁剪，Android 专属原生能力（ACTION_PROCESS_TEXT、高刷、后台服务、MCP OAuth deep link）保留。

## 已确认的环境事实与前置修复

| 项 | 状态 |
|---|---|
| JDK | 已装 `C:\Program Files\Java\jdk-21.0.10`；`JAVA_HOME` 环境变量错指 jdk-13 → 构建一律显式传 `JAVA_HOME`，项目 `gradle.properties` 写 `org.gradle.java.home` |
| Android SDK | `D:\Android\Sdk`：build-tools 34/36/36.1/37.0、platforms android-35/36.1/37.0、platform-tools、emulator、system-images、licenses 已接受；**无 cmdline-tools** |
| Gradle | `GRADLE_USER_HOME=D:\DevCache\.gradle`（软链），gradle-8.14-all 已解压（两条 hash 目录）；缓存 AGP 8.11.1、KGP 2.2.20/2.3.0/2.4.10、compose material3 1.3.2、androidx 全套 |
| ⚠️ 死代理 | `D:\DevCache\.gradle\gradle.properties` 残留 `systemProp.http(s).proxyHost=127.0.0.1 / proxyPort=7897`，端口无监听 → **构建前注释这 4 行**（改动前告知用户，.bak 保留） |
| 网络 | dl.google.com（0.4s）、repo1.maven.org、plugins.gradle.org 直连 200 ✓ |

## 工程结构

新工程：**`D:\program\memo\memo-android`**（与 Flutter 源平级并存，Flutter 源只读作为移植参照，不删除；.gitignore 补 `memo-android/build/`、`.gradle/`、`local.properties`、`keystore.properties`）。

单个 Gradle 工程（AGP 8.11.1 / Gradle 8.14 wrapper 指向本地已解压发行版 / Kotlin 2.2.20 + plugin.compose + plugin.serialization，version catalog `gradle/libs.versions.toml`），9 模块，依赖单向：`app → feature:* → core:*`，feature 间不互依赖：

- `app`：MainActivity、导航装配、通知/前台服务/process-text/高刷、签名
- `core:common`：微秒时间、取消令牌、AppContainer、常量
- `core:ui`：Material3 主题（动态取色+自定义色板+字号）、l10n、通用组件、Compose Markdown 渲染器、WebView 包装
- `core:data`：SQLite schema+DAO、133 设置键路由、实体 JSON DTO、备份 v2 引擎、WebDAV/S3
- `core:llm`：OkHttp 栈/代理、SSE 解析、OpenAI/Anthropic/Gemini/Vertex 客户端、供应商兼容层、工具循环、MCP 客户端
- `feature:chat` / `feature:assistant` / `feature:utility` / `feature:settings`

包名沿用 `com.psyche.kelivo`；compileSdk/targetSdk 35，minSdk 26。

## 兼容性铁律（移植的事实源，冻结 v3）

1. **DB**：`kelivo.db`，与 drift schema v3 **结构级兼容**（列名/类型/顺序/PK/FK/索引一致 + `PRAGMA user_version=3`）。备份 zip 里的 `database/kelivo.db` 可直接拷入使用。DDL 唯一事实源：`drift_schemas/app_database/drift_schema_v3.json` 的 **`fixed_sql` 数组（46 条 CREATE TABLE/INDEX 逐字 SQL）** —— P0 用 Python 脚本过滤透传，不手写重建。
2. **设置键**：以 `lib/core/database/business_settings_router.dart` 的 133 键注册表为准誊录（存 `preference_rows`，JSON 编码），9 个 localOnly 键留 SharedPreferences；未知键原样透传。
3. **业务实体**：payload 列内 JSON 原形状读写（写回不改形状），保证备份自洽。
4. **备份格式**：kelivo-backup v2 无加密 zip（settings.json + database/kelivo.db + upload/ + manifest.json）。

## DB 路线（已决策）

**不用 Room/SQLDelight**：Room 打开非自身创建库会因缺 `room_master_table` 报 integrity 错；SQLDelight 引入插件维护面。用 **SQLiteOpenHelper + 手写 DAO**：结构化表（conversation/message/message_part/asset/generation_run 等约 10 张）手写；同构 JSON payload 表由注册表驱动通用 `PayloadTableDao`。`onConfigure` 开 `PRAGMA foreign_keys=ON`。

## 依赖选型（版本经核实存在，兼容 KGP 2.2.20/AGP 8.11.1/SDK 35）

Compose BOM `androidx.compose:compose-bom:2025.06.01`（material3 1.3.2）、material-icons-extended 1.7.8、activity-compose 1.10.1、core-ktx 1.16.0、lifecycle 2.9.1、navigation-compose 2.9.1、okhttp 4.12.0 + mockwebserver（SSE 自写 ~150 行解析器，对齐原 Dart 语义）、kotlinx-serialization-json 1.9.0（payload 列用 JsonObject 原样存取）、kotlinx-coroutines 1.10.2、coil3 3.2.0、commonmark 0.26.0（+gfm tables/task/strikethrough）自建 Compose markdown、CameraX 1.5.2 + MLKit barcode 17.3.0（QR）、work-runtime-ktx 2.10.2、browser 1.9.0（Custom Tab）、**无 Hilt/无 KSP/无 Room**（手动 AppContainer）、SharedPreferences 对齐 localOnly 键。

## 分阶段总览（每阶段产出可安装 APK；P0-P5 assembleDebug，P6 签名 release）

| 阶段 | 内容 | 验收核心 |
|---|---|---|
| **P0 基建** | 工程骨架；`tools/drift_schema_to_sql.py` + `memo_schema_v3.sql` + SchemaV3Helper/Verifier；主题（动态取色/深浅/色板/字号）；`tools/arb_to_android.py`（en→values、zh_Hans→values-zh、zh_Hant→values-zh-rTW）；SettingsKeyRegistry（133 键）；assets 拷贝（html/mermaid）；模拟器准备；keystore | 启动；中英切换、深浅主题；user_version=3、29 表/17 索引 table_info 与 JSON 一致；assembleDebug 出 APK |
| **P1 聊天 MVP** | 会话/消息 DAO（幂等 UNIQUE、FK CASCADE、分页游标）；provider 配置+GET /models；SSE 解析器；OpenAI/Anthropic/Gemini 三客户端；0-chunk 指数退避重试+取消；流式聊天 UI（检查点局部刷新）；基础 markdown 渲染 | MockWebServer 单测；真 key 流式对话、打断、杀进程重启续读 |
| **P2 会话能力** | 助手编辑 tabs、provider 多 key/分组、模型选择、工具调用/部件可视化、分页、quick phrase/world book/指令注入、drawer 全入口 | 助手切换注入生效；工具轮次可视化；长会话不卡 |
| P3 工具与 MCP | 搜索执行器（首批 ~10 家注册表化）；MCP sse/http + OAuth(PKCE/DCR) + `psyche.memo://` deep link；memo_fetch inmemory | 搜索工具出结果；MCP 授权回跳 |
| P4 备份兼容 | v2 zip 读写；恢复 staging→lease→receipt；settings/实体 DTO 全映射；WebDAV；S3 SigV4 最小集；本地快照；ChatBox 导入 | 旧备份恢复一致；往返一致 |
| P5 语音/统计/翻译/QR/设置全量 | 系统 ASR+网络 TTS+系统 TTS；翻译页；统计（自绘图表）；QR；24 搜索补全；24 设置页 | 各项冒烟 |
| P6 系统打磨 | process-text 入口；高刷；通知+前台服务+WorkManager；R8；自适应图标；签名 release | 全套回归+签名 APK 装机 |

## 本会话执行：P0 → P1 → P2（尽力推进，阶段间出 APK + 模拟器冒烟）

### P0 交付物
- 修复死代理（注释 4 行，保留 .bak）
- 新建 `memo-android/`：settings.gradle.kts、9 模块、libs.versions.toml、local.properties（sdk.dir=D:\Android\Sdk）、wrapper 指向本地 gradle-8.14-all
- `tools/drift_schema_to_sql.py`：解析 drift_schema_v3.json fixed_sql → `core:data/assets/memo_schema_v3.sql`，断言 29 表/17 索引
- `SchemaV3Helper.kt` + SchemaVerifier 测试
- 主题：theme_factory 语义（亮暗 + 动态色开关 + 自定义色板先期载入 core:ui）
- `tools/arb_to_android.py`：ARB → strings.xml（en/zh/zh-rTW），占位符校对一轮
- SettingsKeyRegistry + PreferenceRepository
- app 壳：MainActivity + 空 Home + 导航骨架 + 权限清单（对齐原 Manifest）+ 图标资源拷贝（assets/app_icon*.png）
- 模拟器：下载 commandlinetools-win（dl.google.com/android/repository/）→ `sdkmanager "platform-tools" "system-images;android-35;google_apis;x86_64"` → avdmanager 建 AVD（镜像下载 ~1.5GB）
- keystore 生成：`keytool -genkeypair -keystore memo-android/keystore/memo-release.jks -alias memo`（产物 gitignore）
- 验收：AVD 冷启动装 debug APK；adb shell 校验 `PRAGMA user_version` 与表结构；主题/语言切换截图

### P1 交付物（参考 lib/core/services/api/*、lib/features/chat、lib/features/provider）
- ConversationDao/MessageDao/MessagePartDao（严格对齐 drift 语义：UNIQUE(conversationId,messageOrder)、groupId/version、部分索引、CASCADE）
- Provider 实体 DTO + 默认 baseUrl 推导 + models 列表拉取
- `SseParser.kt`（CRLF/[DONE]/相邻 JSON 容错）+ `SseReader` 流式；`RetryingFlow`（指数退避、0 chunk 才重试、可取消）
- 三协议客户端：chat_completions（含 vendor compat 头/路径改造最小集）、claude_official、google_common（gemini v1beta）
- 聊天 UI：会话列表页（drawer）+ 聊天气泡（流式 delta 更新、检查点落库）+ 输入栏（多行/发送/停止/重试）
- MarkdownRenderer v1：commonmark 解析 → Compose 组件（heading/列表/引用/代码块/表格/任务列表/行内样式/链接/图片），配色对齐主题
- MockWebServer 单测：SSE 容错矩阵、重试矩阵、三协议请求 golden JSON
- 验收：模拟器 + 真 key 流式对话（OpenAI 兼容/Claude/Gemini 任一）、停止生成、杀进程重启续读

### P2 交付物（参考 lib/features/assistant|model|world_book|quick_phrase|instruction_injection、lib/core/services/generation）
- 助手实体编辑（基础/系统提示/工具开关/MCP 引用/记忆）→ 注入消息构建
- 多 API key 管理（按 provider 轮换）、provider 分组、默认模型选择
- 工具调用 UI 卡（toolCall/result 部件渲染）+ 部件时间线消息结构（message_part 对齐）
- quick phrase / world book / instruction injection 数据页 + 发送管线注入
- 会话摘要/标题生成（异步）、分页加载（loadTimelinePage 语义 40 条/页）
- 验收：模拟器走通"建助手→带工具对话→工具卡可见→注入生效→长会话分页"

## 风险与已定取舍

- **覆盖安装不可能**（新签名≠原 Flutter 签名）→ 数据迁移通道 = P4 备份导入，首启给"从备份导入"引导
- **gpt_markdown 视觉 1:1 不可达** → 语义对齐 + 复杂文档 WebView 复用 `assets/html/mark.html` + `assets/mermaid.min.js`；公式 KaTeX 离线（P1 内联公式先降级文本）
- Syncfusion PDF 弃用（授权）→ 阅读用系统 PdfRenderer；图表自绘
- sherpa-onnx 离线 ASR：P5 先系统 SpeechRecognizer 保底，模型下载走前台服务，后置
- API key 沿用明文存储（尊重原存储与备份自洽），不做 Keystore 介入，文档提示
- 24 家搜索 API 漂移 → 注册表隔离、单 provider 独立失败

## 验证体系

每阶段收尾固定动作（`verification-before-completion`）：
1. `:app:assembleDebug` 零错；JVM 单测全绿（SSE/重试/schema/键映射）
2. 模拟器安装启动 + 该阶段验收清单（见上）
3. 更新阶段状态到记忆与计划，供下个会话续推

## 本次批准的执行范围

P0+P1+P2（尽量推到 P2；若会话过长，P2 收尾留待下个会话，以 APK+模拟器冒烟结果为准交接）。
