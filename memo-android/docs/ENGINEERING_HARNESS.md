# 工程规范（照 deepseek-harness 学）

来源：DeepSeek 开源的 agent harness（本地副本 `D:\zcode_workspace\.zcode\workspace\default\deepseek-harness`，
下称 dsh）。它把「让模型/agent 稳定干活」从**靠自觉**变成**可机械检查的制度**，这正是我们缺的。

本文只抄**能落到本仓库**的条款。每条写清四件事：原则 → 我们踩过的同源坑 → 落点 → 谁来机器检查。
**本文不复制任何别处已有的事实**（见 §1：每个事实只有一个所有者）。

---

## §1 每个事实只有一个所有者（one home per fact）

**原则**：可变事实由**拥有它的组件**提供，文字只引用不手写。dsh 的原话是「模型名不能在 persona 里手写，
否则改一次配置就变成谎话」——他们用 `{{model}}` / `{{cwd}}` 变量注入，未知变量直接抛错（不静默留 `{{modle}}`）。

**我们的同源坑**（都是「手写事实 → 漂移」）：

| 坑 | 手写了什么 | 现在谁拥有 |
|---|---|---|
| 图表底色 | 工具里写死 `#FFFFFF` | 主题（`palette.background`，经 `withBackground(svg, bg)` 传入） |
| 工具 schema 的图象清单 | 一度是手写字面量 | 实现（`ChartSpec.Kind.entries` 生成 enum） |
| 工具分工 | 两边描述各写一半 | 各自 DESCRIPTION（互指对方工具名，测试钉住） |
| 主题色说明给模型 | 描述里写死一组 hex | 主题（`VisualTools.withThemeNote()` 按当前主题拼） |

**已落地**：系统提示词装配收成一个出口 `provider/prompt/PromptAssembly.kt`
（带位 `PromptOrder`：persona 0 < toolRules 100 < memoryRules 110 < search 120 < skills 130 <
workspace 140 < 用户注入 900；未登记的 source **直接抛**），原先**两处各自 `joinToString("\n\n")`**
的两条渲染路径合并为一；「可用变量」清单的唯一来源是 `PromptTransformer.supportedKeys()`，
UI 只读 `PromptVariableCatalog`，两边一致性由 `PromptVariableCatalogTest` 钉住。

**落点**：① 任何进模型的东西（工具描述、系统提示词、记忆规则）里**不得出现硬编码颜色/尺寸/模型名**；
② 纪律文档之间只留链接，不留副本 —— `AGENTS.md`（有意偏离清单）↔ `docs/PORTING.md`（细节）↔
`.workbuddy/memory/`（工作日志）三者**不重复同一句事实**；③ 顺序带照 dsh 固定下来：
**身份/产品 → 助手 persona/系统提示词 → 记忆规则 → 技能块 → 工具指导**（工具指导永远在最后）。

**机器检查**：`tools/invariant_checks.sh` 的 `C1 / C2`（描述里禁止 hex 颜色、禁止绝对色值）。

---

## §2 fail loud：宁可当回合失败，也不要静默降级

**原则**：dsh 要求「配置错在最早可解析点就抛」，空 `catch` 必须写明**吞了什么、为什么别的东西到不了**。

**我们的同源坑**（全部已修，细节见 PORTING §5.24）：`orient="auto-start-reverse"` → AndroidSVG 抛异常 →
**空白卡片**；自闭合标签写重复 → 非法 XML → 一张都渲染不出来；工具结果没有 `status` → 模型自己判成「失败」；
导航回调 `= {}` 默认值 → 点击被静默吞掉。

**落点**：① 工具执行失败一律回 `tool_error` + **可操作文案**（点名哪个字段/哪种写法不对）；
② 解析/校验失败**不许返回默认值继续**；③ 新增 `catch` 必须写清吞的是什么。

**机器检查**：`C4`（禁止 `catch (_) {}` 之类空吞）；测试层要求每条校验路径有反例（§4）。

---

## §2.5 模型可见 ⟺ 可重建（日志必须等于请求）

