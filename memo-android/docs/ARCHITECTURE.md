# Memo 架构与工程约定

> Memo 是独立的原生安卓 LLM 聊天客户端（Kotlin + Jetpack Compose），
> 包名 / applicationId = `com.psyche.memo`，数据库 = `memo.db`。
> 本文描述「现在是什么」；历史沿革用 git 查（2026-09-26 前的提交含移植期细节）。

## 1. 模块地图

| 模块 | 职责 |
|---|---|
| `app` | UI、导航、`AppContainerImpl`（容器/会话持有）、工具执行、provider 域（TTS/ASR/记忆/MCP/浏览器/工作区仓库） |
| `core:common` | 纯逻辑：`SessionCompaction`（opencode 阈值压缩）、Skill 解析、日志脱敏 |
| `core:ui` | 主题（`MemoRadius` / `AppSemanticColors` / RikkaHub 预设）、三份 strings.xml、markdown 渲染、iOS 风控件 |
| `core:data` | SQLite DAO（schema 由 `tools/drift_schema_to_sql.py` 生成，门禁校验零 diff）、偏好、备份/恢复/合并、Cherry & Chatbox 导入、schema 迁移闸门 |
| `core:llm` | OkHttp SSE；OpenAI Chat Completions / Responses / Claude / Gemini 四客户端 + 重试策略 |
| `core:workspace` | proot 沙箱：文件系统 / rootfs 安装 / PTY（`termux_pty.cpp`，构建需 NDK） |
| `feature:*` | 骨架（助手/聊天/设置/工具域） |

## 2. 运行时要点（改动前必读）

- **会话对象归容器，不归 ViewModel**：`ChatViewModel` **不继承** `androidx.lifecycle.ViewModel`，
  由 `AppContainerImpl.chatSession(conversationId)` 按会话持有（上限 12，挤掉最旧且 `!isBusy` 的并
  `destroy()`）；作用域是自己的 `sessionScope = SupervisorJob() + Main.immediate`。
  页面/Activity 销毁不断流；「进程被杀续跑」该写 `generation_run_rows`，别用偏好键凑合。
- **中断（问询面板）**：`AskUserInteractionService` 按 `(conversationId, toolCallId)` 分键的容器级
  pending 表。**每一条生成终止路径**（`finally`、`stop()`、`releaseForReuse()`、`destroy()`、前台服务
  超时回调）都必须 `releaseInterruptions()`；`currentChatInterruption(..., generating)` 是兜底闸
  —— 没人等就不许用面板顶掉输入栏。
- **无工具审批体系**（2026-09-25 整块拆除）：把关 = 全局开关 + 时间线可见 + 可停 + 提示词边界。
  工具清单的唯一来源是各自 catalog（如 `BrowserTools.catalogDefinitions()`），别抄第二份。
- **上下文压缩**：`core/common/SessionCompaction.kt`（opencode 阈值语义）——
  估算 tokens > 上下文窗口 − max(输出预算, buffer) 时，同会话插入锚定摘要检查点
  （`CompactionPart`）；分母 = 模型级 `contextWindow`。呈现只画一条分隔线，摘要不进导出/多选/标题。
- **浏览器**：14 颗 `browser_*` 工具 + 会话级多标签（上限 5、恰 1 活动）+ 接管遮罩
  （`ui/chat/BrowserOverlay.kt`，同屏二级页**不是 Dialog**——§5.31 的 window token 教训）。
  模型只见 index + generation 寻址；每颗调用信封带页面证据（url/title/generation/page_changed）。
  登录态 app 全局保留，唯一清点是「清空并关闭」。

## 3. 组合纪律（机器把守）

- **组合期零 IO**：`@Composable` body（含 `remember {}` 与组合期参数表达式）里禁止
  SQLite / 文件 / `ContentResolver`。「每键读一次」走 `ui/AsyncLoad.kt` 的
  `rememberLoaded`；`LaunchedEffect` 里的 DB 操作自带 `withContext(Dispatchers.IO)`。
  守卫：`CompositionThreadingTest`（逐文件逐 token 豁免，须写理由）。
  读侧缓存已就位（`PreferenceRepository` 行缓存 / `AssistantCache` / 启动预热），别再加 `remember { db() }`。
