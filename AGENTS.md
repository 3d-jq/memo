# AFENTS.md

## Project overview

Kelivo is a cross-platform LLM chat client built with Flutter, targeting iOS, Android, macOS, Windows, and Linux. Package name is `Kelivo` — imports use `package:Kelivo/...`.

**Memo** is a standalone native Android app (Kotlin + Jetpack Compose,
AGP 8.11.1 / Gradle 8.14 / Kotlin 2.2.20, minSdk 26 / target 35) in
`memo-android/`. It is **not** data-compatible with the upstream Flutter app:
`namespace`/`applicationId` are `com.psyche.memo` and the database is
`memo.db`. The SQLite DDL is still generated verbatim from drift v3
(`tools/drift_schema_to_sql.py` → `core/data/src/main/assets/memo_schema_v3.sql`)
because it is a proven schema — not for compatibility. No user-visible string,
identifier, resource key or asset may carry the kelivo name. Reference
implementation for native details: **RikkaHub** (https://github.com/rikkahub/rikkahub,
same AGPL-3.0 license; its `ai` module is the closest match to `core:llm`, local clone
at `D:\program\.rikkahub-ref`). Memo and RikkaHub are functionally very close (sibling
LLM chat clients with the same provider/tool/surface model), so when implementing or
fixing a Memo feature, **borrow or directly port from RikkaHub** as a valid source —
don't re-derive from scratch unless RikkaHub doesn't cover the case. The Flutter
source (`lib/`) remains the primary 1:1 source-of-truth for UI/text parity; RikkaHub
fills in native-side details and shared feature implementations.

## 有意偏离原版的地方（勿"修回"，除非用户改口）

这些地方**故意**与原版不同（**完整清单见 `memo-android/docs/PORTING.md` §5.11**，含平台差异与踩坑）：
- **旧版（V1）记忆模式不移植**（用户点名：Memo 无老数据）
- **供应商分组整块删除**：UI（详情页分组行 / 列表分组头折叠 / 移动分组钮 / 分组管理页与路由）与数据层（`ProviderGroup`、`ProviderGroupLogic`、三个分组偏好键、备份里的 `provider_groups_v1` 实体）全删——用户 2026-09-12「把分组这个去掉吧 我感觉没有什么用」「去掉就彻底呀」。**勿按原版加回来**
- **流式等待提示＝扫光文字**：`ThinkingShimmerText`（主题色轮换短语 + 扫光，列表末尾单独一行靠左），替代原版三点脉动（`LoadingIndicator`）。用户点名改；工具卡里的小三点保持原版。**勿按原版修回三点**。2026-09-13 用户又要求可自定义 → 显示设置 → 渲染 → 「流式等待提示」三行（字号 10–28sp／颜色跟随主题或 `#RRGGBB`／提示词逐行编辑），键 `display_thinking_indicator_font_size_v1`·`_color_v1`·`_phrases_v1`（本工程新增，原项目没有这个指示器）
- **⏸ `memo-android/docs/UI_AUDIT_2026-09-12.md` 是用户自己的 UI/UX 审计报告，用户说「先不做」**：不要当待办自行开工、也不要删
- **语音播放图标按消息归属**：原版 `chat_message_widget.dart:3253-3291` 用全局 `isActive`，读一条消息会让**所有**消息显示停止、且暂停不可见；我们按 `ownerId` 只让被朗读的那条响应（暂停显示"继续"）——用户实测后要求
- **搜索引用胶囊尺寸**：原版 20dp/12sp/20% 底，用户要求缩小一档（16dp/10sp/16%，全圆）
- **输入栏样式**：保持 kelivo 原样，但最小高改为 64dp（用户要求）；其余参数勿动
- **上下文压缩＝opencode 阈值机制**（用户 2026-09-13「改成 opencode 那个压缩阈值来压缩」）：算 `estimate(system+messages+tools)`，超过「上下文窗口 − max(输出预算, buffer)」时在**同一会话**里插入锚定摘要检查点（`CompactionPart` + `<conversation-checkpoint>`），不再「新建会话 + 摘要作首条消息」——**勿改回原版**；详见下面「上下文压缩机制＝opencode 阈值机制」条
- **品牌化**：无 kelivo 字样/链接/端点；归档建议名 `memo_backup_<stamp>.zip`、本机副本 `memo-snapshot-<nanos>.zip`
- ⚠️ **品牌残留（用户 2026-09-11：他自己后续替换，暂不处理）**：`AboutScreen.kt` 的社区链接三行仍指向上游（`kelivo.psycheas.top` / `Chevey339/kelivo`）——**勿代改、勿当待办追问**，等用户给新 URL 或说删行；见 PORTING.md §5.11

