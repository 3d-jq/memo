# Agent 浏览器（模型可自由操控的内嵌浏览器）设计

日期 2026-09-25 · 项目 `memo-android` · 状态：待用户复核，未开工

参照：Eta（`D:\program\.eta-ref`，**PolyForm Noncommercial 许可 ⇒ 只读架构，不搬代码**）。
上游 kelivo 与 RikkaHub 都没有这个能力，所以这是超出上游的加法，实现后要在
`memo-android/docs/PORTING.md` 点名（延续 §5.68 之后的编号）。

## 1. 目标与非目标

**目标**：模型在自己那一轮生成里，能用一个工具打开网页、读正文、找到可交互元素、点击/输入/滚动、
截图看效果；用户在需要登录/验证码时**一键把同一个页面接管过去**，交还后模型继续。

**非目标（第一版明确不做）**：书签、历史列表、iframe 内元素、User-Agent 伪装与反爬、
跨会话共享登录态、并发多个活动浏览器会话、把浏览器暴露给"不存在的助手"（无助手时仍然没有任何
工具，现状不变）。

> **2026-09-26 修订（用户「都做吧，现在直接做完整」）**：多标签页、地址栏 + 手输 URL、
> `<select>`、文件上传、JS 弹窗**不再是**非目标 —— 全部纳入，具体形状与边界见 §12。
> 上面那行"非目标"从本日起只对**书签 / 历史 / iframe / 反爬 / 跨会话共享**有效。

## 2. 内核：`BrowserSession`

新目录 `app/src/main/java/com/psyche/memo/provider/browser/`：

- `BrowserSession.kt`：持一个 `WebView`。**照 `MermaidRenderer.kt:128-135` 已验证的离屏形状**：
  用 `MutableContextWrapper(appContext)` 构造、`measure/layout` 出手动视口 **1280×1600 px**、
  `javaScriptEnabled=true`、`domStorageEnabled=true`、`allowFileAccess=false`、`mixedContentMode`
  只允许 NONE、`safeBrowsingEnabled` 保持开（API 26+ 有降级路径）、`cacheMode=LOAD_DEFAULT`。
  不设 `addJavascriptInterface`（攻击面），所有驱动都是 `evaluateJavascript`。
- `BrowserScripts.kt`：JS 源码常量 + Kotlin 侧结果解析（纯字符串/JSON 逻辑，可单测）。
- `BrowserTool.kt`：工具定义、参数校验、动作分派、错误形状。
- 接管 UI：`app/src/main/java/com/psyche/memo/ui/chat/BrowserOverlay.kt`。

**容器持有**（照 `WorkspaceTerminalSessionManager` + `WorkspaceReadLedger` 的既有形状）：
`AppContainerImpl.browserSessions: MutableStateFlow<Map<String, BrowserSession>>` +
`browserSession(conversationId)` / `closeBrowserSession(conversationId)`。
**同时只允许一个活动实例**：`browserSession(x)` 时若存在别的会话的实例，先 `close` 它并清凭据。
原因必须写进代码注释：Android 的 `CookieManager` / `WebStorage` 是**全 app 共享**的，
做不到并发隔离，只能做到"新会话看不到上一个会话的登录态"。

生命周期：`close()` = `stopLoading` + `loadUrl("about:blank")` + `removeAllCookies(null)` +
`WebStorage.deleteAllData()` + `destroy()` + 从 map 移除；`ChatViewModel.destroy()`（会话被删/被挤掉）
里调 `container.closeBrowserSession(conversationId)`。**不新增浏览器页的导航路由、不新增数据库表**（接管用遮罩，见 §6；§4 的「Agent 能力」是设置页里
的一行，走既有设置页导航，不是浏览器路由）。

## 3. 工具面：~~`browser_use` 单工具 + `action` 枚举~~ → **已作废，见 §12.1**

> 本节保留为**历史记录**（它是 v1 的实现依据，代码与测试都按它做过一遍并被验收）。
> 工具面从 2026-09-26 起改成 **12 颗独立工具**，动作表在 §12.1；表格里的
> **索引契约 / 代次换号规则 / 文本量 / 统一信封 / 错误码**全部继续有效，不因拆分而变。

单工具 + `action` 枚举，与 Eta 的"一次调用一个动作"一致，但**动作面收窄**（Eta 13 个，这里 9 个）：

