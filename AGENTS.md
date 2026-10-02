# AGENTS.md

> **项目状态（2026-09-26 起）：Memo 是已完成并开源的独立原生安卓项目
> （https://github.com/3d-jq/memo），进入自主维护与自研功能阶段。**
> 移植期文档（PORTING.md / UI_AUDIT / CHAT_JANK_AUDIT）已删除；代码注释里引用的
> `PORTING §x.x` 用 git 历史查（2026-09-26 前的提交）。现行文档地图见
> `memo-android/docs/ARCHITECTURE.md` §6：DESIGN（设计语言）/ ARCHITECTURE（架构与工程约定）/
> ENGINEERING_HARNESS（工程纪律）/ superpowers/（功能 spec）/ CHANGELOG。

## Product overview

**Memo** 是独立的原生安卓 LLM 聊天客户端（Kotlin + Jetpack Compose，AGP 8.11.1 /
Gradle 8.14 / Kotlin 2.2.20，minSdk 26 / target 35），位于 `memo-android/`。
`namespace`/`applicationId` = `com.psyche.memo`，数据库 = `memo.db`。
模块：`app`（UI/nav/container/工具执行）、`core:common`（压缩/技能/日志纯逻辑）、
`core:ui`（主题+三份 strings.xml+markdown+iOS 风控件）、`core:data`（SQLite DAO/偏好/备份/
第三方导入）、`core:llm`（OkHttp SSE；OpenAI/Responses/Claude/Gemini 四客户端）、
`core:workspace`（proot 沙箱+PTY）。

- **品牌化**：任何用户可见字符串、标识、资源键、资产不得出现 kelivo 字样/链接/端点；
  更新端点用 `https://github.com/3d-jq/memo`。
- **SQLite DDL 是唯一生成物**：`tools/drift_schema_to_sql.py` →
  `core/data/src/main/assets/memo_schema_v3.sql`（门禁校验零 diff）；三份 strings.xml、
  色板、偏好键**直接手维**。
- **参考仓库（本机副本）**：**RikkaHub** `D:\program\.rikkahub-ref`（同型客户端，实现/修 bug
  第一参照，可直接搬，AGPL 同源）；**Eta** `D:\program\.eta-ref`（**PolyForm Noncommercial ⇒
  只借架构、不搬代码**）；上游 Flutter 对照 `D:\program\memo-upstream` 仅考古。
  别拿「参照没有」当拒绝修体验问题的理由；超出参照的改动在 CHANGELOG/提交信息里点名。

## 产品决策（勿回退，除非用户改口）

- **无供应商分组**（UI+数据层整删）——勿加回。
- **无工具审批**（审批面板/`requiresUserApproval`/Shield 状态位全拆）：把关 = 全局开关 +
  时间线可见 + 可停 + 提示词边界。工具清单唯一来源是各自 catalog（如
  `BrowserTools.catalogDefinitions()`），别抄第二份。
- **AI 输出＝ZCode 桌面端样式**（2026-10-02 用户「完全按照 zcode 的 AI 输出样式来」，取代
  2026-09-13 的扫光提示决策）：思考/工具调用都是无卡片紧凑行（`ui/chat/AgentTraceRows.kt`）。
  思考行流式＝「正在思考」+滚动摘要、完成＝「思考 · 持续了 N 秒」自动折叠、展开纯文本限高滚动；
  工具行＝图标+标题+状态词+计时（执行中跳秒/完成「· Ns」），点开行内展开详情（无弹层），
  展开态收 `ChatViewModel.expandedToolRows`；工具 payload 带 `startedAt/finishedAt`（老消息
  无键不显示时长）。勿改回卡片式思维链/详情弹层/扫光提示行；生成中呼吸星与压缩分隔线扫光保留。
- **上下文压缩＝opencode 阈值机制**（`core/common/SessionCompaction.kt`）：同会话插入锚定
  摘要检查点（`CompactionPart`），分母 = 模型级 `contextWindow` − max(输出预算, buffer)；
  呈现只画分隔线、摘要不进导出/多选/标题。**勿改回「新建会话 + 摘要首消息」**；
  输入栏上方常显细条已撤，别加回。