## Native Android port (memo-android)

**Golden rule (user requirement): 1:1 translation of the Flutter source — every
screen, component, string and icon must come from the original kelivo code.
Never invent UI/UX. The Flutter project in this repo is the source of truth;
RikkaHub is only a reference for solving native-side issues.**

1:1 covers behaviour, layout and components — **not branding or upstream
endpoints**. Do not carry over the kelivo name, its GitHub/Discord/afdian links,
the `kelivo.psycheas.top` update/sponsor/tools endpoints, or upstream-branded
seed entries; comment references to Flutter source paths (`mirrors kelivo's
_iosNavRow`) stay as-is because they are provenance, not UI text.

已对齐（已提交，全模块门禁绿）—— 详情与批次表见 `memo-android/docs/PORTING.md`（唯一事实索引）：

聊天与基础 UI
- 顶栏 / 输入栏（frosted 圆角容器 + 左 Boxes/Globe/Brain/Hammer/Zap、右 Plus/Mic/ArrowUp）/ 消息头 / 气泡 / 消息操作
- MarkdownText（commonmark + GFM）+ ThinkingCard（思维链折叠）
- 侧边抽屉（搜索/历史/助手卡/日期分组/用户栏）+ 自绘 InteractiveDrawer（偏移滑入、scrim 0.12、仅边缘拖拽、按速度归位）
- 启动：最近会话（或新建）；临时聊天仅经开关；全局 ripple 关闭
- 顶栏统一（MemoTopBar，40+ 页面已转）、页面转场（PredictiveBack→FadeForwards）、输入框几何对齐 BasicTextField

模型与助手编辑
- 模型选择 sheet（搜索/收藏/provider chips + 可拖拽高度 0.4-0.8）+ ModelDetailSheet（编辑/创建双模 + Basic/Advanced/BuiltInTools 三 tab + 可拖拽高度 NestedScrollConnection）
- 助手列表页 + AssistantStore + seed + assistant_rows PK 修复；拖拽 animateItem+zIndex / 左滑 pane
- 编辑页骨架 + 分段条 + basic tab（聊天模型/背景/参数 sheet×4/头像 sheet/思考预算）+ 提示词 tab（系统提示词/消息模板/预设对话）+ 记忆/MCP/本地工具/快捷短语/自定义请求/正则/标签 tab
- 编辑页 tab 布局管理页 C（重排/隐藏/重置 + 提纲模式 + 单 tab 分段页，`AssistantTabLayoutState` 容器级共享）
- 显示设置 + 子页（ChatItemDisplay 13 / Rendering 8 / Behavior/Startup 20 / Image/MessageStyle/AutoRetry/Haptics）