| action | 参数 | 返回 |
|---|---|---|
| `navigate` | `url`（必填，仅 https） | 信封 + `title` / `url` / `generation` |
| `read` | `offset?` `max_chars?` | 可见正文（自写 TreeWalker，跳过 script/style/svg/canvas/iframe，节点预算 12000 / 截止 500ms）+ `truncated` + `next_offset` |
| `find` | `query?`（文本子串过滤） | `{generation, elements:[{index,tag,role,text,placeholder,type,href,bounds}]}` 上限 **20 条**，按 DOM 顺序编号 |
| `click` | `index` + `generation`，或 `x`+`y` + `generation` | 信封 |
| `type` | `index` + `generation` + `text` | 信封 |
| `scroll` | `direction`(up/down) + `amount?`（默认 0.9 屏） | 信封 |
| `screenshot` | — | 图片附件（见 §5）+ 信封 |
| `back` / `reload` | — | 信封 |

**索引契约（这条是设计核心，也是与 Eta 最大的偏离）**：每次 `read`/`find`/`navigate` 之后 DOM 的
"可寻址快照"记一个自增 `generation`；`click`/`type` 必须回传它，且**服务端校验**：
`generation` 不等于当前值 → `STALE_GENERATION`（不执行，让模型重新 `find`）；
index 越界/元素已消失 → `TARGET_NOT_FOUND`。**模型永远看不到 CSS selector**，避免抄写长串出错；
selector 只在 JS 内部用来从快照取回同一个节点。
**什么时候换号**：`navigate`/`back`/`reload` 一定换号；页面自己跳转（`onPageFinished` 里 URL 变了）
也换号；**`scroll` 不换号**（快照还是那份，只是 bounds 变了，index 仍指向同一节点）。

**文本量**：`read` 默认 `max_chars=6000`、上限 20000，超出给 `next_offset`；
整个信封的最终大小仍过现成的 `provider/tool/ToolRunner.cap`（不另造一套上限）。

**统一信封**（沿用 `provider/tool/ToolResults` 的形状，别造第二套）：
`{ok, tool:"browser_use", action, status, url, title, generation, code?, message?, instruction?, next_offset?}`。
错误码固定枚举：`BLOCKED_SCHEME` `INVALID_ARGS`（带 `violations`，走 `provider/tool/ToolArgs`）
`NO_PAGE` `NAV_TIMEOUT` `SCRIPT_TIMEOUT` `STALE_GENERATION` `TARGET_NOT_FOUND`
`USER_CONTROLS_PAGE` `RENDERER_GONE` `CANCELLED`。
**每条错误必带 `instruction`**（PE3 那条纪律），`CANCELLED` 说"结果未知"而不是"失败"。

## 4. 门控与提示词

- 新 PREFERENCE 键 `agent_browser_enabled_v1`，**默认 "1"（开）** —— 用户 2026-09-25 复核 spec 时明确
  「改成默认开着的」。直接后果：**开了以后所有助手都能用这个工具**（不绑助手），第一次对话就可能被
  模型自己调用去查网页；缓解手段是提示词里那条路由句（"要读网页内容才用 `browser_use`，不要在浏览器里
  登录/支付"）与设置页那颗随时可关的开关。
- `ChatViewModel.offeredTools()` 新增一条 **app 级分支**（不读助手配置）：`currentAssistant()` 为空
  时仍然返回空（保持现状），否则开关为 "1" 就把 `browser_use` 加进去。这是 Memo 首例"不绑助手"，
  代码注释里写清楚为什么（浏览器是设备能力，不是人设能力）。
- 设置入口：设置主页 →「Agent 能力」新页面（一行开关 + 一句说明 + 一颗「清空浏览器数据」）
  —— 与 MCP/TTS/搜索各自的设置页同级，不再往"聊天项显示"里塞。
- 系统提示词加一段使用说明（何时用浏览器 vs 用搜索）、`provider/ToolRules.kt` 加一条路由句
  （"要读网页内容用 `browser_use`，不要在浏览器里登录/支付"）。
  ⇒ **`SystemPromptGoldenTest` 会红**：把 `browser_use` 加进该测试的 `allOffered` 后，
  按失败信息里写明的路径重写 `app/src/test/resources/prompts/system-prompt-golden.txt`，
  并在 PR 描述里说明基线变了（这是网在起作用，不是噪声）。
