# 流式等待提示「随大模型输出抖动」——根因与修复（**已实施 2026-09-23**）

> 原先留档为「不修」；用户 2026-09-23 晚改口「你看看这个修复问题」⇒ **已按本文附节的标准修法实施**。
> 根因分析（一~三节）保留原样，实施记录见文末。实现落在 `ui/chat/PinnedFollow.kt`（纯函数判定 +
> 契约测试）与 `ui/chat/ChatContent.kt`（触发源换成布局驱动）。

---

## 一、结论

**抖的不是提示本身，是它在消息列表里的垂直位置**——它以 chunk 频率上下跳约一行高。
之前的几次改动（消息内 → 列表末尾独立项 → 对齐气泡 → 图标/扫光形态开关，§5.49–5.52）
动的全是提示的**位置与形态**，没碰真正的机制（贴底是事件驱动的），所以每次都复现。

## 二、根因：贴底是「事件驱动」，尾部高度却是「异步落地」，两个节奏对不齐

流式期间，提示行坐在 LazyColumn 末尾（`ChatContent.kt:1382-1428`，`STREAMING_INDICATOR_ITEM_KEY`）。
它的屏幕位置由两套互不同步的机制决定：

### 1. 贴底只在少数「事件」上发生

`ChatContent.kt:788-801`：

```kotlin
LaunchedEffect(messages, streaming, following, pointerDown, autoScrollEnabled,
               timelineListState.isScrollInProgress) {
    if (streaming && following && !pointerDown && autoScrollEnabled && …) scrollTimelineToBottom()
}
```

`ChatViewModel.updateAssistantStreaming()`（`ChatViewModel.kt:2866-2888`）**每个 SSE chunk 都新建一个
`_messages.value` 列表实例**，所以这个 effect 每个 chunk 重启一次、贴一次底。
除此之外会贴底的只有：进会话首次（:778）、流式结束两连贴（:806-824）、IME 抬起（ImeRisePinEffect）。
**布局长高本身不会触发贴底**——`snapshotFlow { tailBottomGapPx() }` 那个收集器（:745-756）只在尾巴
「已经贴底」时把 `following` 置 true，是个 no-op，不做滚动。

### 2. 但流式期间尾部高度是异步、延后落地的

- `MarkdownRenderer.kt:213-237`：首帧同步解析，**之后每个增量都在 `Dispatchers.Default` 解析**
  （`snapshotFlow → conflate → mapLatest`），落地至少晚 1 帧；≥8000 字还叠加 50ms 长文去抖（:228-232）。
  这些是 `MarkdownText` **内部** `parsed` 状态的变化，不经过 `messages` 实例变化。
- 代码块 `animateContentSize(220ms)`（§5.32③）：围栏关闭后高度动画期间**每帧**都在变。
- 思考卡展开/折叠过渡、流式结束时长出的操作行 / Token 统计行，同样是异步高度变化。

### 3. 时序 → 就是「抖」

```
chunk 到达 → messages 新实例 → effect 贴底（gap≈0，提示钉在底边）
          → 下一帧异步解析结果落地 → 尾部长高 Δh（≈一行高，~60px）→ 没人贴底
          → 提示被顶高 Δh
下一个 chunk   → 又贴回底边 → 提示掉回来
```

Δh 出现频率 ≈ 换行/新段/新代码行的频率：普通文字约每秒 1~3 次，**代码块流式时几乎每个 chunk 一次**，
思考段切换时是大段跳。工具调用时工具卡一次性长出/换态（几百 px 跳变）是同一机制、幅度更大。

### 4. 可排除的其他嫌疑

- 指示器自身的动画（`MemoLoadingIndicator` 呼吸缩放、`ThinkingShimmerText` 扫光）都是
  `rememberInfiniteTransition` 无 key、参数实例稳定（`timelineSettings` 是 `remember {}` 一次性构建），
  **不会**被 chunk 重启，不是抖动源。