对话增强与工具
- 搜索体系 S1–S4（定义/8+15 provider 引擎/设置/用量，共 23 个可运行 provider）
- 翻译页、世界书（页 + 注入引擎，5 注入位置/31 单测）、指令注入、快捷短语、记忆工具 M1/M2a/M2b/M2c + **Smart Add 去重合并 M2d-b**（tokenizer/提示词组装/四动作判定/批量，judge 走 `memory_model_v1`）、本地工具 L1 + 日历 L2、OCR F4、文档抽取 F3、附件 UI F2
- **记忆抽取 pipeline M2d-c**（gatekeeper/extractor/distiller + 单并发队列 + 水位 + 版本链折叠 + 自动调度；助手记忆 tab 的「整理」按钮与状态行）；流程追踪页（memory_trace）同期落地
- 多模态输入引擎 F1、MCP 基础与连接管理 MCP-1/MCP-2、助手 MCP sheet（输入栏 Hammer）
- **旧版（V1）记忆模式不移植（用户点名）**：原版留它是为兼容老数据，Memo 没有 → 设置页旧版开关/旧版只读页/旧版提示词行/legacy 工具定义全部删除；`assistant_memory_rows` + 备份写入保留（归档格式需要），但没有 UI 读它
- **记忆可见性修复**：`MemoryProviderV2.ensureLoaded()`（此前系统提示词的记忆块与助手记忆 tab 拿到空 store ⇒ 记忆既不注入也不显示）+ 列表/tab 统一用容器级 provider 并跟随 `version` + 助手记忆 tab 补齐可见/归档列表
- **实体键存储修复（M2d-a 记忆 + 存储-3 TTS）**：ENTITY 键（`memory_entries_v1`/`assistant_memories_v1`/`tts_services_v1`）走 `PreferenceRepository` 时被静默丢弃（read=null、write=no-op）→ 记忆改 `MemoryEntryRowDao`/`AssistantMemoryRowDao` 直写类型表（payload 权威 + 类型列投影自愈 schema CHECK、读-改-写），TTS 改 `PayloadEntityDao("tts_service_rows")`，`BackupRestorer` 对两个记忆表同步改为投影写入；ASR 的 `asr_services_v1` 是 PREFERENCE 键不受影响
- **悬浮语音播放器（用户点名「语音播放这个样式」）**：`TtsEngine`/`TtsPlaybackController`（分块朗读、暂停=停+重起当前块、±15s 定位、0.8–2.0 变速、200ms/字符估算的时间轴）+ 悬浮胶囊 1:1（双弧进度环/展开控制条/可拖动/自动收起），`TtsPlayer.init` 在 `MemoApplication.onCreate`
- 聊天周边：Select&Copy/WebView 预览/分享/BoundedLargeTextView、助手壁纸、推理预算全链路、清空/压缩上下文、记忆关于+种子、建议气泡、消息多选+导出（文本）
- 抽屉全局搜索模式、临时聊天三态、长按会话 sheet + 多选栏、流式扫光文字（用户点名，替代原版三点）、iOS 风格控件 + 触觉反馈 + Haptics

设置、系统与服务壳
- provider 管理页、语音服务/备份/赞助 UI 壳（BackupScreen/LocalSnapshotsScreen/SponsorScreen）+ TTS/ASR 编辑器全屏化
- **供应商域收官（2026-09-12 用户点名「把供应商这个部分全部移植」）**：详情页 AppBar（头像/测试/分享/删除）+ 配置 tab（无分隔线、整卡无前置图标〔原版 `_iosRow`〕、底部分段条 insets 修正）+ 头像五选一（`ProviderAvatar`/`BrandIconCatalog` 59 图标）+ **API 端点三选一 combobox**（`ApiPathField`：与同屏输入框同宽同高、字段里显示当前路径、点击出锚定下拉、选中变色不打勾；Anthropic Messages `/v1/messages` / Chat Completions `/chat/completions` / Responses `/responses`；详情页与添加供应商页共用，原版「Response API 开关 + 手填路径」已合并——**勿改回**）+ **Responses API 真接线**（`ResponsesApi`/`ResponsesDecoder` + `LlmRequest.useResponseApi`：input items/instructions/`max_output_tokens`/工具摊平/厂商 reasoning、`response.*` SSE 事件与 `call_id` 成对的上行 follow-up；8 处请求组装点填充）+ 获取模型选择面板（搜索/家族分组/整组增删）+ 测试连接对话框（选模型 + 使用流式 + 四态）+ 列表页多选导出面板；**供应商分组整块删除**（用户 2026-09-12：「把分组这个去掉吧 我感觉没有什么用」「去掉就彻底呀」——UI 与数据层一起删，见上「有意偏离」）
- **本机副本（备份子块 3）已落地**：保留策略/存储/调度/设置 + 本机副本页全接线（存一份/恢复/导出/置顶/删除，启动与回前台自动调度）
- **merge 恢复 + 备份提醒（备份子块 2/4）已落地（2026-09-12）**：`DatabaseSnapshotMerger`（ATTACH 快照、指纹去重、冲突整段换确定性 merge- id）+ `SettingsSnapshotMerger`（助手 avatar/background 本地优先、记忆内容去重、偏好本地有就不动）+ `BackupReminder`（五键调度 + 分钟 ticker + 抽屉到期横幅 + 滚轮时间选择）；恢复顺序修正为数据库先行、settings 后写；备份页 §2/§3 全接线
- **WebDAV 备份（备份子块 5）已落地（2026-09-12）**：`WebDavClient`（OkHttp + XmlPullParser：PROPFIND/MKCOL 逐段建目录/PUT/GET/DELETE + Basic auth + 多状态解析）+ 设置子页 `webdav_settings` + 备份页 §5 四行（设置/测试连接/恢复远端列表 sheet/立即备份）；配置键 `webdav_config_v1`，默认目录品牌化为 `memo_backups`
- 日志三件套（收尾-6：LogPayloadElider/LogRedactor/RequestLogger/FlutterLogger/ContextLogger/LogBootstrap，64 单测；**2026-09-11 三个 tab 全部通电**：上下文日志补齐组装侧打标签 + `ContextLogAssembler`，应用日志接上 SSE 恢复/provider 解码/后台任务/抽屉/压缩/供应商/模型等失败路径，顺手修掉「指令注入从未进入请求」）
- 关于页闪退修复、智谱 400 修复（applyVendorReasoningKnobs）
- **网络代理真正生效（2026-09-12）**：`GlobalProxy`（ProxySelector 每连接读配置、http/https=HTTP 隧道 + Basic、socks5=SOCKS（无账户，已知偏差）、bypass 精确/后缀/CIDR）挂进容器 OkHttp；TTS「自动播放助手回复」接到回复完成后；记忆设置页「用户画像」行接到既有 `user_profile` 路由；WebDAV 子页补 statusBars insets
- **请求主链接线补完（2026-09-12，接线审计）**：采样参数 temperature/topP/maxTokens（LlmRequest.topP + 三客户端，Claude thinking 只在 0.95-1.0 下发 top_p）、自定义请求三层合并 `CustomRequestMerger`（assistant/provider/model 的 headers+body，x-conversation-id 保护）、auto_retry_options 按请求实时读、消息模板+时间后缀+正则 send/visual（AssistantRegexApplier）+预设对话注入新会话、selected_model_v1 进 fallback 链、回车发送/重生确认开关、两个读写格式 bug（background mode 读端、readBoolPref "1"/"0"）——**完整审计清单（已修/待接按批）在 PORTING.md §5.12，剩余项目按渲染/输入/聊天行为/语音/模型五批推进，行不删
- **设置全站分类化（2026-09-09 用户点名）**：偏好主页 17 行拆 5 组、五个偏好子页/触感页行内分组、关于页（应用信息/社区与链接）、统计页（数据概览/排行榜）、网络代理页（代理设置/连接测试）、存储主页（空间总览/存储分类）——统一 SectionHeader + SectionCard
- **设置行 tip 规范（2026-09-09 用户点名；2026-09-12 位置改口）**：不裸排提示词，一律 ⓘ + 浮动气泡；**ⓘ 紧跟标签文字**（不是行尾/开关那侧）——用 `SettingsUi.kt` 的 `TipHuggingLabel`（收尾-7）
- **存储功能补全（用户点名）**：上传管理器=原项目形态（来源筛选/排序/3 列缩略图网格/点击预览/长按选择/批量删除）、用量条 10 分类 10 色（撞色修复）、LOGS「查看日志」+ LOCAL_SNAPSHOTS「管理副本」入口
- **图片查看器补全（用户点名）**：底部毛玻璃功能栏（保存/分享/镜像×2/旋转×2）+ 每图独立变换状态（收尾-9）