- 行为边界（提示词，不是硬闸）：可以填输入框，**不得点击提交/发送/购买/关注这类把动作交给外部世界的目标**；
  要发出时停下来告诉用户"填好了，请你点发送"。审批体系已拆除（§5.68），这一条只是约定，
  我们在 spec 里如实这么写。

## 5. 截图与图片上行

复用现成链路，不新增通道：`ToolHandler.handle(..., onImage)` → `ToolHandler` 内的
`persistToolImage()`（`:411-423`，落 `<filesDir>/tool_images/`）→ `foldToolResult(id, content, images)`
→ `payload.images` → `LlmMessage.toolImages` → `MessageContent.openAiToolResultContent` 的 content 数组，
并且**只发给 `request.imageInput` 为真的模型**，否则补 `[Image output omitted: …]` 占位。
要做的一处小改：`persistToolImage` 目前只吃 `WorkspaceTools.ToolImageBytes`，把该类型提到
`provider/tool/` 下共用（两个调用方都改引用），不复制一份。
截图用 `View.draw(Canvas)` 到 `Bitmap`，**PNG 原尺寸不缩放**（对齐 Eta 的"不降质"口径），
但超过 **4 MiB** 时不发给模型、改回 `message` 里说明"页面过大，请用 read 分段取正文"。

## 6. 用户接管

- 入口在工具卡：`browser_use` 的 `ToolCallCard` 自己的 body（像 TTS/天气那样有特殊行）给「查看页面」。
- 载体是**遮罩**，照现成的 `htmlPreviewFor`（`ChatContent.kt:514` + 渲染 :2107-2112）加一颗
  `browserOverlayFor`，不新增 `NavHost` 路由、不穿 navigate 回调。
- 遮罩内 `AndroidView(factory = { it })` 把**同一个 WebView** 的 parent 摘过来（先 `removeFromParent`），
  `detach` 时还回离屏持有者。遮罩顶部：当前标题 + 「交还给助手」+「清空并关闭」。
- 接管期间 `BrowserSession.userControls = true`，模型任何调用立即返回 `USER_CONTROLS_PAGE` +
  `instruction`（"用户正在自己浏览，等他交还或改用 read 已拿到的内容"）。

## 7. 安全硬闸（能落代码的）

1. **仅 https**：`navigate` 解析 URL，scheme 非 https → `BLOCKED_SCHEME`；`read` 里对
   `file://` / `content://` / `javascript:` 一律不给进入；`WebViewClient.shouldOverrideUrlLoading`
   拒绝非 https 跳转与外部 app 跳转（不发 Intent）。
2. **网页内容只是数据**：工具结果（正文、元素文本、title）发给模型前统一过
   `provider/tool/PromptFrames.sanitize`（A1 那条框架标签转义），并把正文包进既有的工具结果帧里。
3. JS 弹窗不自动确认；下载、`onPermissionRequest` 拒绝 —— 拒绝也要在信封里说明，否则模型会
   以为动作成功。**§12 修订了两处**（2026-09-26）：接管态的 `alert/confirm/prompt` 改成**弹给用户**
   并回填结果（无头时照旧自动拒绝 + 记账，见 §12.4）；`window.open` / `target=_blank` 不再一律拒绝，
   而是**开成新标签**（到 `MAX_TABS` 才拒），因为多标签之后它有去处了（见 §12.2）。
4. 不给 WebView 任何 `addJavascriptInterface` 桥；不读宿主 CookieJar。

## 8. 线程、超时与取消

- 所有 WebView 调用 `withContext(Dispatchers.Main)`（Mermaid 同款），**组合期绝不创建/触碰 WebView**
  （`CompositionThreadingTest` 那条规矩）。
- 超时：`navigate` 25 s、单次 JS 8 s、`read` 内部 500 ms 节点截止 + 12000 节点预算、
  `screenshot` 5 s。
- 取消：协程取消 → `stopLoading()`，信封 `CANCELLED`，**绝不自动重放**（点过没点过都可能，算结果未知）。
- 串行：一个 `ReentrantLock`（或 `Mutex`）包住同一会话的所有动作，与 Eta 一致 —— 模型并行发两个工具
  调用时不会互相踩。

## 9. 测试