- **消息导出图片功能已整块撤掉**——勿加回（重做的正确起点见 git 历史 §5.31）。
- **自动重试出厂即开**（`AutoRetryOptions.enabled` 默认 true），状态码/触发词表照既定清单，
  勿把词表改回空表。
- **语音播放图标按消息归属**（按 `ownerId` 响应，暂停显示「继续」）；悬浮播放器 1:1 保留。
- **显示开关语义**：助手名字行照 `Assistant.useAssistantName` 取值（默认显示模型名）；
  「显示助手头像」键 `display_show_assistant_avatar_v1` 默认关。
- **Agent Skills**（照 RikkaHub）：技能名与 `use_skill` 的 path 都过 `SkillPaths` canonical 检查、
  保存 staging+rename、删技能同步清助手引用；工具卡标题「技能：<名>」。
- **沙箱工作区**（照 RikkaHub workspace）：记录走 `extension_entity_rows`（kind=workspace），
  **不能加表**；app 仓库包名 `com.psyche.memo.provider.workspace`；构建需 NDK 28.2.13676358
  （勿删，删了 `[CXX1101]`）。「常用环境」apt 预设装在各 rootfs，软件源默认清华 TUNA；
  `RootfsPatcher.DEFAULT_DNS_SERVERS` 已改国内可达（223.5.5.5 等），`resolv.conf` 会在目标列表
  变化时重写——勿改回「有 nameserver 就不动」。`adb am force-stop` 后 app uid 沙箱出网会被掐，
  手测网络前先 `am start`。
- **位置工具**（自写能力）：`get_current_location` 空参数、不走审批；权限 → 服务开关 →
  10 分钟内 last-known 秒回 → 实时 10 秒超时 → 回退过期缓存；运行时权限走
  `LocationPermissionService`（**带 90 秒超时**，否则后台生成挂死）；拿不到地址只给坐标。
- **Agent 浏览器**（参照 Eta 架构）：14 颗独立 `browser_*` 工具 + 会话级多标签（上限 5、
  恰 1 活动）+ 接管遮罩（**同屏二级页不是 Dialog**——window token 教训）；index+generation
  寻址、动作信封带页面证据（`page_changed:false` 就不许说成功）；登录态 app 全局保留，
  唯一清点是「清空并关闭」；文件上传只给用户；入口常驻「+」面板；开关在
  「设置 → 模型与服务 → 浏览器功能」（键 `agent_browser_enabled_v1`）。
- **主题**：预设集 = Memo 默认 + RikkaHub 7 套；预设走「原样表面」通道（页面底 =
  `surfaceContainer`、卡片 = `surfaceBright`、填充 = `surfaceContainerHigh`——勿改回 M3 默认
  关系）；设置分组标题跟随主题 primary。
- **旧版（V1）记忆模式不存在**——Memo 无老数据，勿移植。
- **长用户消息折叠/展开**：`CollapsibleUserBubble`（按高度封顶 8 行、展开态存
  `ChatViewModel.expandedUserMessages`——本地 remember 会被 LazyColumn 重置）。

## UI 规范

见 `memo-android/docs/DESIGN.md`（Apple 参考系四档圆角 token、组件基线、颜色纪律、例外清单）。
要点：圆角只用 `MemoRadius`（CARD 20 / INNER 16 / SMALL 10 / PILL 999）；颜色全部主题派生；
对话框/sheet 用 `MemoAlertDialog` / `MemoSheet` 共享壳；图标用 Lucide；**保持现有 UI/UX**，
可见行为改动（动画/转场/间距/色彩/动效）先方案后确认，库/架构替换不得改可见效果。

## 工程规则