**原则**：dsh ——「anything that reaches a model request must be reconstructable from the
session log」。光「都记下来了」不够，**日志里的那份必须与实际发出去的一字不差**。

**实测漂移（2026-09-23）**：请求侧已改成 `assembleSystemPrompt`（按带位排序 + 去空白），
而 `ContextLogAssembler` 还是老的 `parts.joinToString("\n\n")` —— 于是**日志里看到的顺序
与真实请求不同**，排查请求问题时会被误导。

**落点**：日志的拼接与标签长度都改走 `provider/prompt/PromptAssembly` 的**同一份规范化**
（`orderedPromptParts`）；由 `PromptRenderPathInvariantTest` 守三条：
① `joinSystemParts(parts) == assembleSystemPrompt(parts)`；
② 按标签切片（摘掉段首 `\n\n`）== 渲染用的每一段，且段数/来源标签一一对应、总长覆盖全串；
③ 追加到已有系统消息时，追加部分的带位顺序不变。

## §3 证据要匹配被改的面，不要默认跑全量

**原则**：dsh ——「Match evidence to the surface」：行为改动跑聚焦测试、模型/用户可见输出跑快照、
文档改动跑 doc-sync；**CI 负责穷尽覆盖**，本地不要重复跑已经通过的全量。

**我们的现状**：每次都是 `test + lintDebug + assembleDebug` 全量（≈7 分钟），既慢又掩盖「该跑哪一类证据」。

**落点**（本地验收矩阵）：

| 改动面 | 必跑 |
|---|---|
| `app/` 业务代码 | `:app:testDebugUnitTest` + `:app:lintDebug` + `:app:assembleDebug` |
| `core/ui/`（组件/markdown/主题） | `:core:ui:testDebugUnitTest` + `:app:compileDebugKotlin` |
| `core/data`（模型/DAO） | `:core:data:testDebugUnitTest` + `:app:testDebugUnitTest` |
| 公共 API / 跨模块签名 | 全量 `test` + `lintDebug` + `assembleDebug` |
| 真机可见行为 | 以上 **+ 装机**（`adb install -r` 并记录 `lastUpdateTime`）——装机不算测试，两者都要 |

**机器检查**：无（纪律条款，写在本文与提交模板里）。

---

## §4 不变式 + 反例：护栏必须证明它能拒绝无效输入

**原则**：dsh ——「Wire mechanically checkable invariants into an executed top-level gate,
**and prove each changed acceptance path rejects an invalid case**」。

**我们的现状**：其实已经无意中做对了几处——消毒器的 `<script>`/外链用例、`isImageFileName` 的负例表、
`kind enum == 实现` 的等价断言都属于这类。但**没有制度化**：新加护栏时经常只测正例。

**落点**：任何新护栏（检查脚本、不变量测试、lint 规则）必须在**同一提交**里附一条反例说明，
证明「喂给它坏输入它会红」。`tools/invariant_checks.sh --self-test` 就是这条原则的自证。

**机器检查**：`invariant_checks.sh --self-test`（对每条规则喂一个**故意的坏样例**，必须全部被判红）。

---

## §5 把历史教训变成机械可检的门禁

**原则**：dsh ——「机械可检的不变式要接进一个真的会跑的门禁」。**我们的痛点是：几十条血泪教训
全躺在 memory 里靠人记**，下次照样踩。

**落点**：`tools/invariant_checks.sh`（每条规则都是某次真实事故的化石）：

| 规则 | 类型 | 事故 |
|---|---|---|
| `C1` 工具描述里禁止 `#RRGGBB` 字面量 | 硬（=0） | 主题一变描述就成了谎话（2026-09-18）→ 颜色改由主题经 `withThemeNote` 在**请求时注入** |
| `C3` 导航回调参数禁用 `= {}` 默认值 | **棘轮**（存量 32，只许减少） | 漏传时点击被静默吞掉（PORTING §5.11） |
| `C4` 禁止一行空 `catch` | 硬（=0） | 「渲染不出来」系列事故的共同形状；已把 18 处补上「吞了什么」 |
| `C5` 应用代码禁用 `android.widget.Toast` | 硬（=0） | 统一走 `SnackbarManager`/`AppSnackBarOverlay`；**Dialog 内要自挂 overlay**（2026-09-18） |
| `C6` 改动文件无行尾空白、以一个换行结尾 | 硬 | 照抄 dsh（`git diff --check`） |