纯逻辑（JVM 单测，无需设备）：`BrowserScriptsTest` 断言 JS 的参数转义（`JSONObject.quote`）、
`find` 结果解析、20 条上限、index 顺序、`next_offset` 二分；`BrowserToolTest` 断言
`generation` 校验（旧号必须 `STALE_GENERATION`）、非 https 必须 `BLOCKED_SCHEME`、每条错误都带
`instruction`、开关关时 `offeredTools()` 里不出现 `browser_use`。
既有基线：`SystemPromptGoldenTest` 重生成后必须绿；`LocalToolOfferingTest` / `ToolRulesTest`
跟着新名字改。Robolectric 测不到真实导航（`WebView` 是 shadow），所以**页面行为必须真机验收**：
在你手机上跑一个真站，走 `navigate → read → find → click → screenshot → 接管 → 交还`。

## 10. 已知风险（写下来，不藏）

- OEM WebView（你这台是 ColorOS 系）内核版本差异、`safeBrowsing` 拦截、站点反爬 —— 都会表现为
  读不到东西，只能靠真机验收发现；发现后按错误码补，不猜。
- 提示注入的实际防线只有 §7.2 的转义 + "正文只是数据"，网页里嵌的指令仍可能诱导模型做动作，
  而审批已经拆了 —— 唯一硬约束是它按不到"提交"这一类的**约定**，这是这个功能真实的残余风险。
- ~~每会话隔离依赖"关掉就清"~~ **§13 已作废这条设计**（2026-09-26 晚）：登录态改成 app 全局持久，
  清只由用户手动触发。残留的同一条风险继续成立：接管期间登录敏感站点，日志/请求记录里可能留 URL ——
  与 `LogRedactor` 现有规则一致，URL 不脱敏，需要时单独讨论。

## 11. 落地顺序

1. `BrowserSession` + `BrowserScripts`（先只做 navigate/read/find + 纯逻辑测试）
2. `browser_use` 工具面 + app 级门控 + 基线重生成
3. click/type/scroll + 索引契约与错误码
4. 截图上行（含 `ToolImageBytes` 提取）
5. 接管遮罩 + 「清空并关闭」
6. 真机验收 → PORTING 点名 → 版本号与发布

## 12. v2 修订（2026-09-26 用户拍板）—— 拆工具、标签页、地址栏、`<select>`、上传、弹窗

用户原话与判断依据：`browser_use` 单工具在我们现有的工具卡渲染里，**九种动作全都显示成同一张
「内置浏览器」卡**，看不出模型这一轮到底做了什么（「非常不好看」）。本仓**已有两处先例支持拆开**：
工作区（`workspace_read_file`/`write_file`/`edit_file`/`shell`/`list`/`glob`/`grep`）与记忆族
（`memory_read`/`update`/`search_profile`…，用户 2026-09-23 还专门要求"一个动作一个图标"）。
参照实现 Eta 用的是单工具 + 14 action，**它的可读性靠的是卡片按 action 出标题**
（`AgentTraceFormatter.browserActionLabel()`），不是靠拆工具 —— 但既然本仓的卡片体系就是
"一个工具名一个图标一个标题"，跟着本仓的形状走比跟着参照实现走对。

**代价，写在这里不要藏（落地后实测，不再是估算）**：14 份 schema 每轮都进请求 =
**1389 tokens / 5555 字符**（拆分前单工具是 ~180 token）。首版把同一段行为边界贴满 13 颗时是
**2411 tokens / 9641 字符**，几乎是本条估算区间（~1000–1400）的两倍；收口成「边界只贴在会改页面
的那三颗（`browser_click`/`browser_type`/`browser_select`）+ 族级一句」之后是
**1323 tokens / 5291 字符**，加上多标签那三处新增（`new_tab`、`browser_tabs`、
`browser_page_info` 的标签数）回到 **1389**。天花板钉在 **1400**
（`BrowserToolsTest.theWholeFamilyStaysWithinTheSchemaBudget`）—— 要往上涨必须先在 PORTING 点名。
换来的是卡片可读 + 模型不用先选 `action` 字段再选参数。用户已知情并要求拆。

### 12.1 十三颗工具（+ 一颗 `browser_tabs`）（名字 / 参数 / 图标）

`provider/browser/BrowserTools.kt`（复数，替换 v1 的 `BrowserTool.kt`）统一出定义与分派。
全部沿用 `ToolResults.ok/error`、`ToolRunner.run` 的 deadline、以及 v1 的代次契约。