待移植 / 剩余（仅以下；**2026-09-12 清理过一轮旧账**，M2d 记忆收尾 / C tab 布局页 / S4 用量卡 / 消息模板·预设对话卡都已落地，不再列）
- 真机 API-key 聊天冒烟验证（设备已连，待跑；用户已在真机上跑 DeepSeek/GLM 聊天）
- MCP-3：OAuth 授权流程、会话内 MCP sheet、STDIO 传输（桌面专属不移植）
- 备份剩余（§5.10 子块 7~8）：前向兼容闸门（完整版）/ Cherry·Chatbox 导入（子块 1~6 已通）
- **§5.12 五个接线批**（设置写了但没消费）：渲染批 ✅ **已收官**（气泡风格整页〔样式/助手·用户覆盖 JSON/贴合内容/按段拆分，frosted 只画 tint〕、用户·助手 Markdown 开关、代码块 chrome〔折叠·行数·移动端换行·底部渐隐·Copy·HTML 预览〕、**$LaTeX 数学渲染**〔照 RikkaHub：`jlatexmath-android` fork + `markdown/Latex.kt`，块级 MathBlock／行内 InlineTextContent，两开关接线〕）/ 输入批 ✅（聊天字体大小、App·代码字体加载、自动滚动、输入框不透明度、长粘贴转文件、图片画质管线；裁剪器与 markdown 图片链接未做）/ 聊天行为批 ✅（重新生成删后续、Fork 保版本、编辑助手消息保思考·工具卡、消息导航三态、会话列表日期、侧栏保持展开×2、删除后新建会话、启动时新建会话〔默认开，按原版〕）/ 语音批 **基本收官**（用户 2026-09-12「按照原项目 都做了吧」：网络 TTS 11/12 家 HTTP + 合成缓存 + 播放引擎 + 保存音频；云端 ASR **7 种全通**——system/mimo/step/openai_realtime/dashscope/volcengine〔照 RikkaHub `speech/` 模块移植〕/qwen_audio + `AudioRecord` 采集 + 运行时授权；⬜ 只差 **qwenAudio TTS 的 WebSocket**，sherpa_onnx 桌面向不移植）/ 模型批 ✅（apiModelId wire 映射〔WireModelIdClient〕、模型层 headers·body〔已核对形状〕、**contextWindow 已接线**〔模型编辑页 Advanced 的「上下文长度」→ opencode 压缩阈值基准〕；builtInTools 门控随厂商内置工具本体）
- **`applyContextLimit` 未实现**（用户 2026-09-11「先留着」）：`assistant.limitContextMessages`/`contextMessageSize` 的按条数裁剪在请求链路上缺失（`clearContextLabel` 会显示配置值但从不生效）——会改变发给模型的消息数，要做时单开一批 + 用户确认
- **上下文压缩机制＝opencode 阈值机制**（用户 2026-09-11「这个上下文压缩这个机制这个部分 我们要改 不用原项目这个」→ 2026-09-13「改成 opencode 那个压缩阈值来压缩」）：估算 tokens 超过「上下文窗口 − max(输出预算, buffer)」时，发送前把较早的上下文归纳成**锚定摘要检查点**插进**同一个会话**（`CompactionPart`，`boundaryOrder` 之前的消息不再进请求，检查点整条替换成 `<conversation-checkpoint>` user 轮次），最近 tokens 原样保留。纯逻辑 `core/common/SessionCompaction.kt`（照 `opencode/packages/core/src/session/compaction.ts`），设置键 `context_compaction_{auto,keep_tokens,buffer,window}_v1`，模型级上下文长度写 `modelOverrides[modelId].contextWindow`（模型编辑页 Advanced）。**不要再改回「新建会话 + 摘要作首条消息」那套**（`CompressText`/`Utf16SafeCut` 旧机制已删）；唯一未接：provider 报 context-length 后的自动压缩重试
- 语音剩余：**只剩 qwenAudio TTS 的 DashScope WebSocket**（12 家网络 TTS 里唯一没接的；其余 11 家 HTTP + 合成缓存 + 播放引擎 + 悬浮播放器「保存音频」都已落地）。ASR 7 种全部通（system/mimo/step/openai_realtime/dashscope/volcengine/qwen_audio），sherpa_onnx 是桌面离线件不移植
- ~~收尾-5：Toast 用 sonner 替换~~ **已关闭**（用户 2026-09-13「这个不用做了 已经弄好了toast这个部分」）：保留手撸 `MemoSnackbar`（core:ui/snackbar/），不引 sonner
- UI-7i 图片导出（widget 截图引擎，文本导出已完成）
- S5：kelivo 内置搜索（上游端点+内置令牌，按品牌规则不移植，低优先）
- 图片查看器桌面专属件（复制钮/缩放三钮/拖拽关图/桌面翻页箭头——compact=手机端不含，低优先）