> `C2`（禁止把卡片底 `lerp` 到 `Color.White`）**没有进脚本**：同一形状的 `lerp(…, Color.White, …)`
> 在别处是合法的（思考卡的扫光高光，`ChainOfThoughtCard.kt`），grep 无法不误报。
> 照 dsh 的「narrow, justified exceptions」：它留在评审清单里（判据见 PORTING §5.22），不进自动门禁。

用法：`bash tools/invariant_checks.sh`（提交前，与 §3 的相关面检查一起跑）；
`bash tools/invariant_checks.sh --self-test` 证明每条规则都能拒绝反例。

**第一条把规则逼出来的实例**：`C1` 一上来就是红的（工具描述里写着 `#2C2C2A` 等颜色），
修法不是删掉颜色，而是让**主题**这个所有者注入 —— 见 `provider/chart/VisualTools.withThemeNote`。

---

## §6 任务记录制度（对到我们的 PORTING.md）

dsh 要求「**非平凡改动必须在同一个 PR 里附 Agent Note**」（`.agents/notes/`：feature/architecture/bug-fix，
已实现的归档后**冻结不可改**）。我们的对应物就是 `docs/PORTING.md` 与 `.workbuddy/memory/`：

- **非平凡改动**（新功能、架构选择、修掉一个非显然的 bug）→ 追加 PORTING 条目（含判据、踩坑、留账）；
- **归档冻结**：PORTING 里已定稿的条目只增不改；要改就在新条目里写明「覆盖 §x.y」；
- **日志不重复**：memory 记过程与决策现场，PORTING 记结论与口径，两者不互抄。

**机器检查**：无（纪律条款）。评审时看「这个改动有没有对应条目」。

---

## §8 工具执行契约（照 dsh 的 `execute()`）

**原则**：dsh ——「策略不写进工具」+「抛错 ⇒ isError」+「结果有上限」+「args 先校验后冻结」。
落地在 `provider/tool/ToolExecution.kt`（`ToolRunner` + `ToolResults`），由 `ToolHandler` 的本地工具
分派统一调用：

| 契约 | 我们的做法 |
|---|---|
| 统一 deadline | `ToolRunner.timeoutFor(tool)`（策略表集中在此，默认 20s；`render_mermaid` 35s＞其内部 25s，避免双重超时） |
| 挂住不拖死对话 | 超时回 `tool_timeout` + 可操作文案 |
| 抛错 ⇒ isError（不吞） | 任何 `Throwable` 归一成 `tool_crashed`；**`CancellationException` 透传**（用户点停止不算工具失败） |
| 结果有上限且标明 | `MAX_RESULT_CHARS = 24k`，超限回 `{truncated:true, originalChars, content}` —— 不许把截断当完整 |
| 规范结果值 | 成功 `{type:<工具的>|tool_result, status:"ok", tool, …工具字段}`；失败 `{type:"tool_error", status:"error", error, message, tool, instruction?}`。**不重命名**各工具既有的 `type`（视图层在读），只统一外壳 |
| args 校验与审批 | 已在原位（各工具的 schema/解析 + 高风险工具审批门）—— 视作 pre-execute 的位置，策略不进工具本体 |

**为什么重要**：2026-09-18「工具明明成功、模型却说没成功」就是缺「规范结果值」——
当时靠加一句「别跟用户说失败」的散文叮嘱绕过，现在形状本身带 `status`，**叮嘱已删除**。

## §7 这份文档自己也要能被改

dsh 的 AGENTS.md 有一条 *Editing these instructions*：指令本身可修订，但要求「每条规则自足、
高层细节用链接、能压缩就压缩」。

**落点**：本文的修订只做两件事——**收紧条款**或**新增可检规则**；新增规则必须同时进
`invariant_checks.sh`（含反例）。条款若与 `AGENTS.md` 冲突，以 `AGENTS.md` 的「有意偏离清单」为准。
