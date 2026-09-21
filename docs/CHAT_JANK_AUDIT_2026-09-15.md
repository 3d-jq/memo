# 对话界面卡顿 / 闪烁审计（2026-09-15）

**触发**：用户「点击到底部这个按钮 对话界面会闪」「你这个对话点击加载还是卡 你没有完整看到源代码的吧」「看看人家原项目和 rikkhub 代码 人家做的都很好 都没有什么卡顿问题 全面查看我这个项目和卡顿检查问题对比这两个项目的报告」。

**对比对象**
- **原版（Flutter）**：仓库内 `lib/`（`features/home/widgets/message_list_view.dart`、`features/chat/widgets/chat_message_widget.dart`、`features/home/controllers/scroll_controller.dart`）
- **RikkaHub**：`<RikkaHub 本地克隆>`（`ui/pages/chat/ChatList.kt`、`ui/pages/chat/ChatPage.kt`、`ui/components/message/ChatMessage.kt`、`ui/components/richtext/Markdown.kt`）
- **我们（Memo/memo-android）**：`app/src/main/java/com/psyche/memo/...`

---

## 0. 摘要

| # | 问题 | 状态 |
|---|---|---|
| 1 | 「到底部」按钮传 `Int.MAX_VALUE` 当滚动偏移 ⇒ 点一下整个对话界面闪一下 | ✅ 已修（改成滚末尾哨兵项） |
| 2 | 组合期实参里 `messageDao.count(id)`（整表 `SELECT COUNT(*)`）⇒ 点开长会话卡一下 | ✅ 已修（VM 级 `tailLoaded` + 窗口判空） |
| 3 | 打开会话取数：整会话 `groupVersions` 全表扫 + 多余全表 `COUNT` + 自创 60 条 Markdown 预热 | ✅ 已修（尾窗 40 / 按需版本表 / 去掉预热） |
| 4 | 组合期读偏好（约 195 处 `remember { readJson }`，每次一条 SQL） | ✅ 已修（读缓存 + 启动预热 + 机器守卫） |
| 5 | 组合期读助手/供应商（`assistantStore.get` / `providerConfig`，含列表行级） | ✅ 已修（`AssistantCache` / `ProviderConfigCache` + 启动预热） |
| 6 | 消息行会不会被无谓重组（点按钮/打字/翻状态时整屏重渲染） | ✅ 已核：`MessageRow` 是 skippable，探针测试钉住 |
| 7 | Markdown 流式解析是否压主线程 | ✅ 已核：与原版/RikkaHub 同款（首帧同步 + 之后 `mapLatest` on Default） |
| 8 | 到底部锚点（越界下标 vs 哨兵项） | ✅ 已加 RikkaHub 同款哨兵项 |
| 9 | Flutter 的自定义**列表高度估算**（`_estimateItemExtent`）与**首屏骨架**（`windowSkeletonKey`） | ⬜ 未做（前者影响长会话跳转精度；后者是可见 UI 变化，要你点头） |
| 10 | 真机 frame trace（Perfetto/systrace）级别的量化 | ⬜ 未做（本轮证据 = 编译器报告 + 单元测试 + 逐行对比，不是帧时间线） |

### 2026-09-15 第二轮追加：三个**随内容/历史量线性变慢**的点（用户「不论内容多少 点击都不会卡」）

用户这句话把范围锁死了：参考实现不随数据量退化，说明我们这条路上有 O(N) 的活。逐个找出来：