- Build env on this machine: system `JAVA_HOME` points at jdk-13 (breaks AGP) —
  always pin `JAVA_HOME=/c/Program Files/Java/jdk-21.0.10` and
  `GRADLE_USER_HOME=D:/DevCache/.gradle` (the default `~/.gradle` is under a
  Chinese username and Gradle's `@argfile` worker classpath becomes unreadable
  on CP936 JVMs, which kills `testDebugUnitTest` with `ClassNotFoundException:
  GradleWorkerMain`).
- Quality gate (all must pass before commit):
  ```bash
  cd memo-android && bash tools/quality_gate.sh
  ```
  which runs aggregate `lintDebug` + `testDebugUnitTest` across **all** modules,
  `:app:assembleDebug`, then two presence checks: every module with sources must
  have at least one test, and all four generators must produce no diff.
  CI `.github/workflows/android-pr-check.yml` enforces the same gates.
- Generated resources are committed and must stay in sync: ARB→strings via
  `tools/arb_to_android.py` (brandifies `Kelivo`/`kelivo`→`Memo`/`memo`), drift
  schema→SQL via `tools/drift_schema_to_sql.py`, palettes via
  `tools/palettes_gen.py`, settings keys via `tools/settings_keys_gen.py`.
- Modules: `app` (UI/nav/container), `core:common`, `core:ui` (theme + l10n),
  `core:data` (SQLite DAO + settings), `core:llm` (OkHttp SSE + OpenAI/Claude/
  Gemini clients), `feature:*` (assistant/chat/settings/utility — skeleton so far).
