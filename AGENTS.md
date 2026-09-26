# AGENTS.md

> **2026-09-24 起项目状态：Memo（`memo-android/`）是与上游 Flutter 完全解耦的独立原生安卓项目。**
> 上游 Kelivo 的 Flutter 源码已移出本仓（对照副本在 `D:\program\memo-upstream\`，仅用于查证
> PORTING 文档里引用的 Dart 路径）。文案（三份 strings.xml）**直接手维**，不再由
> `lib/l10n/*.arb` 生成（`tools/arb_to_android.py` 已随上游移出）。RikkaHub / Agora 仍是
> 实现层面的参考仓库；下文的历史约定与 PORTING 索引继续有效。

## Project overview

Kelivo is a cross-platform LLM chat client built with Flutter, targeting iOS, Android, macOS, Windows, and Linux. Package name is `Kelivo` — imports use `package:Kelivo/...`.（历史信息：上游 Flutter 源码已于 2026-09-24 移出本仓，见顶部状态说明。）

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
don't re-derive from scratch unless RikkaHub doesn't cover the case. **2026-09-24 起
上游 Flutter 源码不再在本仓内**：1:1 奇偶校验改为「与已发布版本/用户期望对齐」，
新行为按用户指示（Agora/RikkaHub 参照）推进，不再以「Flutter 代码怎么写」为唯一依据。

## 有意偏离原版的地方（勿"修回"，除非用户改口）

这些地方**故意**与原版不同（**完整清单见 `memo-android/docs/PORTING.md` §5.11**，含平台差异与踩坑）：
- **位置本地工具是安卓侧自写的能力**（用户 2026-09-21「加一个位置获取的本地工具吧」+「他这个项目我用过是没有问题的」）：上游 `locationSupported` 是 iOS-only，安卓走不到那条通道。接口照上游（名字 `get_current_location`、**空参数**、上游那段英文描述、**不走审批** —— 上游那份 `requiresUserApproval` 名单已于 2026-09-25 整块拆除，见下），执行在 `provider/LocationTool.kt`：权限 → 定位服务开关 → 10 分钟内 last-known 秒回 → 实时 10 秒超时 → 回退过期缓存 → 才报错；逆地理用平台 `Geocoder`，**拿不到地址只给坐标**。运行时权限走新增的 `ui/chat/LocationPermissionService`（形状照问询服务，**但带 90 秒超时**，否则后台生成会被挂死）。详见 PORTING §5.35
- **消息列表「显示助手头像」是 Memo 新增的开关**（用户 2026-09-23「在偏好设置里面的聊天项显示加一个显示助手头像的功能吧」）：上游只有助手头像**选择器**，没有消息头像开关。键 `display_show_assistant_avatar_v1`（默认关，关闭时完全维持上游行为），落在显示设置 →「聊天项显示」，渲染在 `MessageRow` 的消息头。判定集中在 `ui/DisplayPrefs.kt`
- **体验类修复允许超出上游**（用户 2026-09-20「按照你的改吧 我们现在在修复体验 上游也没有做好 我们要做好」）：上游没有中文错误分类、上游的 HEIC 靠 `image_picker` 插件转好、上游不管列表帧率——都不再是不修的理由。默认仍是 1:1，但**每一条超出上游的改动都要在 PORTING 里点名**（现有清单：§5.32 的中文错误分类＋带内错误提示＋HEIC 转码＋代码块高度过渡）。别再拿「上游没有」当拒绝依据，也别悄悄加
- **旧版（V1）记忆模式不移植**（用户点名：Memo 无老数据）
- **供应商分组整块删除**：UI（详情页分组行 / 列表分组头折叠 / 移动分组钮 / 分组管理页与路由）与数据层（`ProviderGroup`、`ProviderGroupLogic`、三个分组偏好键、备份里的 `provider_groups_v1` 实体）全删——用户 2026-09-12「把分组这个去掉吧 我感觉没有什么用」「去掉就彻底呀」。**勿按原版加回来**
- **流式等待提示＝扫光文字**：`ThinkingShimmerText`（主题色轮换短语 + 扫光，列表末尾单独一行靠左），替代原版三点脉动（`LoadingIndicator`）。用户点名改；工具卡里的小三点保持原版。**勿按原版修回三点**。2026-09-13 用户又要求可自定义 → 显示设置 → 渲染 → 「流式等待提示」三行（字号 10–28sp／颜色跟随主题或 `#RRGGBB`／提示词逐行编辑），键 `display_thinking_indicator_font_size_v1`·`_color_v1`·`_phrases_v1`（本工程新增，原项目没有这个指示器）
- **⏸ `memo-android/docs/UI_AUDIT_2026-09-12.md` 是用户自己的 UI/UX 审计报告，用户说「先不做」**：不要当待办自行开工、也不要删
- **语音播放图标按消息归属**：原版 `chat_message_widget.dart:3253-3291` 用全局 `isActive`，读一条消息会让**所有**消息显示停止、且暂停不可见；我们按 `ownerId` 只让被朗读的那条响应（暂停显示"继续"）——用户实测后要求
- **搜索引用胶囊尺寸**：原版 20dp/12sp/20% 底，用户要求缩小一档（16dp/10sp/16%，全圆）
- **输入栏样式**：保持 kelivo 原样，但最小高改为 64dp（用户要求）；其余参数勿动
- **上下文压缩＝opencode 阈值机制**（用户 2026-09-13「改成 opencode 那个压缩阈值来压缩」）：算 `estimate(system+messages+tools)`，超过「上下文窗口 − max(输出预算, buffer)」时在**同一会话**里插入锚定摘要检查点（`CompactionPart` + `<conversation-checkpoint>`），不再「新建会话 + 摘要作首条消息」——**勿改回原版**；详见下面「上下文压缩机制＝opencode 阈值机制」条。**呈现**（同日用户点名）：压缩只在对话里显示一条分隔线（压缩中＝扫光文字、完成＝静态），不弹对话框；**摘要不显示在对话界面**（不画气泡/不进导出/多选/标题/总结/记忆）；上下文占用只在「上下文管理」sheet 里看（占用卡：占比 + `约 12k / 108k tokens`，分母＝自动压缩阈值）——**输入栏上方的常显细条用户当日已要求撤掉，别再加回来**
- **品牌化**：无 kelivo 字样/链接/端点；归档建议名 `memo_backup_<stamp>.zip`、本机副本 `memo-snapshot-<nanos>.zip`
- **内置主题集＝Memo 默认 + RikkaHub 7 套预设**（用户 2026-09-13「我们这个八个效果不好，用 RikkaHub 那个主题，他那个更全面；我们这个默认也要保留，主题按照我们这个 UI 和 UX 不改」→ 2026-09-14「跟着人家一比一做 不然做出来不好看」）：列表在 `RikkaHubPresets.kt` 的 `themeChoices`，主题选择页＝RikkaHub 那种**四列彩色色卡**（1:1 上游 `PresetThemeButtonGroup`：48dp 圆、`primaryContainer` 底 + 双象限 + 中心 primary 圆点/选中打勾、名字用主题色居中），Memo 旧 8 套不再列出但 id 仍可解析；预设走「原样表面」通道，角色映射**照真机取色**：**页面底 = `surfaceContainer`、卡片 = `surfaceBright`、填充 = `surfaceContainerHigh`** —— 勿改回 M3 默认关系（那样页面白/卡片黄，正好做反）；**设置页分组标题跟随主题色** `primary`（判据集中在 `SettingsUi.kt` 的 `settingsSectionHeaderColor`，用户 2026-09-14「分类的字的颜色没有跟着主题走呀 rikkhub就可以呀」；照 RikkaHub `CardGroup.kt:157`）——勿改回 `onSurface@80%`，详见 PORTING §4-44 与 §5.11
- **Agent Skills 是照 RikkaHub 新增的能力**（原版 Flutter 没有 skill 系统）：技能 = `<filesDir>/skills/<名>/SKILL.md`（frontmatter 必填 `name`/`description`），`use_skill` 工具 + `<available_skills>` 系统提示词块 + 助手 `enabledSkills` + 设置→技能 管理页 / 技能详情页 / 助手编辑页「技能」tab。**工具名与工具描述逐字照上游 `SkillsTools.kt`，界面外壳用 Memo 风格**（`MemoTopBar`/`SectionCard`/长按操作面板），不搬上游的 FAB + 大标题栏；三种导入方式齐（手动粘贴 / 文件 .md·.zip / GitHub 仓库），并**内置 skill-creator**（抓取脚本 `tools/fetch_bundled_skills.py` → `assets/skills/`，启动时只播一次进 `filesDir/skills`；上游没有内置技能，这是我们的加法）。三条边界勿松：技能名与 `use_skill` 的 `path` 都过 `SkillPaths` 的 canonical 检查、保存走 staging+rename、删除技能同步清助手引用。**工具卡标题照上游 `UseSkillToolUI.title`**：`技能：<技能名>`（带 path 追加 ` / <路径>`）+ `Lucide.Puzzle` 图标 —— **别落回默认的「调用工具 use_skill」**（用户 2026-09-14「你看看 rikkhub 就是显示加载 skill」）。详见 PORTING §4-45 与 §5.11
- **Agent 浏览器是超出上游的新增能力**（2026-09-25 用户点名，2026-09-26 v2 拆工具+补标签；上游 kelivo 与 RikkaHub 都没有内嵌浏览器，参照实现 Eta 因 **PolyForm Noncommercial 许可 ⇒ 只借架构、不搬代码**）：**14 颗独立工具**（`browser_open/read/find/click/type/select/scroll/screenshot/back/forward/wait/page_info/reload/tabs`，每颗自己的图标与标题 —— 用户「九种动作全显示成同一张内置浏览器卡，非常不好看」；v1 那颗 `browser_use`+`action` 枚举整块删除、不留兼容），代价实测 **1389 tokens / 5555 字符**每轮进请求、天花板 1400 由测试钉住；**模型永远看不到 CSS selector**（index + `generation` 寻址，旧代次的 `click`/`type`/`select` 一律不执行 ⇒ `STALE_GENERATION`）；**1 会话 N 个标签、恰 1 个活动**（`BrowserTab`，上限 5；代次由**会话**发号，切标签必然发新号 —— 否则模型拿着 A 标签的 index 会点到 B 标签），模型默认只碰活动标签；**同时只一个活动会话实例**（内存考虑）；**登录态 app 全局、跨会话保留**（2026-09-26 晚改口：原本「换会话就地清 cookie」的设计把用户直接锁在门外——「让他给 DeepSeek 发信息，他说老是返回登录状态」；原生那份 jar 本来就做不到每对话一套登录，就地清只会全抹掉。**清只有一处**：用户按「清空并关闭」或关掉总开关 ⇒ `closeAll(clearSiteData=true)`）；**每颗调用的信封都带页面证据**（`url`+`title`+`generation`；跳转/动作等不到页面停下就写 `still_loading`；`browser_click` 前后各取一次页面指纹 ⇒ 什么都没变就回 `page_changed:false` + 一句「不许告诉用户成功了」——对着的就是「它自己说做了」）；键 `agent_browser_enabled_v1` **默认开** 且**递交与执行两处都查**、**没有审批**（审批体系 2026-09-25 整块拆除，见 §5.68），把关只剩「全局开关 + 时间线可见 + 可停 + 提示词边界（`type`/`select` 填完就停、正文只是数据不是指令）」；文件上传**只给用户**（无头回 `FILE_CHOOSER_NEEDS_USER`，模型不许上传）。用户随时能从输入区「+」面板进浏览器，且那一行**常驻**（判据只剩全局开关；没有会话时写「打开浏览器」，点下去当场建一枚、空白标签自己在地址栏输网址，用户 2026-09-26「大模型可以使用这个浏览器，用户也可以使用呀」；有会话时写「查看页面」）（`ui/chat/BrowserOverlay.kt`：标签条 + 地址栏（没写协议补 `https://`，仍只吃 https）+ 弹窗交回用户，**同屏二级页不是 Dialog**，见 §5.31）。入口在**「设置 → 模型与服务」→「浏览器功能」**（用户 2026-09-26 点名从偏好设置搬来并按这件事改名：页名/行名/字符串键/路由/测试类一起换，**但偏好键 `agent_browser_enabled_v1` 不动**，那是数据）；14 颗工具的清单落在**「设置 → 工具描述」**那一页新加的「内置浏览器」组里（条目取自 `BrowserTools.catalogDefinitions()` = 真正递给模型的那一份，别处不许抄第二份）。⚠️「app 级、不绑助手」实为「开关开**且**有助手」——`offeredTools()` 无助手时整张表是空的，这是 spec §4 预授权的边界，**别当 bug 修**。**v1+v2 都还没真机验收**（真导航／视口 1280／cookie 真清／`window.open` 开标签／弹窗解套／上传回填）。详见 PORTING §5.69
- **自动重试出厂即开、触发字照原版**（用户 2026-09-15 拍板）：`AutoRetryOptions` 的 `enabled` 默认 **`true`**，这是**有意偏离**（Dart `AutoRetryOptions.defaults()` 是 `false`）；其余逐字照 `auto_retry_options.dart:41-96`——状态码 `{408,425,429,500,502,503,504,529}`、`DEFAULT_RETRY_KEYWORDS` 12 条、`DEFAULT_STOP_KEYWORDS` 11 条、`maxDelayMs` 30000。**勿把两个词表改回空表**（空了＝关键词触发永不命中，就是「触发条件没做完」那个 bug）；设置页与容器解码都引用 `AutoRetryOptions.DEFAULT_*`，别再抄副本。详见 PORTING §5.11
- **开源准备（2026-09-21，用户「我可能要开源 把准备工作做好吧」）**：可发布树 = `memo-android/` 单独成仓（本机同级目录 `memo-public/`，含 `LICENSE` + `NOTICE.md` + `README.md` + 生成器输入 `upstream/`，细节见 PORTING.md §5.36）。原「品牌残留」一条已处理：`AboutScreen.kt` 的社区链接三行整组删除，上游 kelivo/RikkaHub 的署名改由公开仓 README+NOTICE 承担；要恢复一行「源码仓库」需用户给 Memo 自己的 URL。**已给**：`https://github.com/3d-jq/memo`，About 页那一行与更新端点都用它。仍留在库里的上游字样只有 Dart 源码路径溯源注释与 `kelivo.psycheas.top/update.json`（§5.37 说明"为什么换成 GitHub Releases"的事实引用）

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
- **聊天项显示 6 开关全部通电（2026-09-15）**：用户消息操作行（`display_show_user_message_actions_v1`）、助手名字行/时间戳（`display_show_model_name_v1`/`_timestamp_v1`）、`模型名 | 供应商`（`display_show_provider_in_chat_message_v1`，默认关）、Token 统计（`display_show_token_stats_v1`）、顶栏助手头像（`display_use_new_assistant_avatar_ux_v1`，默认关，28dp 头像加在标题行前）。**助手名字行照 CMW:2809-2813 取值**：助手开了「使用助手名字」（`Assistant.useAssistantName`，默认 false）才显示助手名，否则显示模型名（`modelOverrides[id].name` → `apiModelId` → 模型 id，`ChatTimeline.kt` 的 `messageModelDisplayName`，原料经 `rememberLoaded` 异步取）——**勿改回「永远显示助手名」**（那样这排开关没有意义）

对话增强与工具
- 搜索体系 S1–S4（定义/8+15 provider 引擎/设置/用量，共 23 个可运行 provider）
- 翻译页、世界书（页 + 注入引擎，5 注入位置/31 单测）、指令注入、快捷短语、记忆工具 M1/M2a/M2b/M2c + **Smart Add 去重合并 M2d-b**（tokenizer/提示词组装/四动作判定/批量，judge 走 `memory_model_v1`）、本地工具 L1 + 日历 L2、OCR F4、文档抽取 F3、附件 UI F2
- **记忆抽取 pipeline M2d-c**（gatekeeper/extractor/distiller + 单并发队列 + 水位 + 版本链折叠 + 自动调度；助手记忆 tab 的「整理」按钮与状态行）；流程追踪页（memory_trace）同期落地
- 多模态输入引擎 F1、MCP 基础与连接管理 MCP-1/MCP-2、助手 MCP sheet（输入栏 Hammer）
- **Agent Skills K1（2026-09-14）**：`core:common` 的 `SkillFrontmatterParser`（snakeyaml）/`SkillPaths`（canonical 边界）/`SkillStore`（列表·原子保存·删除·文件表）+ 工具面 `SkillTools`（`use_skill` 定义 / `<available_skills>` 系统提示词块挂 `ContextSource.skillPrompt` / 执行）+ `SkillImporter`（md 与 zip）+ `SkillGitHubImporter`（GitHub 仓库导入）+ `Assistant.enabledSkills` + 管理界面（设置→技能 列表页 / 技能详情页 / 助手编辑页「技能」tab）
- **模型问询在输入栏位置**（用户 2026-09-14「工具权限确认这个…应该出现在输入框那个位置」的参照截图）：`ui/chat/ChatInterruptionPanel.kt` —— 待答的 `ask_user_input_v0` 做成**底部面板占输入栏位置**（pending 时 `ChatInputBar` 暂时不显示），面板**一题一页**（`‹ n/N ›` + ×，`AskUserPanel`）。对话里的工具卡在 pending 时**只留一行状态**（「等待你的回复…」），交互控件已拿掉——**别按原版改回内联工具卡**；判据 `currentChatInterruption`（只看本会话）。**同族的工具审批半区已于 2026-09-25 整块拆除**（用户「工具的权限审批全部去掉」：`ToolApprovalService`、审批面板、工具卡上的 Shield 状态位与批准/拒绝按钮、`requiresUserApproval` 名单、workspace shell 的默认审批与详情页那张卡、MCP 工具的 `needsApproval` 全部删除），详见 PORTING §5.68
- **沙箱工作区 WS（照 RikkaHub 移植，2026-09-14 功能已齐）**：`core:workspace` = 上游 `workspace` 模块 1:1（文件系统/管理器/rootfs 安装器与补丁/proot 运行器/PTY 的 `termux_pty.cpp`）+ proot 二进制（arm64-v8a + x86_64，真机 spike 通过）；记录层走 schema 已有的 `extension_entity_rows`（`kind = "workspace"`，**不能给 workspaces 加表**，schema 是 drift 生成且门禁校验零 diff）；app 的 `WorkspaceRepository`（包 `com.psyche.memo.provider.workspace`，**别用 `com.psyche.memo.workspace`** 会 split package）+ 容器挂载 `/skills`、`/upload`。**工具面已通**（`workspace_read_file`/`write_file`/`edit_file`/`shell`，写文件走 shell `cat >`、读走 `exportRootfsFile`；持久可写区是 `/workspace` 与 `/tmp`，区外不再拦 —— 上游那里是「升级为需要审批」，审批体系拆除后连升级也没有了；替换三阶梯含 `block_anchor`）、**工作区提示词已注入**、**管理界面已落地**（列表页 / 详情页 / 助手编辑页「工作区」tab，绑定是每助手一份）、**交互式 PTY 终端已落地**（`workspace_terminal/{id}` 独立页，多 tab + 附加键栏 + 点 URL 开浏览器；会话独立于页面生命周期，容器级 `workspaceTerminalSessions`；一次命令与 PTY **共用同一份挂载表和 loader 环境**）。详情页结构照上游 `WorkspaceDetailPage`：**两个 tab 在底部**（基本 / 文件）、顶栏「导入文件 · 刷新 · 终端」、基本页两张卡（工作区信息 / 启用 Shell + 进度 + URL 对话框）—— 原第三张「工具审批」卡随审批体系一起删除、文件页段控（文件 / Rootfs）+ 路径栏 + 每条目一张卡（两行 + 溢出菜单导出·分享·删除）+ 空态，点文件按扩展名分流（文本编辑 sheet / 图片查看器 / 系统应用），rootfs 区只读 —— **首版自己猜的结构（顶部段控 + 单卡列表）已被用户否掉，别再改回去**。构建要 NDK（`ndkVersion = "28.2.13676358"`，**勿删**，删了 AGP 报 `[CXX1101]`）+ cmake 3.22.1，CI 已加 sdkmanager 安装步骤。**读图片也照上游做了**：payload 新增 `images` 键（`ToolCallPart`，无图不写键）、字节落 `<filesDir>/tool_images/`、UI 走 `ToolUiPart.attachedImages` 的图片横滚条、请求侧 `LlmMessage.toolImages` → OpenAI/Responses 的 tool 结果 content 换数组，且**只发给 `LlmRequest.imageInput`（= `ModelOverrideResolver.visionInput`）为真的模型**，否则补 `[Image output omitted: …]` 占位（Claude/Gemini 客户端本来就不上行 tool 消息，故未接）。**Batch 3 至此功能齐**。**「常用环境」一键装是本工程新增**（RikkaHub 没有环境安装入口；用户 2026-09-14「这个沙箱可以让用户选择 下载 node gitbash 这些常用的环境吗？」）：详情页「基本」一张卡 + 三条 apt 预设（Node+npm / Python3+pip / 常用命令行工具），装在**每个工作区自己的 rootfs**；`WorkspaceEnvironments.kt` 是纯逻辑（命令拼装 + `dpkg-query` 一次探测，**全装齐才算可用**、失败关闭），**软件源默认清华 TUNA**（可选中科大/阿里云/官方；官方 ports.ubuntu.com 真机实测只有 86 KB/s，是「装个 node 怎么这么慢」的根因 —— 用户 2026-09-14「好慢呀」「来点国内的镜像源呀」），安装拆三步（切源 → 刷索引 → 装）各自 10 分钟超时、**可取消**（取消后补 `dpkg --configure -a`）、**可卸载**（`remove --purge` + `autoremove`），**不占生成回合**（用户自点，等同于终端手敲）。**「+」面板也有工作区入口**（照 RikkaHub `WorkspacePickerListItem`：`BottomToolsSheet` 一行 → `WorkspaceSelectorSheet` 选工作区/管理；绑定仍是每助手一份）。⚠️ **沙箱内「换了源还是连不上」先查 DNS**：`RootfsPatcher.DEFAULT_DNS_SERVERS` 已改成国内可达的 `223.5.5.5/119.29.29.29/1.1.1.1`（实测 `8.8.8.8` 完全不通、`1.1.1.1` 不稳），且**我们自己生成的 `resolv.conf`（首行 `# Generated by Memo workspace.`）会在目标列表变化时被重写** —— 别改回「有 nameserver 就不动」，那样已装好的 rootfs 永远刷不到新 DNS（用户 2026-09-14「镜像源为什么用不了」「官方原也是失败」的真因）。另：`adb am force-stop` 过 App 之后 app uid 的沙箱出网会被 Android 掐掉，手测网络前先 `am start`。
- **旧版（V1）记忆模式不移植（用户点名）**：原版留它是为兼容老数据，Memo 没有 → 设置页旧版开关/旧版只读页/旧版提示词行/legacy 工具定义全部删除；`assistant_memory_rows` + 备份写入保留（归档格式需要），但没有 UI 读它
- **记忆可见性修复**：`MemoryProviderV2.ensureLoaded()`（此前系统提示词的记忆块与助手记忆 tab 拿到空 store ⇒ 记忆既不注入也不显示）+ 列表/tab 统一用容器级 provider 并跟随 `version` + 助手记忆 tab 补齐可见/归档列表
- **实体键存储修复（M2d-a 记忆 + 存储-3 TTS）**：ENTITY 键（`memory_entries_v1`/`assistant_memories_v1`/`tts_services_v1`）走 `PreferenceRepository` 时被静默丢弃（read=null、write=no-op）→ 记忆改 `MemoryEntryRowDao`/`AssistantMemoryRowDao` 直写类型表（payload 权威 + 类型列投影自愈 schema CHECK、读-改-写），TTS 改 `PayloadEntityDao("tts_service_rows")`，`BackupRestorer` 对两个记忆表同步改为投影写入；ASR 的 `asr_services_v1` 是 PREFERENCE 键不受影响
- **悬浮语音播放器（用户点名「语音播放这个样式」）**：`TtsEngine`/`TtsPlaybackController`（分块朗读、暂停=停+重起当前块、±15s 定位、0.8–2.0 变速、200ms/字符估算的时间轴）+ 悬浮胶囊 1:1（双弧进度环/展开控制条/可拖动/自动收起），`TtsPlayer.init` 在 `MemoApplication.onCreate`
- 聊天周边：Select&Copy/WebView 预览/分享/BoundedLargeTextView、助手壁纸、推理预算全链路、清空/压缩上下文、记忆关于+种子、建议气泡、消息多选+导出（文本）
- 抽屉全局搜索模式、临时聊天三态、长按会话 sheet + 多选栏、流式扫光文字（用户点名，替代原版三点）、iOS 风格控件 + 触觉反馈 + Haptics
- **思考卡展开态 + 流式自动跟随（2026-09-13 用户实测两处：思考中点击卡片展不开「会打架」／大模型输出时上滑被自己拉回底部）**：展开态以 `ChatViewModel.segmentExpanded` 为**权威态**（`ReasoningSegmentCodec.resolveExpanded`：新段 `!autoCollapse`、只有「结束转变」那一次采信流式侧的值）——**勿退回「按 handler 重建的 expanded 编码」**（§4.41）；自动跟随＝**按位置跟随 + 手指在屏上绝不程序化滚动**（位置判据照 RikkaHub `ChatList.kt:236-243/284`，旗标/容差/空闲计时照原版）——守卫必须同时有 `!pointerDown`（`Modifier.pointerInput` 的 `awaitFirstDown` 硬标志），**勿只靠 `interactionSource` 或 `snapshotFlow { isScrollInProgress }`**（前两版都因此失效），贴底用 `requestScrollToItem(messages.lastIndex, Int.MAX_VALUE)` —— **`scrollToItem(index)` 是把该条对齐到视口顶部，传末条下标≠到底**（长消息会跳到开头，看起来「视口往上跑」，2026-09-13 日志实证），**勿用 `animateScrollToItem`** 做跟随（§4.42）

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

待移植 / 剩余（仅以下；**2026-09-12 清理过一轮旧账**，M2d 记忆收尾 / C tab 布局页 / S4 用量卡 / 消息模板·预设对话卡都已落地，不再列；**2026-09-13 再清一轮**：真机聊天冒烟早已在进行——用户日常用 DeepSeek/GLM/智谱真机对话并按实测报 bug）
- ~~MCP-3 OAuth~~ **✅ 已收官**（2026-09-19，`provider/mcp/McpOAuth.kt`：RFC9728/8414 发现 + DCR + PKCE 授权〔系统浏览器 + 127.0.0.1 回环回调〕+ 刷新 + SSRF 全地址校验 + 连接期 Authorization 附带 + 编辑页「使用 OAuth 登录」；令牌存 `McpServerConfig.oauth`；**会话内 MCP sheet 上游无调用点是死代码不移植，STDIO 桌面专属不移植**）
- ~~备份剩余（§5.10 子块 7~8）~~ **✅ 已收官**（2026-09-19：子块 7 前向兼容闸门完整版＝`core/data/db/SchemaMigrations.kt` 双轴判定 + 同意对话框 + drift 1→2→3 原位迁移 + forwardCompatible 归一化〔只删未知表不动列，记 §5.25〕；子块 8 Cherry Studio / Chatbox 导入＝`core/data/backup/cherry/`+`chatbox/` 全格式解析〔含 LevelDB+Snappy+V8 值扫描直备读取〕，备份页两行死入口已接线）
- **§5.12 五个接线批**（设置写了但没消费）：渲染批 ✅ **已收官**（气泡风格整页〔样式/助手·用户覆盖 JSON/贴合内容/按段拆分，frosted 只画 tint〕、用户·助手 Markdown 开关、代码块 chrome〔折叠·行数·移动端换行·底部渐隐·Copy·HTML 预览〕、**$LaTeX 数学渲染**〔照 RikkaHub：`jlatexmath-android` fork + `markdown/Latex.kt`，块级 MathBlock／行内 InlineTextContent，两开关接线〕）/ 输入批 ✅（聊天字体大小、App·代码字体加载、自动滚动、输入框不透明度、长粘贴转文件、图片画质管线；裁剪器与 markdown 图片链接未做）/ 聊天行为批 ✅（重新生成删后续、Fork 保版本、编辑助手消息保思考·工具卡、消息导航三态、会话列表日期、侧栏保持展开×2、删除后新建会话、启动时新建会话〔默认开，按原版〕）/ 语音批 **基本收官**（用户 2026-09-12「按照原项目 都做了吧」：网络 TTS 11/12 家 HTTP + 合成缓存 + 播放引擎 + 保存音频；云端 ASR **7 种全通**——system/mimo/step/openai_realtime/dashscope/volcengine〔照 RikkaHub `speech/` 模块移植〕/qwen_audio + `AudioRecord` 采集 + 运行时授权；**qwenAudio TTS 的 WebSocket 也已接线**〔2026-09-19 `NetworkTts.qwenAudio`：run/continue/finish 生命周期 + 二进制音频回流 + PCM 转 WAV，12/12 全通〕，sherpa_onnx 桌面向不移植）/ 模型批 ✅（apiModelId wire 映射〔WireModelIdClient〕、模型层 headers·body〔已核对形状〕、**contextWindow 已接线**〔模型编辑页 Advanced 的「上下文长度」→ opencode 压缩阈值基准〕；builtInTools 门控随厂商内置工具本体）
- **`applyContextLimit` 已接线**（2026-09-13 用户「给我做完吧 我要使用了」）：助手的「限制上下文条数」在世界书注入后按原版语义裁剪（保留系统消息 + 最近 N 条 + 丢悬空 tool 消息，`app/llm/prompt/ApplyContextLimit.kt`）；同批补完**「管理总结」**（记忆 tab 列出/编辑/清除会话总结）与**「流式输出」**（`LlmClient.completeAsChunks` 非流式路径，`runGenerationLoop` 按 `assistant.streamOutput` 选源）——助手域三处遗留已全部关闭
- **上下文压缩机制＝opencode 阈值机制**（用户 2026-09-11「这个上下文压缩这个机制这个部分 我们要改 不用原项目这个」→ 2026-09-13「改成 opencode 那个压缩阈值来压缩」）：估算 tokens 超过「上下文窗口 − max(输出预算, buffer)」时，发送前把较早的上下文归纳成**锚定摘要检查点**插进**同一个会话**（`CompactionPart`，`boundaryOrder` 之前的消息不再进请求，检查点整条替换成 `<conversation-checkpoint>` user 轮次），最近 tokens 原样保留。纯逻辑 `core/common/SessionCompaction.kt`（照 `opencode/packages/core/src/session/compaction.ts`），设置键 `context_compaction_{auto,keep_tokens,buffer,window}_v1`，模型级上下文长度写 `modelOverrides[modelId].contextWindow`（模型编辑页 Advanced）。**不要再改回「新建会话 + 摘要作首条消息」那套**（`CompressText`/`Utf16SafeCut` 旧机制已删）；唯一未接：provider 报 context-length 后的自动压缩重试
- ~~语音剩余~~ **✅ 全部收官**（qwenAudio TTS WebSocket 已接线，ASR 7 种全通；sherpa_onnx 是桌面离线件不移植）
- ~~收尾-5：Toast 用 sonner 替换~~ **已关闭**（用户 2026-09-13「这个不用做了 已经弄好了toast这个部分」）：保留手撸 `MemoSnackbar`（core:ui/snackbar/），不引 sonner
- ~~UI-7i 图片导出~~ **❌ 已整块撤掉（2026-09-20 用户「导出图片这个功能去掉吧」）**：2026-09-19 曾落地（离屏 ComposeView 渲染 → ARGB 位图 → PNG 分享），真机一点就闪退两层：`Dialog` 无 window token（非 Activity 上下文）、`ComposeView` 找不到 ViewTree owner（`MainActivity` 是纯 `ComponentActivity`，owner 只装在 activity-compose 自己那棵树上；lifecycle 2.9.1 又把它对 Kotlin 藏了，没有公开 API 可写）。第二层在本工程补不干净 → `ChatExportImage.kt` 与多选导出栏第三颗钮、导出 sheet 图片行全部删除，**文本导出（.md/.txt）不受影响**。详见 PORTING §5.31（含以后重做的正确起点：在现有组合里画 + `GraphicsLayer.toImageBitmap()`）
- S5：kelivo 内置搜索（上游端点+内置令牌，按品牌规则不移植，低优先）
- 图片查看器桌面专属件（复制钮/缩放三钮/拖拽关图/桌面翻页箭头——compact=手机端不含，低优先）

- Build env on this machine: system `JAVA_HOME` points at jdk-13 (breaks AGP) —
  always pin `JAVA_HOME=/c/Program Files/Java/jdk-21.0.10` and
  `GRADLE_USER_HOME=D:/DevCache/.gradle` (the default `~/.gradle` is under a
  Chinese username and Gradle's `@argfile` worker classpath becomes unreadable
  on CP936 JVMs, which kills `testDebugUnitTest` with `ClassNotFoundException:
  GradleWorkerMain`).
- `:app:assembleDebug` also needs **NDK 28.2.13676358 + CMake 3.22.1** (the
  workspace terminal builds `core/workspace/src/main/cpp/termux_pty.cpp`).
  `[CXX1101] NDK at … did not have a source.properties file` means the NDK is
  missing/broken, not a code bug. On this machine the working NDK lives at
  `D:\android_sdk\ndk\28.2.13676358`; `D:\Android\Sdk\ndk\27.*` are empty stubs
  (only `.installer`), so it is junctioned to `D:\Android\Sdk\ndk\28.2.13676358`.
  CI installs the same two packages with `sdkmanager`.
- Quality gate (all must pass before commit):
  ```bash
  cd memo-android && bash tools/quality_gate.sh
  ```
  which runs aggregate `lintDebug` + `testDebugUnitTest` across **all** modules,
  `:app:assembleDebug`, then two presence checks: every module with sources must
  have at least one test, and the committed SQLite DDL must regenerate with no diff
  (`tools/drift_schema_to_sql.py` — the **only** generator left; 文案/色板/偏好键手维).
  CI lives in the published repo only: `memo-public/.github/workflows/android-pr-check.yml`
  enforces the same gates (开发仓的 `.github` 已随上游移出).
- **对话界面卡顿审计（2026-09-15，用户「点击到底部按钮对话界面会闪」+「对比原项目和 rikkhub」）**：完整报告 `memo-android/docs/CHAT_JANK_AUDIT_2026-09-15.md`，结论摘要见 PORTING §5.14。三条硬规则：① **滚动命令不许传 `Int.MAX_VALUE` 当偏移**（会被原样写进滚动位置 → 下一帧再夹一次 → 界面闪）；「到底」= 滚**末尾哨兵项** `SCROLL_BOTTOM_ITEM_KEY`（RikkaHub `ChatList.kt:374 ScrollBottomKey` 同款，`scrollTimelineToBottom()`），`ChatScrollOffsetTest` 守卫；② **消息行必须保持 skippable**（`app/compose_compiler_config.conf` 把 `UiMessage`/`ChatTimelineSettings`/`Assistant`/`MessagePart`/`Conversation`/`ProviderConfig` 声明 stable；`ChatRowRecompositionTest` + `ChatRecompositionProbe` 断言「碰列表/翻 `pointerDown`·`following`·`navVisible` 不重组合任何消息行」）；③ **组合期不许查库**（顶栏 `+` 的三态判据用 `ChatViewModel.tailLoaded + messages.isEmpty()` 的 `newActionToggleable`，别再回去调 `messageDao.count(id)`；`CompositionThreadingTest` 守卫）。刻意差异：到底按钮走瞬移不做动画（Compose 侧没有 `maxScrollExtent` 等价值）
- **会话对象归容器，不归 ViewModel（2026-09-25，用户点名「生成执行体从 ViewModel 搬到容器级 owner」，PORTING §5.62）**：`ChatViewModel` **不继承** `androidx.lifecycle.ViewModel`，实例由 `AppContainerImpl.chatSession(conversationId)` 按会话持有（上限 12，挤掉最旧且 `!isBusy` 的并调 `destroy()`），作用域是它自己的 `sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`。**别再改回 `viewModel(key=…, factory=…)`** —— 页面/Activity 一销毁，正在输出的回答就断；`ChatContent` 现在直接取容器那一份，`factory` 已删，`onCleared()` 已变成 `destroy()`（只在淘汰与 `deleteConversation()` 时调用）。仍然没有「进程被杀后续跑」：schema 里的 `generation_run_rows` 才是该写恢复状态的地方，别加偏好键的半套方案
- **生成中的「打断请求」必须随生成终止释放（2026-09-25，用户「工作区工具传了参数，工具就全部问题，换新对话才好」）**：问询（`AskUserInteractionService`，同族的审批服务同日整块拆除）是**容器级、按 `(conversationId, toolCallId)` 建键**的 pending 表，等待方却是本会话的生成协程。所以**每一条终止路径**都要走 `ChatViewModel.releaseInterruptions()`（生成 `finally` 两处、`stop()`、`releaseForReuse()`、`destroy()`），前台服务超时回调必须调 `stop()` 而不是裸 `generationJob?.cancel()`；`currentChatInterruption(..., generating = streaming)` 那道门是兜底——**没人等就不许用面板顶掉输入栏**。表必须按会话分键（原先按裸 id 建键，而解码器在厂商不给 id 时会造 `tool-1` 这种每轮重复的占位 id ⇒ 跨会话顶掉表项），守卫见 `AskUserInteractionServiceTest`/`ChatInterruptionTest`，详见 PORTING §5.59。同批：后台生成三态里「开」此前根本不 acquire 前台服务（保活和通知写成了同一支），`shouldKeepAlive`/`shouldNotifyCompletion` 已把两条判据拆开，见 §5.60
- **Composition must stay cheap** (2026-09-15 sweep, see PORTING.md §5.13): no
  SQLite / file / `ContentResolver` call in a `@Composable` body — including
  `remember { dbCall() }` and composable-call argument expressions. "Read once
  per key" goes through `ui/AsyncLoad.kt`'s `rememberLoaded(initial, keys…) { … }`
  (`produceState` + `Dispatchers.IO`, keeps `remember(key)` semantics; pinned by
  `RememberLoadedTest`), and DB work inside a `LaunchedEffect` needs its own
  `withContext(Dispatchers.IO)` (the effect body runs on the main thread).
  **Machine guard**: `app/src/test/.../ui/CompositionThreadingTest.kt` scans every
  `remember { … }` block and fails on SQL/file/full-table-decode calls (building a
  DAO or grabbing a DB handle does *not* count); exemptions are registered per
  file **and** token with a reason, so a new offending call in the same file still
  fails. Reads are cheap now: `PreferenceRepository.readJson` has a
  write-invalidated row cache, `AssistantStore.get` has `AssistantCache`
  (invalidated by `PayloadEntityDao`), and `AppContainerImpl.prewarmConfigCaches()`
  decodes provider_rows/assistant_rows on IO at startup — so composition-time
  `providerConfig(key)` / `assistantStore.get(id)` / `readJson(key)` are memory
  lookups. **Do not** add new `remember { <db/file call> }` sites.
- **Robolectric 测试不许用「真时钟轮询 + 手动泵 Looper」等异步**（2026-09-21，PORTING §5.40）：
  `Thread.sleep(20)` 循环配 `shadowOf(Looper.getMainLooper()).idle()` 等 `viewModelScope` 的协程，在
  GitHub 的 2 核 runner 上会偶发卡到超时（本地多核复现不了，曾连续六轮 CI 红）。改用
  `app/src/test/.../MainDispatcherRule.kt`（`Dispatchers.setMain(UnconfinedTestDispatcher())`）+ 等真实信号
  （`withTimeout(30_000) { vm.tailLoaded.first { it } }`）。**Compose UI 测试（`ComposeUiTest`）例外**：
  它自己接管 Main 调度器，挂这条规则会打架。
  **Compose UI 测试的样例内容必须留在视口内**（Robolectric 默认 320×470 **px**）：节点被推出去以后
  `assertIsDisplayed` 会因几何出界失败、`performClick` 会点空（`CollapsibleUserBubbleTest` 用 16 行正文
  展开时实测踩过一次，改成 12 行才稳）。要证"变高了"就比 `boundsInRoot.height` 的数值，别依赖可见性断言。
  另外 **CI 上跳过 3 个"只在 CI 会卡"的类**（`ChatTimelineWindowTest` / `ChatHeaderAssistantTest` /
  `DrawerAndChatUiTest`，见根 `build.gradle.kts` 的 `ciSkippedTests` + `-PciSkipFlakyTests`）——
  它们**仍然在本地门禁里跑**；名单由 `CiSkipListTest` 守着，不要往里面加"只是偶尔红一次"的类。
- Only one generated resource is left: the SQLite DDL — `tools/drift_schema_to_sql.py`
  reads `drift_schemas/app_database/drift_schema_v3.json`（开发仓在外层、发布仓在仓根，
  脚本两种布局都自己找）→ `core/data/src/main/assets/memo_schema_v3.sql`，头部注释写的是
  **逻辑路径**（`drift_schemas/...`）而不是相对 `tools/` 的 `../`，否则两棵树互相报假 diff。
  三份 `strings.xml`、`Palettes.kt`、`SettingsKeyRegistry.kt` 自 2026-09-24 解耦起**直接手维**，
  文件头已改掉 "GENERATED … DO NOT EDIT" 的旧告示；`arb_to_android.py` / `palettes_gen.py` /
  `settings_keys_gen.py` / `upstream_root.py` 已删，别再往门禁里加它们。
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
