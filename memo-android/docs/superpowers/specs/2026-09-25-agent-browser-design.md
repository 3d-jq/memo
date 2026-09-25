# Agent 浏览器（模型可自由操控的内嵌浏览器）设计

日期 2026-09-25 · 项目 `memo-android` · 状态：待用户复核，未开工

参照：Eta（`D:\program\.eta-ref`，**PolyForm Noncommercial 许可 ⇒ 只读架构，不搬代码**）。
上游 kelivo 与 RikkaHub 都没有这个能力，所以这是超出上游的加法，实现后要在
`memo-android/docs/PORTING.md` 点名（延续 §5.68 之后的编号）。

## 1. 目标与非目标

**目标**：模型在自己那一轮生成里，能用一个工具打开网页、读正文、找到可交互元素、点击/输入/滚动、
截图看效果；用户在需要登录/验证码时**一键把同一个页面接管过去**，交还后模型继续。

**非目标（第一版明确不做）**：多标签页、地址栏、书签、历史列表、文件上传下载、`<select>` 与
iframe 内元素、JS `alert/confirm/prompt`、User-Agent 伪装与反爬、跨会话共享登录态、
并发多个活动浏览器会话、把浏览器暴露给"不存在的助手"（无助手时仍然没有任何工具，现状不变）。

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

## 3. 工具面：`browser_use`

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
3. JS 弹窗不自动确认（`onJs*` 一律 return false / 忽略）；下载、`onPermissionRequest`、
   新窗口都拒绝 —— 拒绝也要在信封里说明，否则模型会以为动作成功。
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
- 每会话隔离依赖"关掉就清"，若用户在接管期间登录了敏感站点，日志/请求记录里可能留 URL —— 与
  `LogRedactor` 现有规则一致，URL 不脱敏，需要时单独讨论。

## 11. 落地顺序

1. `BrowserSession` + `BrowserScripts`（先只做 navigate/read/find + 纯逻辑测试）
2. `browser_use` 工具面 + app 级门控 + 基线重生成
3. click/type/scroll + 索引契约与错误码
4. 截图上行（含 `ToolImageBytes` 提取）
5. 接管遮罩 + 「清空并关闭」
6. 真机验收 → PORTING 点名 → 版本号与发布