- `StreamingIndicatorPlacementTest` 守的「必须是列表独立项」是必要的，但只解决「消息内部重排」那一层，
  不解决本根因。

## 三、两个参考实现为什么没这个问题

### 3.1 RikkaHub（`D:\program\.rikkahub-ref`）

`app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt:278-290`：

```kotlin
if (settings.displaySetting.enableAutoScroll) {
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
            if (!state.isScrollInProgress && loadingState) {
                if (visibleItemsInfo.isAtBottom()) {                       // :236-243
                    state.requestScrollToItem(conversationUpdated.messageNodes.lastIndex + 10)
                }
            }
        }
    }
}
```

- **它的贴底是「布局驱动」：每发生一次布局就重新判定**。异步解析晚一帧落地 → 那次布局变化
  **本身就是再次贴底的触发器**；`isAtBottom()` 容差 8px、底部 contentPadding 32dp，一行的漂移
  （≈65px）仍在贴底带内 → 当帧贴回，漂移活不过一帧。
- 它的 Markdown 管线和我们是同一套异步模式（`richtext/Markdown.kt:240-252`：首帧同步 +
  `mapLatest + flowOn(Default)`）——**异步滞后不是差异，差异在滞后落地后谁来贴底**。
- 判据上它没有 `following`/`pointerDown` 旗标，纯靠位置（在底部才贴、不在底部绝不碰）+
  `!isScrollInProgress`；我们多一套旗标（§4.42 硬规则：手指在屏上绝不程序化滚动），这不是差异点。
- 它的 `isAtBottom`（最后一条**可见** item 底边 vs 输入栏顶边）与我们的 `tailBottomGapPx()`
  （:680-684）同源；`lastIndex + 10` 有界越界下标 ≈ 我们的哨兵项。这部分早对齐过了。

### 3.2 deepseek-harness（`D:\zcode_workspace\.zcode\workspace\default\deepseek-harness`，Web GUI）

**它也有这个显示**（`packages/client/ui-conversation/src/client/chat/ChatView.tsx`）：

- `:403` `{running && <TurnStatus … />}` —— **轮次级**等待提示「Deep diving...」（:131-138），
  15 秒后才叠一个计时（:129）；注释原话「Turn-level loading signal: rides the whole running turn
  (first-token wait, tool execution, streaming) so it never flickers per step」；dots 在
  `AssistantMarkdown.tsx:6` 注明「live in the chat view's tail」。它连**会动的文字都没有**
  （无扫光/无轮换短语），形态上更接近 kelivo 原版三点 + 一句静态状态文字。

**它为什么不抖**——和 RikkaHub 同一个根因解，而且更直接：

- `:331-341` **一个 ResizeObserver 观察内容列**（连 composer 一起观察）：任何内容高度变化
  （流式文本、工具卡展开、markdown 重排）→ `followRef.current()` → **只有 `atBottomRef.current`
  （读者还贴着底）才写 `scrollTop = scrollHeight`**（:318-327）。即：**贴着底 ⇒ 内容每长高一次
  就贴一次**，异步落地的那一帧自己就把自己校正了。
- `:182-192` 逐 chunk 的文本增长**不走事件贴底通道**：事件触发用「tip signature」
  （`openState:firstSeq:lastKey:order.length:running:lastSteeringId`），只有**结构**变化
  （新增节点 / 轮次边界 / 队列项）才贴；注释专门警告不要在「scroll-driven at-bottom chrome
  re-render」时贴（会把惯性滚动 snap 到地板）。
- `:277-281` 归属账本 `observedTopRef`：只有**读者输入**造成的滚动才改写 at-bottom 所有权，
  程序化写入/浏览器 clamp 不抢所有权——比我们的 `pointerDown` 旗标更细，目的相同。
- 测试把契约钉死：`packages/client/ui-conversation/tests/chat-view.client.spec.tsx:1069`
  「one ResizeObserver owns pinned dynamic-height follow and ignores growth while away」
  （贴着底 700→1200 跟着长；离开后 200 绝不被动）、`:1022`「Streaming growth must NOT drag a
  scrolled-away reader down」、`:1031`「stream-finalization shrink clamp」（流式结束内容变矮也要贴住）。