| 工具名 | 参数 | 图标 | 说明 |
|---|---|---|---|
| `browser_open` | `url`(必填 https) `new_tab?`(默认 false) | `Lucide.Globe` | 在活动标签打开；`new_tab=true` 开新标签并设为活动 |
| `browser_read` | `offset?` `max_chars?` | `Lucide.Menu`（v1 已用形状则沿用） | 可见正文，与 v1 完全一致 |
| `browser_find` | `query?` | `Lucide.Search` | 可交互元素清单 + `generation` |
| `browser_click` | `index` `generation` 或 `x` `y` `generation` | `Lucide.MousePointer2` | 点击；之后代次 +1 |
| `browser_type` | `index` `generation` `text` 或 `x` `y` `generation` `text` | `Lucide.TextCursorInput` | 只写入，绝不提交（v1 边界不变） |
| `browser_select` | `index` `generation` `value` | `Lucide.ListChecks` | **新增**：`<select>` 选值并派发 `change` |
| `browser_scroll` | `direction` `amount?` | `Lucide.ArrowUpDown` | 不换代次（§3 契约） |
| `browser_screenshot` | — | `Lucide.Camera` | §5 上行规则不变 |
| `browser_back` | — | `Lucide.ArrowLeft` | 历史回退 |
| `browser_forward` | — | `Lucide.ArrowRight` | **新增**：历史前进 |
| `browser_wait` | `query?` `index?` `generation?` `timeout_ms?`(≤8000) | `Lucide.Timer` | **新增**：等元素/文本出现，替代 Eta 的 `wait_for_selector`（**不吃 CSS selector**，只吃 `find` 的 index 或文本子串） |
| `browser_page_info` | — | `Lucide.Info` | **新增**：`url`/`title`/滚动位置与 `atTop`/`atBottom`/标签列表/`generation` |
| `browser_reload` | — | `Lucide.RefreshCw` | 重新加载活动页 |

- 图标全部**逐图标 import**（`icons-lucide` 是文件级扩展属性），并把这 14 个名字全加进
  `ToolIconCoverageTest.mustHaveOwnIcon`；`toolIconFor` 按**工具名**（不再看 args）出图标。
- `offeredTools()` 一次加 14 颗、`reserved` 加这 14 个名字；`ToolRules` 那条路由句改成点名
  代表几颗（`browser_open` / `browser_read` / `browser_find`）+ 一句"这些是同一个内置浏览器的
  动作"，别写 12 行。基线 `-Pgolden.bless=1` 重写并核 diff。
- `browser_use` 这个名字**不留兼容**：库里没有历史会话在用（功能今天刚落地），旧会话里若有
  `browser_use` 记录，卡片按未知工具名回落到默认样式即可。
- 门控、错误码、`USER_CONTROLS_PAGE`、`page_notice`、`READ_STALLED` 语义全部照 v1。

### 12.2 标签页

`BrowserSession` 从"1 个 WebView"变成 **1 会话 N 个标签、1 个活动标签**：

- 每个标签一个 `WebView`（同一 `BrowserSession` 内），共享同一份 cookie / WebStorage（本来原生侧就是
  app 全局，拆标签不改变隔离模型）。
- 代次（`generation`）**按标签**记：`session.generation(activeTab)`；`find`/`click` 只作用于活动标签。
- 模型侧**默认永远只碰活动标签**，唯一能改活动标签的是 `browser_open(new_tab=true)`。
  再加一颗 `browser_tabs`（`list` / `select(index)` / `close(index)`，图标 `Lucide.Layers`）——
  它是第 14 颗：模型可用，但提示词不引导它（主要给用户接管时对齐界面上的标签条）。
- 接管遮罩：顶部标签条（可点切换、可关、可加），地址栏（显示当前 URL、可编辑、回车跳转 =
  `loadUrl`，只允许 https，与非 https 拦截同一道闸）。
- **落地形态（写死，免得后来人按字面猜）**：
  `MAX_TABS = 5`（每枚是一个真 WebView）；代次**由会话发号**（`BrowserSession.nextEpoch`），
  不在各标签自增 —— 各标签自己 `++` 迟早同号，模型拿着 A 标签的 index 就会点到 B 标签，
  切标签必然发新号；`target=_blank` / `window.open` → 新标签并设为活动（`NEW_TAB_OPENED` 记账），
  到上界才拒；关掉单个标签**不清** cookie/WebStorage，清只发生在 `BrowserSession.close()`。
