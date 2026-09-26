# Memo 设计语言（Apple 参考系）

> 2026-09-26 起本项目的 UI 规范文档。设计参照：`D:\program\Apple Copy`
> （Pinguo Design System，Apple HIG 风格）——**只取几何与组件语言，不取颜色**；
> 配色永远走 Memo 主题（`LocalSemanticColors` / `ColorScheme`），任何新 UI 不得写死 hex。

## 1. 圆角：四档 token

唯一来源：`core/ui/src/main/java/com/psyche/memo/ui/theme/MemoRadius.kt`。
Pinguo 的全局唯一 radius = 19.2px ≈ 20dp，内嵌 = radius−4，胶囊 = 999。

| token | 值 | 适用 |
|---|---|---|
| `CARD_DP` | 20 | **一级容器**：卡片、sheet、对话框、独立整行输入框 |
| `INNER_DP` | 16 | 一级容器**内部的嵌套块**：卡内选项行、内嵌输入、表格/代码块等内容 chrome |
| `SMALL_DP` | 10 | 小控件：行内紧凑字段（`IosFormField` inline 变体）、分组列表行的圆角端 |
| `PILL_DP` | 999 | **全胶囊**：按钮、标签、chips —— 无硬角 |

选档口诀：先问「它是不是容器」——是容器就看它在不在另一个容器里面；按钮和 chips 永远 PILL。
新增圆角一律 `RoundedCornerShape(MemoRadius.XXX_DP.dp)`，不许出现裸数字（微细节如进度条
半高胶囊、2-3dp 发丝件除外）。

## 2. 组件规范

- **按钮**：全胶囊轮廓；一颗界面只留一个实心主按钮，次按钮文字化、无描边壳；高度 40/48/56，
  字号 14sp / 字重 600。落点：`IosButton` / `IosTileButton`。
- **卡片**：静音卡 —— 1px hairline（`semantic.hairline`）定边界，阴影只给悬浮时刻
  （4–6% 分层）；用间距对比替代分隔线。落点：`SectionCard` / `SettingsSectionCard`。
- **输入框**：独立整行 = CARD 档 + calm fill（`surfaceCardFill`）+ focus ring（primary@0.4，
  未聚焦描边透明）；行内紧凑 = SMALL 档。落点：`IosFormField`（inline / 非inline 两个变体）。
- **对话框 / sheet**：`MemoAlertDialog` / `MemoSheet`（`app/ui/MemoDialogs.kt`）——
  CARD 圆角 + `overlaySurfaceColor()` 底 + 标题 16sp SemiBold + 正文 14sp +
  取消 onSurface@74% / 确认 primary / 破坏性 error。M3 默认的 28dp 圆角与裸 surface 底
  不许再出现；场景不允许用 `Dialog`（如 WebView 之上的一层）时，复刻同样的样式参数。
- **顶栏**：`MemoTopBar` 一统（40+ 页面已转）；action 走 `TopBarAction`，不手绘 IconButton。
- **设置页骨架**：`SectionHeader` + `SectionCard`；分组标题跟随主题 primary
  （`SettingsUi.kt` 的 `settingsSectionHeaderColor`）。
- **触控**：44×44 下限；紧凑芯片行（如浏览器标签条）例外，但不得低于约 38dp 整行。
- **提示行**：不裸排说明文字，一律 ⓘ + 浮动气泡（`TipHuggingLabel`，ⓘ 紧跟标签文字）。

## 3. 颜色纪律

- 一切颜色从主题派生：`ColorScheme` / `LocalSemanticColors`（`surfaceFill` / `surfaceCard` /
  `hairline` / `success` / `warning` / …）；弹层底 = `cs.overlaySurfaceColor()`。
- RikkaHub 预设主题走「原样表面」通道（`AppSemanticColors.authored`）：页面底 =
  `surfaceContainer`、卡片 = `surfaceBright`、填充 = `surfaceContainerHigh`——勿改回 M3 默认关系。
- 禁止：写死 hex、`onSurface@任意值` 之外的随手 alpha 叠色不经理由、把 Pinguo 的品牌蓝带进来。

## 4. 有意例外（勿「统一」掉）

- **聊天气泡家族**：`ChatBubbleSurface` / 思维链卡 / 预设对话气泡 / 气泡样式预览的圆角属于
  消息样式体系（含用户可自定义的气泡风格 JSON），不按四档收。
- **头像圆、勾选件、开关/滑杆**的半高胶囊、停止钮 SVG 半径（上游 1:1 几何）。
- **输入栏**：kelivo 原样，最小高 64dp，其余参数勿动。

## 5. 新增 UI 检查清单

1. 圆角用了 token？容器层级对吗？
2. 颜色全部主题派生？深浅色都对？
3. 对话框/sheet 用了共享壳？按钮有取消/确认的色彩层级？
4. 触控目标 ≥44dp？
5. 组合期无 IO（见 ARCHITECTURE.md §组合纪律）？
