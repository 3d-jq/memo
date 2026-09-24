<p align="center">
  <img src=".github/icon.png" width="128" alt="Memo">
</p>

# Memo

原生安卓大模型聊天客户端（Kotlin + Jetpack Compose）。没有账号、没有自建服务器、没有遥测：
你在设置里填谁的端点，请求就只发给谁。

*A native Android LLM chat client — no account, no backend, no telemetry.*

Memo 是一个独立的原生安卓应用：包名 `com.psyche.memo`、数据库 `memo.db`，
自己发版、自己演进。它的来历与参考见文末[许可与致谢](#许可与致谢)。

## 安装

从 [Releases](https://github.com/3d-jq/memo/releases) 下载 APK 安装：Android 8.0 及以上（`minSdk 26`），
不需要 root、Google Play 或 GMS。首次启动进「设置 → 供应商」填你自己的 API Key。
debug 变体包名带 `.dev` 后缀，可以和正式版共存。

应用内「关于 → 检查更新」读取 GitHub Releases 的 `releases/latest`，这是唯一的应用自检联网，
不携带设备标识；关掉通知里的更新提示也不会影响任何功能。

## 能力

**模型接入**
OpenAI（Chat Completions / Responses）、Anthropic Messages、Google Gemini 三套协议原生实现，
DeepSeek、智谱、月之暗面、通义、小米 Mimo 等国内厂商有真机实测记录。模型列表可在线拉取并批量导入，
每个模型可单独覆盖上下文长度、采样参数、思考预算与请求头。供应商可配 API 密钥池、代理与自定义请求层。

**流式与思考链**
增量渲染、思维链折叠卡、按段拆分的回复气泡。自动重试按状态码 + 关键词双触发（指数退避、停止词可配），
主模型失败走 fallback 链。provider 返回的原始报错会归类成中文提示，不把 JSON 原样抛给使用者。

**上下文压缩**
阈值机制照 opencode：估算 `system + messages + tools` 的 token，超过
「上下文窗口 − max(输出预算, buffer)」时，在**同一会话**内把较早的上下文归纳成一条锚定摘要检查点，
而不是新建会话。上下文占用在「上下文管理」面板里查看。

**工具**
- 本地工具：时间、剪贴板、朗读、计算器、屏幕时间、日历读写、当前位置（安卓侧自写：
  权限 → 定位服务 → 10 分钟内缓存秒回 → 实时 10 秒超时 → 回退过期缓存；逆地理拿不到地址就只给坐标）
- 工具审批：敏感工具可逐项设为需要确认，参数摘要显示在对话里；拒绝会以错误形式回给模型
- `ask_user`：模型可以反问，一题一页地在输入栏位置作答
- MCP：连接外部 MCP 服务器，含 OAuth 2.1 授权流程（RFC 9728 / 8414 + DCR + PKCE，系统浏览器 + 本地回环回调）
- Agent Skills：`<filesDir>/skills/<名>/SKILL.md` 即技能，支持手动粘贴 / 文件 / GitHub 仓库三种导入，内置 skill-creator
- 沙箱工作区：proot 容器内跑真 shell，带交互式 PTY 终端（多标签 + 附加键栏）、文件读写编辑与
  list/glob/grep 检索工具，Node / Python / 常用命令行工具一键安装（默认国内镜像源，可取消可卸载）

**记忆**
从对话里抽取—蒸馏—合并长期记忆（四动作判定 + 批量 judge），带流程追踪页可查每一步为什么被写入或丢弃；
世界书支持 5 种注入位置；指令注入可逐项开关。

**多模态与语音**
图片 / 文档输入（HEIC 自动转码、按字节嗅探 MIME、OCR、文档正文抽取），12 家网络 TTS + 7 种云端 ASR，
悬浮播放器支持暂停续读、±15 秒定位与 0.8–2.0 倍速。

**联网搜索**
23 个可运行的搜索 provider（Tavily / Bing / Brave / Exa / Serper / SSE 系列等），结果以引用胶囊挂回消息，可统计用量。

**数据与备份**
一键归档为 zip；本机副本带保留策略与定时调度（可置顶 / 导出 / 恢复）；WebDAV 远端备份；支持从
Cherry Studio 与 Chatbox 导入。恢复走 ATTACH 快照 + 指纹去重的合并策略，并有前向兼容闸门：
高版本的备份不会静默覆盖坏你的库。请求日志分「上下文 / 应用 / 请求」三类，写盘前脱敏。

**界面**
Markdown + GFM + LaTeX 渲染、代码块折叠 / 换行 / 高亮 / HTML 预览、Mermaid 与自绘可视化图、
侧边抽屉全局搜索、消息多选与导出、四列色卡的主题预设、iOS 风格控件与触觉反馈，
简中 / 繁中 / 英文三种语言。

## 隐私与数据

**留在本机的**

| 内容 | 位置（应用私有目录） |
|---|---|
| 会话、消息、助手、供应商配置与 API Key | `databases/memo.db` |
| 上传的图片 / 文档 | `files/upload/` |
| 技能 | `files/skills/` |
| 生成的图片与视频 | `files/images/`、`files/videos/` |
| 工具返回的图片 | `files/tool_images/` |
| 头像、助手背景、自定义字体 | `files/avatars/`、`files/assistant_backgrounds/`、`files/fonts/` |
| 上下文 / 应用 / 请求日志 | `files/logs/` |
| 沙箱 rootfs 与工作区文件 | 应用私有目录下的沙箱目录（体积最大的一块，可在「存储」里清理） |
| 备份归档与本机副本 | 应用私有目录；只有导出与分享时才写到你选的位置 |

**会离开本机的**，只有你亲手开启的那几条通道：

- 你在「供应商 / 语音 / 生成 / 搜索 / WebDAV」里配置的端点 —— 请求体与附件按功能需要发送
- 你连接的 MCP 服务器
- GitHub Releases（检查更新，不带设备标识；应用内自更新下载的 APK 落 `Download/`）
- 沙箱容器内你执行的命令访问的网络
- 系统分享、剪贴板、相册/相机：只在你主动点了对应按钮时发生

**没有的**：统计埋点、崩溃上报、广告 SDK、后台同步账号数据、任何自建服务器。
**注意**：备份 zip 与 WebDAV 上传都不加密，密钥与聊天记录是明文 —— 请放到你自己控制的位置。

**申请的权限与用途**（除网络与振动外都是运行时/特殊权限，全部可拒，功能降级而不是崩）：

| 权限 | 用途 | 可拒的后果 |
|---|---|---|
| `INTERNET` | 与模型 / 搜索 / MCP / WebDAV 通信 | 不可拒 |
| `VIBRATE` | 触觉反馈 | 无振动 |
| `POST_NOTIFICATIONS`（13+） | 生成中的实时通知、备份提醒、下载完成 | 不显示通知 |
| `POST_PROMOTED_NOTIFICATIONS`（Android 15+） | 状态栏实时活动芯片 | 芯片不显示 |
| `FOREGROUND_SERVICE` / `..._DATA_SYNC` | 生成与下载的常驻任务 | 后台生成会被系统回收 |
| `RECORD_AUDIO` | 语音输入（ASR）、语音朗读录音 | 语音输入不可用 |
| `CAMERA` | 拍照作为附件 | 只能用相册图 |
| `READ_MEDIA_IMAGES` / `..._USER_SELECTED`（13+）、`READ_EXTERNAL_STORAGE`（≤ 12） | 选图作为附件 | 只能现场拍摄或手动分享 |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | `get_current_location` 本地工具（前台一次性定位，不申请后台定位） | 模型拿不到位置 |
| `READ_CALENDAR` / `WRITE_CALENDAR` | 日历查询与创建工具 | 日历工具不可用 |
| `PACKAGE_USAGE_STATS`（系统设置里手动授予） | `get_screen_time` 本地工具 | 屏幕时间工具不可用 |
| `WRITE_EXTERNAL_STORAGE`（仅 ≤ 28） | 应用内下载 APK 更新包写公共 Download 目录 | 高版本不需要，改由系统下载器处理 |

## 本地构建

要求：

- JDK 21
- Android SDK：`compileSdk 36`、`targetSdk 35`、`minSdk 26`
- NDK `28.2.13676358` + CMake `3.22.1` —— `:core:workspace` 要编译 `termux_pty.cpp`，
  缺 NDK 时报 `[CXX1101] did not have a source.properties file`

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/
```

签名只在仓库根有 `keystore.properties` 时启用，那份文件与它指向的密钥库都**不入库**。
没有签名材料时 `:app:assembleRelease` 照样构建，只是产出未签名包（装不上）；自己试用请构建 debug。

> 两个环境坑：`JAVA_HOME` 指向 JDK 13 会让 AGP 直接失败；`GRADLE_USER_HOME` 若位于非 ASCII
> 用户名目录下，Gradle 的 `@argfile` worker classpath 在 CP936 JVM 上读不到，
> `testDebugUnitTest` 会以 `ClassNotFoundException: GradleWorkerMain` 失败。
> `tools/quality_gate.sh` 会 source 一个不入库的 `tools/quality_gate.local.sh`，可以把本机钉写在那里。

## 质量门禁

提交前跑：

```bash
bash tools/quality_gate.sh
```

依次是：全模块 `lintDebug` + `testDebugUnitTest` → `:app:assembleDebug` →
「每个有源码的模块至少有一个测试」→ 数据库 DDL 重新生成后必须**零 diff**。
CI（`.github/workflows/android-pr-check.yml`）执行同样的检查，不放宽任何一步。

界面细节、线程约束等还有机器守卫测试兜着：消息行必须保持 skippable、组合期禁止查库、
滚动命令禁止传 `Int.MAX_VALUE` 当偏移 —— 这些是实测踩坑后加的，改界面时如果碰到它们，
先看测试里的说明再动。

## 仓库结构

```
app/                 导航、界面、容器（绝大多数 UI 与 ViewModel 在这里）
core/common/         纯逻辑（技能解析、上下文压缩、日志脱敏…）
core/ui/             主题、语义色、字符串资源
core/highlight/      代码高亮
core/data/           SQLite DAO + 偏好/实体键存储
core/llm/            OkHttp SSE 与三家协议客户端
core/workspace/      沙箱：rootfs 安装与补丁、proot 运行器、PTY（含 NDK C++）
feature/*            按域拆分的骨架（当前 UI 仍在 :app）
tools/               质量门禁、生成器、审计脚本
```

## 参与

- **分支模型**：`master` 是唯一长期分支，保持可发布；日常改动从 `master` 切短分支走 PR，
  合并即删分支。发布从 `master` 打 tag。
- **版本策略**：`versionName` 用 `X.Y.Z` —— `X` 不兼容变更（数据格式或核心行为），
  `Y` 新增能力，`Z` 修复与体验改进。`versionCode` 每次发布 +1，只增不减
  （当前 `1.0.18` 对应 `versionCode 19`）。
- **变更日志**：`CHANGELOG.md` 每个版本一节，标题 `## [X.Y.Z] — YYYY-MM-DD`，按
  「新增 / 修复 / 改进」分组，写使用者能感知的结果，不留开发过程流水账。
  GitHub Release 的说明从对应小节摘录。
- **提 PR 前**：`bash tools/quality_gate.sh` 全绿；带界面改动的说明在真机上的验证情况，
  没验证的直说没验证。文案改动请三份语言一起改。
- **行为准则**：issue 和 PR 里对事不对人。讨论围绕可复现的行为、日志和代码，
  不接受针对使用者或群体的攻击。报 bug 请给：机型与安卓版本、复现步骤、
  相关日志片段（贴之前**把 API Key 打码**，日志本身已做脱敏但仍建议你复查一遍）。
- 我们不会往里加遥测、账号体系或后台同步，也不会为了让 CI 变绿放宽门禁。
  这两条如果哪天变了，会写在版本说明里。

## 许可与致谢

AGPL-3.0，见 [`LICENSE`](LICENSE)；第三方署名见 [`NOTICE.md`](NOTICE.md)。
按 AGPL-3.0，分发本代码或其修改版（包括通过网络向他人提供服务）必须同样以 AGPL-3.0 提供完整源码。

Memo 站在两个开源项目的肩膀上，两者也都是 AGPL-3.0：

- **[kelivo](https://github.com/Chevey339/kelivo)** by Chevey339 —— 这个产品的起点。
  它的界面语言、交互规范与功能取向决定了 Memo 长什么样；Memo 用 Kotlin + Jetpack Compose
  把这套设计重新实现到原生安卓，并在实现过程中按平台差异和实际使用反馈做了大量改动与新增。
  Memo 是独立仓库、独立发版：包名 `com.psyche.memo`、数据库 `memo.db`，与 Flutter 版的备份不互通。
- **[RikkaHub](https://github.com/rikkahub/rikkahub)**（AGPL-3.0）——
  安卓原生侧的实现参考。网络 TTS / ASR、主题预设、Agent Skills、沙箱工作区与终端，
  以及通知、下载、权限、文件共享这些平台细节，都参考过它的做法。
  另需说明：kelivo 的界面设计本身参考过 RikkaHub，所以这几条线在视觉上是相通的。

感谢这两个项目的作者把作品开源，让我们能在它们的基础上做自己的版本。

其他直接进了依赖的开源项目：commonmark / GFM 解析、jlatexmath-android、snakeyaml、Coil、
lucide-icons 等，完整清单见各模块的 `build.gradle.kts`。上下文压缩的阈值机制参照
[sst opencode](https://github.com/sst/opencode) 的会话压缩实现。