- **消息行必须 skippable**：`compose_compiler_config.conf` 声明 stable 的类型别乱动；
  `ChatRowRecompositionTest` 断言「碰列表/翻标志不重组合消息行」。
- **滚动三规则**：① 「到底」滚末尾哨兵 `SCROLL_BOTTOM_ITEM_KEY`，滚动命令不许传
  `Int.MAX_VALUE` 当偏移；② 自动跟随必须同时有 `!pointerDown` 守卫，贴底用
  `requestScrollToItem(lastIndex, Int.MAX_VALUE)`（`scrollToItem(index)` 是对齐到视口顶部！）；③ 跟随别用
  `animateScrollToItem`。守卫：`ChatScrollOffsetTest`。

## 4. 构建环境（本机 pin）

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"   # 系统 JDK 13 会挂 AGP
export GRADLE_USER_HOME=D:/DevCache/.gradle            # 中文用户名路径会杀 GradleWorkerMain
cd memo-android && ./gradlew <task>
```

- `:app:assembleDebug` 需 **NDK 28.2.13676358 + CMake 3.22.1**（workspace 的
  `termux_pty.cpp`）；`[CXX1101]` = NDK 缺失/损坏，不是代码 bug。
- 唯一生成物：SQLite DDL（`tools/drift_schema_to_sql.py`）；三份 strings.xml / 色板 / 偏好键手维。
- 真机：OPPO PKB110，serial `SKIBZ955INZXDYEY`，adb 在 `D:\Android\Sdk\platform-tools\adb.exe`；
  装机后核 `dumpsys package com.psyche.memo | grep lastUpdateTime`。
  **日常装机装 release 变体**（`:app:assembleRelease`，签名走本仓 `keystore.properties`）——
  debug 变体的 applicationId 带 `.dev` 后缀，会装成并列的第二个 app（用户手机上的正主是
  release 的 `com.psyche.memo`）。

## 5. 质量约定

- 全量门禁 `bash tools/quality_gate.sh`（全模块 lint + test + assemble + 两道存在性检查）
  由外部流程/CI 跑；**本地提交前自检 = 编译 + 定向/相关模块测试**，绿了就本地提交。
- 本地 git **不 push**（发布走 `memo-public/` 发布树 + GitHub Releases）。
- 测试在各模块 `src/test/`（JUnit4）；Robolectric 规范：`MainDispatcherRule`（Unconfined）+
  等真实信号，**不许**「真时钟轮询 + 手泵 Looper」；Compose UI 测试例外（自管 Main 调度器），
  样例内容留在视口内（Robolectric 默认 320×470 px）。CI 跳过名单由 `CiSkipListTest` 守着，
  只收「只在 CI 卡」的类。

## 6. 文档地图

- `AGENTS.md`（仓根）— 工作规则与产品决策，每会话加载。
- `docs/DESIGN.md` — 设计语言（Apple 参考系 token / 组件基线 / 例外清单）。
- `docs/ARCHITECTURE.md` — 本文。
- `docs/ENGINEERING_HARNESS.md` — 工程纪律（事实唯一所有者 / fail loud / 机器检查）。
- `docs/superpowers/{specs,plans}/` — 功能 spec 与实施计划（现行：agent-browser）。
- `CHANGELOG.md` — 版本变更（对应 `versionName`）。

## 7. 参考仓库（本机副本）

- **RikkaHub** `D:\program\.rikkahub-ref` — 同型 LLM 客户端，功能实现的第一参照（可搬，AGPL 同源）。
- **Eta** `D:\program\.eta-ref` — 系统级 Agent；**PolyForm Noncommercial ⇒ 只借架构、不搬代码**。
- 上游 Flutter 对照 `D:\program\memo-upstream` — 仅考古。