- 关闭标签与关会话都要清凭据（沿用 `close()` 那套；单标签关闭**不**清全局 cookie，否则会把别的
  标签登录态一起干掉 —— 清 cookie 只发生在 `closeAll()`／换会话，这条要在实现里写死为注释）。

### 12.3 用户接管的界面边界

- 遮罩不再是"只有两颗钮"：`MemoTopBar`(标题=当前页 title) + 标签条 + 地址栏 + 页面 + 底部
  「交还给助手」/「清空并关闭」（**清空仍走 `container.appScope.launch { browserSessions.closeAll() }`**，
  遮罩不许自己 `session.close()`）。
- 系统返回 = 交还（`OverlayBackHandler`），不销毁实例。

### 12.4 上传与 JS 弹窗的边界（安全，不放开到模型）

- **文件上传只给用户手动**：`WebChromeClient.onShowFileChooser` 在 `userControls==true` 时走
  系统选择器（`ActivityResultContracts`，只有界面手里有 registry，照 `LocationPermissionService`
  那根挂起通道的形状）；无头模式直接 `onReceiveValue(null)` 拒掉，并在 `page_notice` 里写
  `FILE_CHOOSER_NEEDS_USER` —— **模型不许上传文件**（这条不放开）。
- **JS 弹窗**：接管中 `alert/confirm/prompt` **弹给用户提供 Memo 对话框**（confirm/prompt 需要
  结果回填，取消即 `cancel()`）；无头时仍自动 `cancel()` + 记账（v1 行为）。两条路径都要记账，
  只是接管时多问用户一次。
- `<select>` 由 `browser_select` 处理（模型可操作），派发 `change` 但不提交表单 —— 与 `type` 同一纪律。

### 12.5 浏览器入口落到 `+` 面板，并且**常驻**（2026-09-26 同日二次修订）

用户 2026-09-26：入口**不该在工具卡里**（浏览器页面是整会话共享的，不属于某一条消息，工具卡还会
滚走）。统一重做输入区之前，先临时放到 **`+` 面板**。

**同日再改**（用户「这个查看页面，为什么不能常驻啊？大模型可以使用这个浏览器，用户也可以使用呀」）：
这一行的判据从「本会话有活着的浏览器实例」改成**「全局开关开着」**——浏览器是设备能力，
不该等模型先动手用户才看得见门。两种状态两个名字：没有实例 ⇒「打开浏览器」（点下去当场
`sessionFor(conversationId)` 建一枚，空白标签 + 地址栏自己输网址）；有实例 ⇒「查看页面」
（回到正在操作的那一枚）。`hasLiveSession` 从此只决定文案，不再决定这一行在不在。
建实例派发在**容器级 `appScope`**，且执行前**再读一次开关**（面板打开时那次读是快照）。
工具卡里那颗钮与它的全部管道（`onOpenBrowser` 逐层透传、`canTakeOverBrowser`）一起删净，不留死参数。
统一方案（附件方格 / 能力胶囊 / 活动胶囊）尚未定稿：草案页 `D:\DevCache\design-mock\memo-input-ux.html`，
待用户拍板后单独开一份计划（跟踪在 task #130）。

### 12.6 落地顺序（v2）

1. §12.5 入口搬家 + 拆 14 颗工具（含 `browser_select`/`forward`/`wait`/`page_info` 的真实实现与测试）
2. §12.2 多标签 + 遮罩地址栏/标签条（含 `browser_tabs`）
3. §12.4 上传与弹窗的接管态处理
4. 门禁 + 真机验收（v1 那 7 条 + 新增：多标签隔离、地址栏只吃 https、select 选值、上传需用户、弹窗回填）
5. PORTING §5.69 改写 + AGENTS 那行同步

## 13. 真机反馈第二轮（2026-09-26 晚，用户「根本无法大模型控制浏览器」）—— 登录态与"做了什么"

用户原话：「你这个浏览器按照 eta 做了吗？他怎么老是出现自己说做了，根本没有调用工具，让他给 DeepSeek
发信息，他说老是返回登录状态。体验太差，根本无法大模型控制浏览器」。这次是**逐条对着参照实现 Eta 的
源码**核出来的三条根因（Eta 路径 `D:\program\.eta-ref`，只读架构、不搬代码）：

### 13.1 登录态：把 §2 的「换会话就地清」整条改回来