| # | 位置 | 原状（O(N) 在哪） | 参考实现怎么做 | 修法 |
|---|---|---|---|---|
| 11 | `SideDrawerContent` 列表刷新 | `LaunchedEffect(selectedId, open) { withContext(IO) { reload() } }` —— `selectedId` **每次点会话都变** ⇒ 整表 `conversationDao.getAll()` + 逐条解 payload JSON；`open`（`presenting` 驱动）在**抽屉收起时**再触发一次。历史越多，「侧边栏→主界面」越慢 | 原版：内存 `_conversationsCache` + `notifyListeners`；RikkaHub：Room `Flow<List<Conversation>>`。**都不在选中时重查** | ✅ 改成 `LaunchedEffect(open) { if (open) reload() }`；抽屉内部的增删改本来就显式调 `reload()`（12 处），重新打开也必刷。守卫 `DrawerListReloadTest`（扫 `LaunchedEffect` 块里出现 `selectedId` + `reload()` 就红） |
| 12 | `MarkdownText` 首帧解析 | 首帧**同步**解析整篇 CommonMark，并且为**每个 AST 节点**各存一份「子树纯文本」字符串 —— 祖先把后代文本重复存一遍（O(内容 × 深度) 的分配），内容多的消息一打开就是主线程上一大批分配 | 原版 `markdown_with_highlight.dart`：`IncrementalMarkdownDocument`（流式按块增量解析）+ `ByteLruCache`（4MB，规范化结果）+ 流式上限（表 30 行 / 高亮 300 行·12000 字）+ 长文 50ms 渲染去抖 | ✅ 改成「整篇拼一次扁平缓冲 + 每节点记区间 + 惰性切片」，`MarkdownPlainTextTest` 3 例钉住「逐字等价」与「不再重复」 |
| 13 | 打开会话时的首屏 Markdown 冷解析 | 可见的几条消息全冷缓存 ⇒ 解析落在跑组合的那一帧 | 原版有首屏骨架 + 增量解析；RikkaHub 首帧同样同步，但解析器是逐行式的（更便宜） | ✅ `ChatViewModel.prewarmWindowMarkdown`：发布窗口**之前**在 `Dispatchers.Default` 预热**即将可见的一屏**（条数 8 / 字符 40000 双上限，命中渲染侧 `withCitations=true` 的同一个键） |

> 注意这三条的**形状**：11 是「每次交互都重做一遍全量读」，12 是「单位内容的开销被放大」，13 是「把解析放在错误的线程/时刻」。都不是"再调一调参数"能解决的，所以对照两个参考实现看**它们怎么组织数据与时机**才是正解。


---

## 1. 取证方法（可复现）

