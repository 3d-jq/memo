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
- **记忆抽取 pipeline M2d-c**（gatekeeper/extractor/distiller + 单并发队列 + 水位 + 版本链折叠 + 自动调度；助手记忆 tab 的「整理」按钮与状态行）——仅 trace 分步记录未移植（流程追踪页仍空）
- 多模态输入引擎 F1、MCP 基础与连接管理 MCP-1/MCP-2、助手 MCP sheet（输入栏 Hammer）
- **旧版（V1）记忆模式不移植（用户点名）**：原版留它是为兼容老数据，Memo 没有 → 设置页旧版开关/旧版只读页/旧版提示词行/legacy 工具定义全部删除；`assistant_memory_rows` + 备份写入保留（归档格式需要），但没有 UI 读它
- **记忆可见性修复**：`MemoryProviderV2.ensureLoaded()`（此前系统提示词的记忆块与助手记忆 tab 拿到空 store ⇒ 记忆既不注入也不显示）+ 列表/tab 统一用容器级 provider 并跟随 `version` + 助手记忆 tab 补齐可见/归档列表
- **实体键存储修复（M2d-a 记忆 + 存储-3 TTS）**：ENTITY 键（`memory_entries_v1`/`assistant_memories_v1`/`tts_services_v1`）走 `PreferenceRepository` 时被静默丢弃（read=null、write=no-op）→ 记忆改 `MemoryEntryRowDao`/`AssistantMemoryRowDao` 直写类型表（payload 权威 + 类型列投影自愈 schema CHECK、读-改-写），TTS 改 `PayloadEntityDao("tts_service_rows")`，`BackupRestorer` 对两个记忆表同步改为投影写入；ASR 的 `asr_services_v1` 是 PREFERENCE 键不受影响
- 聊天周边：Select&Copy/WebView 预览/分享/BoundedLargeTextView、助手壁纸、推理预算全链路、清空/压缩上下文、记忆关于+种子、建议气泡、消息多选+导出（文本）
- 抽屉全局搜索模式、临时聊天三态、长按会话 sheet + 多选栏、流式呼吸点、iOS 风格控件 + 触觉反馈 + Haptics

设置、系统与服务壳
- provider 管理页、语音服务/备份/赞助 UI 壳（BackupScreen/LocalSnapshotsScreen/SponsorScreen）+ TTS/ASR 编辑器全屏化
- 日志三件套（收尾-6：LogPayloadElider/LogRedactor/RequestLogger/FlutterLogger/ContextLogger/LogBootstrap，64 单测）
- 关于页闪退修复、智谱 400 修复（applyVendorReasoningKnobs）
- **设置全站分类化（2026-09-09 用户点名）**：偏好主页 17 行拆 5 组、五个偏好子页/触感页行内分组、关于页（应用信息/社区与链接）、统计页（数据概览/排行榜）、网络代理页（代理设置/连接测试）、存储主页（空间总览/存储分类）——统一 SectionHeader + SectionCard
- **设置行 tip 规范（2026-09-09 用户点名）**：不裸排提示词，一律行尾 ⓘ + 浮动气泡（收尾-7）
- **存储功能补全（用户点名）**：上传管理器=原项目形态（来源筛选/排序/3 列缩略图网格/点击预览/长按选择/批量删除）、用量条 10 分类 10 色（撞色修复）、LOGS「查看日志」+ LOCAL_SNAPSHOTS「管理副本」入口
- **图片查看器补全（用户点名）**：底部毛玻璃功能栏（保存/分享/镜像×2/旋转×2）+ 每图独立变换状态（收尾-9）

待移植 / 剩余（仅以下）
- 真机 API-key 聊天冒烟验证（设备已连，待跑）
- MCP-3：OAuth 授权流程、会话内 MCP sheet、STDIO 传输（桌面专属不移植）

- 收尾-5：Toast 用 sonner 替换（严格保留现有 UI/UX，当前不接入，等移植完再启动）
- UI-7i 图片导出（widget 截图引擎，文本导出已完成）
- S5：kelivo 内置搜索（上游端点+内置令牌，按品牌规则不移植，低优先）
- 备份/语音功能落地（壳已建，功能走 RikkaHub data-sync + app 模块，下一批）
- 存储-4：本地快照 / 备份提醒 / WebDAV / S3 / 前向兼容闸门 / Cherry·Chatbox 导入（PORTING §5.10 子块 2~8）
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