- **症状与根因**：`sessionFor(别的会话)` 会 `close()` 上一枚，而 v1 的 `close()` 里带着
  `removeAllCookies` + `WebStorage.deleteAllData`。原生那份 jar 是 **app 全局**的，就地清
  换不来"每个对话各留一套登录"，只会把所有对话的登录一起抹掉 ⇒ 用户切回来就停在登录页。
- **改法（照 Eta：`AgentBrowserSession` 是进程级单例、全局持久，清只有一处手动 `resetFromUser`）**：
  `BrowserSession.close()` **只销毁 WebView**；清数据挪到 `BrowserSessionStore.closeAll(clearSiteData=true)`，
  只有「清空并关闭」与「关掉总开关」这两条用户明确的动作会走到。换会话 ⇒ `close()`，不清。
- **代价，写在明处**：跨会话的登录态与站点数据不再自动消失，"这个对话不留痕"这条能力**没有了**；
  用户想要干净只能手动清（或关开关）。这是**产品决定**，不是漏洞，别再"顺手加回就地清"。

### 13.2 每颗调用都交回页面证据（对着「它自己说做了」）

Eta 的每一次动作结果都带 `url/host/title/is_loading/can_go_back/http_status`；我们过去只有
`url` + `generation` + 模型自己传进来的把手 —— **模型看不到任何页面状态**，下一步只能凭想象写。
现在：

1. `BrowserGateway` 多两个只读事实：`title`（`onReceivedTitle` 实时维护，**零额外 JS**）与
   `loading`（`onProgressChanged` 的 progress<100）。
2. **所有**工具结果的信封统一带 `title`（与 `url`/`generation` 同一处注入）。
3. 动作类调用等不到页面停下（`loading` 到上限还在转）⇒ 结果里写 `still_loading: true`。
   等待节拍照 Eta：先给 250 ms，再轮询 `loading`，上限 3 s（外层每颗只有 30 s，
   而 `navigate` 自己最坏 25 s，不能把预算花在等上；Eta 是 10 s，我们刻意取短并且**说实话**）。

### 13.3 `browser_click` 的 `page_changed`：能不能保证成功不能，至少不许假装成功

- **根因**：`clickScript` 派发的是 `dispatchEvent(new MouseEvent(...))`，`isTrusted=false`。
  现代站点的按钮上很常见地挂着"只认真实手势"的判断 ⇒ 那一下**什么也不会发生**，而工具回一句 `ok`，
  模型就把它转述成"已经发送了"。**Eta 同样没有解决这件事**（它只回一个写死的 `side_effect:"possible"`），
  所以这条是我们自己补的，不是"照 Eta 抄漏了"。
- **做法**：点之前、点之后各跑一次 `pageSignatureScript`（url|title|scrollY|scrollHeight|
  `body.childElementCount` 五个标量拼的指纹，**不许有遍历**），不同 ⇒ `page_changed: true`；
  相同 ⇒ `page_changed: false` 并附一句 `note`：明说"没有任何可观测变化"、"合成点击常被只认真实
  手势的按钮忽略"、"**不许告诉用户成功了**，先 `browser_find`/`browser_read` 看清页面、换一个元素、
  或者交给用户自己点"。
- **取不到指纹（脚本超时 / 页面被导航走）⇒ 干脆不写 `page_changed` 这个键**：宁可少一张收据，
  也不许把"不知道"说成"没变化"；取证失败**永远不许**把已经执行的动作报成失败（那会让模型以为没点，
  而实际可能已经点了）。
- 成本：一次点击多两发标量读取（各 ≤1.5 s 上限）。**不进 schema**（不占每轮请求那份 1389 token 的预算），
  只在动作发生的这一次付。

### 13.4 明确**不做**的（这次一起核清楚了）

- **不覆盖 `userAgentString`**：Eta 也没有覆盖（全仓零命中），我们沿用系统 UA。桌面 UA 会改变站点
  给不给桌面版的行为，但那是另一件事，别和这次的三条混在一起改。
- **不做登录墙/验证码识别**：Eta 也没有（只有 `HTTP_401/403`）。
- **不动 https-only / 不放开 mixed-content**：Eta 是 `MIXED_CONTENT_ALWAYS_ALLOW` + `safeBrowsing=false`，
  我们刻意相反（spec §7 的硬闸）。这次失败与它无关，别拿"照 Eta"当放开的理由。