### 构建环境（本机 pin）

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"   # 系统 JDK13 会挂 AGP
export GRADLE_USER_HOME=D:/DevCache/.gradle            # 中文用户名路径杀 GradleWorkerMain
cd memo-android && ./gradlew <task>
```

`:app:assembleDebug` 需 **NDK 28.2.13676358 + CMake 3.22.1**；`[CXX1101]` = NDK 缺失/损坏，
不是代码 bug。真机 OPPO PKB110（serial `SKIBZ955INZXDYEY`），adb 在
`D:\Android\Sdk\platform-tools\adb.exe`；装机后核 `lastUpdateTime`。

### 提交纪律

- 全量门禁 `bash tools/quality_gate.sh`（全模块 lint+test+assemble+DDL 零 diff+测试存在性）
  由外部流程/CI 跑；**本地：编译 + 定向/相关模块测试，绿了就本地提交。本地 git 不 push**
  （发布走 `memo-public/` 发布树）。
- `git add` 只圈自己改的文件；批量脚本改动后必须 `git diff --stat` 审删除行数
  （有过脚本删 3308 行的事故）——「往既有调用插参数」类活儿用逐点手工。
- 收工更新 `CHANGELOG.md`（对应 `versionName`）与相关 docs。

### 组合纪律（机器把守）

- **组合期零 IO**：`@Composable` body（含 `remember {}` 与参数表达式）禁止 SQLite/文件/
  ContentResolver。「每键读一次」走 `ui/AsyncLoad.kt` 的 `rememberLoaded`；`LaunchedEffect`
  里 DB 自带 `withContext(Dispatchers.IO)`。守卫 `CompositionThreadingTest`（豁免按文件+token
  注册并写理由）。读侧缓存已就位，别再加 `remember { db() }`。
- **消息行保持 skippable**：`compose_compiler_config.conf` 的 stable 声明别乱动；
  守卫 `ChatRowRecompositionTest`。
- **滚动三规则**：①「到底」滚末尾哨兵 `SCROLL_BOTTOM_ITEM_KEY`，滚动命令不许传
  `Int.MAX_VALUE` 当偏移（守卫 `ChatScrollOffsetTest`）；② 自动跟随必须同时有 `!pointerDown`
  守卫（只靠 interactionSource/snapshotFlow 会失效）；③ `scrollToItem(index)` 是对齐到视口
  **顶部**，贴底用 `requestScrollToItem(lastIndex, Int.MAX_VALUE)`，跟随禁用
  `animateScrollToItem`。

### 会话与生成生命周期

- **会话对象归容器**：`ChatViewModel` 不继承 androidx ViewModel，由
  `AppContainerImpl.chatSession(conversationId)` 持有（上限 12、挤最旧、`destroy()`）；
  作用域自有 `sessionScope`。页面销毁不断流。「进程被杀续跑」写 `generation_run_rows`。
- **中断随生成终止释放**：问询 pending 表按 `(conversationId, toolCallId)` 分键；**每条终止
  路径**（生成 finally / `stop()` / `releaseForReuse()` / `destroy()` / 前台服务超时）都走
  `releaseInterruptions()`；守卫 `AskUserInteractionServiceTest` / `ChatInterruptionTest`。

### 测试规范

- 测试在各模块 `src/test/`（JUnit4）；`core:llm` 有 MockWebServer 集成测试。
- Robolectric：`MainDispatcherRule`（Unconfined）+ 等真实信号，**不许**「真时钟轮询 +
  手泵 Looper」；Compose UI 测试（`ComposeUiTest`）例外（自管 Main 调度器），样例内容留在
  视口内（默认 320×470 px），要证「变高了」比 `boundsInRoot.height`。
- CI 跳过名单（根 `build.gradle.kts` `ciSkippedTests`，本地照跑）由 `CiSkipListTest` 守着，
  只收「只在 CI 卡」的类。

## Code style

- 不留向后兼容：删旧路径，不加兼容层/回退/迁移。
- 选能满足当前需求的最简实现；避免投机抽象、配置项与间接层。
- 分层生长：先端到端最小可用，再逐层加能力；不用未完成的复杂度换掉能用的产品。
- 组件模块化、关注点分离；优先用仓里已有的依赖与共享件（查过文档再断言「库没有」）。
- 架构决策面向长期；不接受「先凑合后重写」的停应变通。