```bash
# ① Compose 稳定性/可跳过性报告（哪个 composable 会被重组、被哪个 unstable 实参拖累）
cd memo-android && ./gradlew :app:compileDebugKotlin --rerun-tasks
#   → app/build/compose_reports/app_debug-composables.txt
#   判据：`restartable skippable` = 实参没变就跳过重组

# ② 消息行重组探针（真实手势，不是推断）
./gradlew :app:testDebugUnitTest --tests 'com.psyche.memo.ui.ChatRowRecompositionTest'
#   ChatRecompositionProbe（MessageRow 每组合一次 +1）+ 断言：
#   「手指按下再抬起（翻 pointerDown/following/navVisible）之后，消息行组合次数 = 0」，
#   并且先断言「导航面板确实被唤出」—— 避免手势没落地造成的空断言。

# ③ 滚动命令守卫
./gradlew :app:testDebugUnitTest --tests 'com.psyche.memo.ui.ChatScrollOffsetTest'
#   扫所有 `*ScrollToItem(` 调用点（含跨行 5 行窗口）禁止 Int.MAX_VALUE

# ④ 组合期重活守卫
./gradlew :app:testDebugUnitTest --tests 'com.psyche.memo.ui.CompositionThreadingTest'
#   扫所有 `remember { … }` 块禁止 SQL/文件/整表解码（豁免按 文件+token 登记）
```

---

## 2. 打开会话（点一条历史会话）路径逐帧对比

| 步骤 | Flutter 原版 | RikkaHub | 我们（现在） |
|---|---|---|---|
| 首屏取数 | `chat_service.loadTimelinePage`：尾窗 + 槽位，`getMessagesByIds` **批量**取 parts | 一条分页查询（64/页），消息与 parts 同列（`ConversationRepository.kt:454`） | `MessageDao.getTail(40)` + parts **批量** `IN (...)`（`MessageDaoPartsTest` 7 例） |
| 版本表（分支选择器） | `chat_controller.dart:180-198`：只预载 `versionCount>1 \|\| version>0 \|\| 已选` 的组 | —（无分支选择器概念） | ✅ 只查窗口里 `version > 0` 的组，一条 `IN (…)`（`MessageDaoGroupVersionsTest` 4 例） |
| 「还有更早的」 | `LoadedTimelinePage.hasMoreBefore = start > 0`（不查库） | 由分页结果推断 | ✅ 窗口装满即认为有（`ChatTimelineWindowTest`） |
| 首屏骨架 | **有**：`windowSkeletonKey`（`message_list_view.dart:1743`）在首屏加载时铺骨架行 | `LoadingIndicatorKey` 哨兵项（`ChatList.kt:382`） | ⬜ 无（首屏空 → 内容到齐后出现） |
| 首帧落到底部 | `_animateToBottom` / 布局期贴底 | `ChatPage.kt:179 requestScrollToItem(size + 5)` | ✅ 滚末尾哨兵项（本轮新增） |
| 顶栏 `+` 的三态判据 | 内存里的 `currentConversation` 消息数 | 内存 state | ✅ `ChatViewModel.tailLoaded + messages.isEmpty()`（**本轮修掉组合期 `messageDao.count(id)`**） |
| 列表高度估算 | **自定义** `_estimateItemExtent`（`message_list_view.dart:578-587`；默认估算 100px 在长会话里偏差大） | LazyColumn 自带 | ⬜ 用 LazyColumn 自带（长会话跳转精度差一档，见 §7） |
| 组合期读库 | 无（Provider 内存态） | 无（VM state） | ✅ 无（`CompositionThreadingTest` 守卫；本轮清掉最后两处） |

---

## 3. 流式输出（每帧）开销对比

| 项 | Flutter 原版 | RikkaHub | 我们 | 判据 |
|---|---|---|---|---|
| 消息行是否可跳过重组 | `ListView.builder` 只重建脏 item + `RepaintBoundary`（`chat_message_widget.dart` 5 处） | `ChatMessage` 是 skippable composable，块级 `animateContentSize()` | ✅ `MessageRow` = `restartable skippable`（实参全 stable；`compose_compiler_config.conf` 把 `UiMessage`/`ChatTimelineSettings`/`Assistant`/`MessagePart`/`Conversation`/`ProviderConfig` 声明 stable） | 编译器报告 + `ChatRowRecompositionTest` |
| Markdown 解析 | 每气泡自绘（`gpt_markdown`） | `Markdown.kt:240-252`：`remember { parseMarkdown(content) }` **首帧同步** → `snapshotFlow + distinctUntilChanged + mapLatest + flowOn(Default)` | ✅ **逐字同款**（`MarkdownRenderer.kt:205-220`）+ LRU(32) AST 缓存 | 源码对照 |
| 流式增量更新 | 只把当前气泡标脏 | 列表按 key diff | `_messages` 整表替换 → `items(key/id)` diff；只有流式那条的 `msg` 变了 ⇒ 只重组它 | `ChatRowRecompositionTest`（碰列表不重组任何行） |
| 流式等待提示 | 三点脉动 | 三点/骨架 | 扫光文字（**用户点名的有意偏离**，§5.11） | — |

> 结论：**每帧这一层我们和两个参考实现是同一档**（都靠「按引用跳过重组」+ 后台线程解析）。用户感觉到的卡，主要来自 §2 的**入口路径**（组合期查库/多余查询）与 §4 的**滚动命令**，这两类本轮都已处理。

---

## 4. 滚动与锚点

| 项 | Flutter 原版 | RikkaHub | 我们 |
|---|---|---|---|
| 到底 | `_animateToBottom`：目标真实 `maxScrollExtent`（450ms easeOutCubic） | `requestScrollToItem(size + 5)` / `(lastIndex + 10)` | ✅ **末尾哨兵项** `SCROLL_BOTTOM_ITEM_KEY` = `requestScrollToItem(bottomAnchorIndex)`（本轮；不做动画，见 §5.14 说明） |
| 上一条/下一条 | `jumpToPreviousQuestion`/`Next`（按消息索引） | `animateScrollToItem(firstVisibleIndex ± 1)` | `animateScrollToItem(target)`（真实索引） |
| 到顶 | `animateTo(0)` | `animateScrollToItem(0)` | `animateScrollToItem(0)` |
| 流式跟随 | 自定义 `ScrollPosition` 布局期贴底 | `ChatList.kt:236-243 isAtBottom()` + `requestScrollToItem(lastIndex + 10)` | 位置判据 + `pointerDown` 硬标志 + `requestScrollToItem`（§4.42） |
| 用户接管 | `Listener.onPointerDown` | 滚动状态 | `pointerInput` 的 `awaitFirstDown` 硬标志（**勿只靠 `interactionSource`**） |
| IME 抬起钉底 | `pinBottomDuringViewportResizeIfNeeded` | Auto | `ChatViewportFollow.shouldPinTimelineOnImeRise`（6 例） |

**本轮修掉的闪烁**：`animateScrollToItem(size - 1, Int.MAX_VALUE)`。越界偏移会被原样写进滚动位置（本工程真机日志 `layout total=4 firstVisible=3 offset=2147483647`），下一帧 LazyColumn 再夹一次 ⇒ 表现为「点一下闪一下」。现在滚哨兵项：下标合法、位置恰好 `maxScrollExtent`。

---

## 5. 列表配置差异（原版更讲究的两处）

| 项 | Flutter 原版 | 我们 |
|---|---|---|
| 视口外缓存 | `SuperListView.builder(cacheExtent: 600, addRepaintBoundaries: false, extentEstimation: _estimateItemExtent)`（`message_list_view.dart:1676-1693`） | `LazyColumn` 默认（`contentPadding` top 8 / bottom 16） |
| 重绘隔离 | `addRepaintBoundaries: false` + **逐条显式 `RepaintBoundary`**（`:2105/2134/2206`） | Compose 自己管层（无显式 `graphicsLayer`/`RepaintBoundary`） |
| 高度估算 | `_estimateItemExtent`（`:578-587`）：SuperSliverList 默认按 100px 估，长会话里偏差大 ⇒ 自定义估算 | LazyColumn 内部估算（无自定义钩子） |
| 首屏骨架 | `windowSkeletonKey`（`:1743`） | 无 |

> 这三条（`cacheExtent`、自定义估算、首屏骨架）属于「体验打磨」而非「卡死」：`cacheExtent` 决定预组合范围（Compose 侧是 `beyondBoundsItemCount` 语义），自定义估算决定长会话里**跳转到第 N 条**的落点精度。第三条是**可见 UI 变化**（骨架行），按项目规则需要你先点头再动。

---

## 6. 页面入口通病的修复史（已在 §5.13 记账）

| 批次 | 内容 |
|---|---|
| 2026-09-15 第一批（fork 记录） | 统计页组合期兜底聚合删掉；`remember { loadModelOptions }` ×4 → `rememberLoaded`；冷启动选会话、抽屉/供应商页等 `LaunchedEffect` 里的库操作包 IO |
| 2026-09-15 第二批（本仓，`4136d26`） | `PreferenceRepository.readJson` 读缓存（写端单键失效）；`AssistantStore.get` 解码缓存 `AssistantCache`；启动预热 `prewarmConfigCaches()`；8 处重活移出组合期（provider_rows/assistant_rows 整表解码、背景图解码、技能目录遍历与文件读取）；**机器守卫** `CompositionThreadingTest` |
| 2026-09-15 第三批（本轮） | 组合期 `messageDao.count()` 换成 VM 级 `tailLoaded`；到底滚动改哨兵项；`ChatScrollOffsetTest` 守卫 |

---

## 7. 仍挂账（按性价比排序）
1. **真机 frame trace**：本轮全部证据是「编译器报告 + 单元测试 + 源码对照」，**没有** Perfetto/systrace 帧时间线。要看真实帧耗时（尤其滚动 vs 流式），需要一次 `perfetto` 抓取（我可以做，但会占用你正在用的手机几分钟）。
2. **自定义高度估算 / `beyondBoundsItemCount`**：长会话「跳到第 N 条」的落点与预组合范围。收益中等（体感「跳得准不准」），改动小（`LazyListState` 不支持自定义估算，Compose 侧等价物有限，需要方案）。
3. **首屏骨架行**（原版 `windowSkeletonKey`）：**可见 UI 变化**，需要你确认要不要（现在是「空 → 内容到齐」，用户可能感知为「顿一下」）。
4. **抽屉/历史列表的开合路径**：抽屉是常驻组合（§4 注释里记过），点会话时的抽屉收起动画与 ChatContent 首帧叠加。没做 trace 之前不敢乱改。
5. **图片/附件渲染路径**：本轮没查（`MessageImageAttachments` / coil 解码是否在首帧）。
6. **输入栏每键重组**：`input` 状态在 VM 里，ChatContent 每键重组；消息行已 skippable（探针测试覆盖「碰列表」，未覆盖「打字」）。

---

## 8. 本轮改动的回归判据（跑这些就知道有没有退回去）

```bash
./gradlew --continue lintDebug testDebugUnitTest :app:assembleDebug
# 关键 5 个：
#   ChatRowRecompositionTest     —— 消息行不会被交互状态重组合
#   ChatScrollOffsetTest         —— 没有 Int.MAX_VALUE 偏移
#   CompositionThreadingTest     —— 组合期不查库/不读文件
#   ChatTimelineWindowTest       —— 尾窗/版本表/到底标记/tailLoaded
#   ChatHeaderAssistantTest      —— 聊天页 Compose 端到端（含新开关）
```