## 四、收敛结论

三个实现（kelivo 原版 / RikkaHub / dsh）的共识：**跟随必须由「内容尺寸/布局变化」驱动、
由「读者是否贴底」把门；数据事件只承担结构变化那一下。** 我们的
`LaunchedEffect(messages, …)` 两头都反了——这就是抖动根因，与提示放在哪、长什么样无关。

---

## 附：标准修法（**已实施 2026-09-23**）

实施结果：

- **判定收成纯函数** `ui/chat/PinnedFollow.kt`：`shouldPinToBottom(...)`（七个门：有内容 ∧ following ∧
  自动滚动开 ∧ 手指不在屏 ∧ 未在滚动 ∧（流式 ∨ 结束宽限）∧ 尾部缝隙 > 容差 10dp）+ 
  `bottomAnchorIndexFor(...)`（哨兵下标 = 消息数 + 压缩进度行 + 流式提示项）；`FINISH_GRACE_MS = 450`。
- **触发源换成布局驱动**（`ChatContent.kt`）：删掉 `LaunchedEffect(messages, …)` 的事件贴底，
  改为 `snapshotFlow { tailBottomGapPx() }.distinctUntilChanged()` ⇒ 内容长高那一帧自己触发贴底。
- **两连贴 + delay(450)** 折成同一个 `followGrace` 宽限窗口（不再补丁式连贴两次）。
- **哨兵下标补齐流式提示项**（原实现在 `compacting && streaming` 时漏算 1 ⇒ 少滚一行）。
- **测试**（照 dsh 的两条契约）`PinnedFollowTest`：贴着底 + 长高 ⇒ 继续贴；离开底部 ⇒ 绝不被拽回；
  另加「已贴底不重复滚（防自激）」「手指在屏上 / 正在滚动 / 自动滚动关 / 无消息」四个门与锚点下标三组合。

未做（留账）：第 4 条「导航回顶部/上一条/下一条时置 `following = false`」的 latent 抢占 —— 需要先确认
那三个动作各自的落点，单独一批；其余按本文实施。

以下为原始修法清单（保留供对照）：

1. 删掉 `ChatContent.kt:788-801` 的 `LaunchedEffect(messages, …)` 事件贴底，改
   `snapshotFlow { timelineListState.layoutInfo.visibleItemsInfo }` 布局驱动：
   满足 `following ∧ autoScrollEnabled ∧ !pointerDown ∧ !isScrollInProgress ∧ (streaming ∨ 结束后 450ms 宽限)`
   且 `tailBottomGapPx() > 容差(8~12px)` 时 `scrollTimelineToBottom()`。浏览器侧对应物即 dsh 的
   ResizeObserver（`ChatView.tsx:331-341`），Compose 侧即 RikkaHub 的 snapshotFlow。
2. 贴底目标改成**真正的哨兵项下标**（现在 `bottomAnchorIndex` :707-708 在 `compacting && streaming`
   时漏算流式项，指向压缩分隔线）：`messages.size + (压缩项?1:0) + (流式项?1:0)`，按下标对齐、
   与内容多高无关。
3. 把 :802-824 的「两连贴 + delay(450)」折叠进同一机制的 450ms 宽限窗口。
4. 导航「回顶部/上一条/下一条」置 `following = false`（latent 抢占：流式中按回顶部会被贴底抢回）。
5. 测试照 dsh 写两条契约：贴着底+内容长高 ⇒ 贴底位置不变；离开底部+内容长高 ⇒ 位置绝不被拖动。

其他备选（均未采用）：代码块流式期关 `animateContentSize`；提示改消息区底部浮层（与 §5.49–5.52
用户既定结构冲突）；`reverseLayout` 重构（大手术）。明确不做：把 `MarkdownText` 改回主线程逐 chunk
同步解析（正是 §5.14/§5.32 搬走的掉帧源头）。