- Tests live under each module's `src/test/` (JUnit 4). `core:llm` has
  MockWebServer integration tests for SSE decoding and retry policy.

## Architecture

- **Feature-based structure**: `lib/features/<feature>/` with `pages/`, `widgets/`, `models/`, `utils/` subdirectories.
- **Desktop / mobile split**: most UI pages have separate desktop and mobile layouts (e.g. `home_desktop_layout.dart` / `home_mobile_layout.dart`). Desktop-only code lives in `lib/desktop/`. Use `ResponsiveHelper` from `lib/shared/responsive/` to branch by screen type.
- **State management**: Provider (`lib/core/providers/`).
- **Database**: Drift (`lib/core/database/`). Schema versions tracked in `drift_schemas/`.
- **Localization**: ARB-based (`lib/l10n/`), English template (`app_en.arb`). Run `flutter gen-l10n` after editing ARB files and commit the generated output.

## Pre-commit checklist

All three must pass before committing:

```bash
dart format lib test                        # format changed files
dart analyze --fatal-infos lib test         # zero warnings, zero infos
flutter test                                # all unit tests green
```

CI (`pr-check.yml`) enforces the same gates on every PR.

## Benchmarks are not tests

`test/perf/*_bench.dart` print timings and contain no `expect()`, so they cannot
fail. They are named out of the default `_test.dart` glob and so are skipped by
`flutter test`. Run one explicitly:

```bash
flutter test test/perf/timeline_scroll_bench.dart
```

## UI guidelines

- **Use app-defined widgets** from `lib/shared/widgets/` and `lib/shared/dialogs/` instead of raw Flutter/Material widgets wherever an equivalent exists (e.g. `SectionCard`, `CustomBottomSheet`, `IosFormTextField`, `IosCheckbox`, `InteractiveDrawer`).
- **BottomSheet**: both Flutter's built-in bottom sheet and `CustomBottomSheet` are fine on mobile. Never use any bottom sheet on desktop — use a dialog or another interaction pattern instead.
- **Icons**: use `lucide_icons_flutter`, not `Icons.*` from Material.
- **Animations**: use `flutter_animate` / `animations` for motion.
- When building a new page, create separate desktop and mobile layouts unless the page is trivially simple. Wire them together via `ResponsiveHelper`.
- **Preserve the current Memo UI/UX** — the 1:1-ported visual style and interaction feel is intentional. Don't refactor or swap components for "modern patterns" / library upgrades / cleanliness without explicit approval; user prefers the current look. Visible-behavior changes (animations, transitions, gestures, spacing, color, type ramp, motion) need plan-then-confirm. Library/architecture swaps (e.g. swapping `MemoSnackbar` for `io.github.dokar3:sonner`) are allowed only when the *visible* UI/UX is preserved.

## Code style

- Do not preserve backward compatibility. Remove obsolete paths instead of adding compatibility layers, fallbacks, or migrations.
- Choose the simplest implementation that fully meets the current requirements. Avoid speculative abstractions, configuration, and indirection.
- Grow the system in layers. Start from the smallest version that works end to end, and add each new capability on top of a product that already works. Never trade a working product for unfinished complexity.
- Keep components modular and concerns clearly separated.
- Prefer established, well-maintained libraries when they reduce overall complexity or improve reliability. Do not reimplement common functionality without a clear reason.
- Lean on the dependencies already in the project before writing your own implementation or adding packages. Do not assume a library lacks a capability without checking its documentation and types.
- Make architectural decisions for the long term. Do not accept a stopgap that only works for now and is meant to be replaced later.

## Local dependencies

Several packages live under `dependencies/` and are referenced by path in `pubspec.yaml` (e.g. `gpt_markdown`, `mcp_client`, `flutter_tts`, `flutter_math_fork`, `downsize`). The analyzer excludes `dependencies/flutter_math_fork/**` and `dependencies/flutter_tts/**`.

## Useful commands

```bash
flutter pub get                             # install dependencies
flutter gen-l10n                            # regenerate l10n files
dart run build_runner build                 # regenerate Drift code
```
