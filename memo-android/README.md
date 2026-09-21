<p align="center">
  <img src="docs/icon.png" width="128" alt="Memo">
</p>

# Memo

一个**完全跑在你自己手机上的**大模型聊天客户端：Kotlin + Jetpack Compose 原生实现，
没有账号、没有自建服务器。你的 API Key 只存在本机数据库里，聊天记录、附件、日志、备份
全在应用私有目录 —— 除了你亲手配置的那家模型服务商，没有任何数据会发给第三方。

A native Android LLM chat client — no account, no backend, everything local.

## 特性

**接你自己的模型**
OpenAI（Chat Completions / Responses）、Anthropic Messages、Google Gemini 三套协议原生实现，
DeepSeek、智谱、月之暗面、通义、小米 Mimo 等国内厂商均有真机实测记录。模型列表可在线拉取并
批量导入，每个模型可单独覆盖上下文长度、采样参数、思考预算与请求头。

**流式与思考链**
增量渲染、思维链折叠卡、按段拆分的回复气泡。请求失败自动重试（状态码 + 关键词双触发、
指数退避、可配停止词），主模型挂了自动走 fallback 链。错误一律翻成人话，不把 provider
的原始 JSON 甩给你。

**上下文压缩**
按 opencode 的阈值机制：估算 `system + messages + tools` 的 token，超过
「上下文窗口 − max(输出预算, buffer)」时，在同一会话里把较早的上下文归纳成一条**锚定摘要检查点**，
而不是新建会话。上下文占用只在「上下文管理」面板里看，平时不打扰你。

**工具与代理**
- 本地工具：时间、剪贴板、朗读、计算器、屏幕时间、日历读写、**当前位置**（安卓侧自写，
  10 分钟内缓存秒回、实时定位 10 秒超时、拿不到地址就只给坐标）
- 工具审批：每个敏感工具可单独设为需要确认，参数摘要直接显示在对话里
- `ask_user`：模型可以反问，一题一页地在输入栏位置作答
- **MCP**：连接外部 MCP 服务器，含 OAuth 2.1 授权流程（RFC 9728 / 8414 + DCR + PKCE，
  系统浏览器 + 本地回环回调）
- **Agent Skills**：`<filesDir>/skills/<名>/SKILL.md` 就是技能，支持手动粘贴 / 文件 /
  GitHub 仓库三种导入，内置 skill-creator
- **沙箱工作区**：proot 容器里跑真 shell，带交互式 PTY 终端（多标签 + 附加键栏）、
  文件读写编辑工具、Node / Python / 常用命令行工具的一键安装（默认国内镜像源），
  可以取消、可以卸载、挂载 `/skills` 与 `/upload`

**记忆**
从对话里抽取—蒸馏—合并长期记忆（四动作判定 + 批量 judge），带流程追踪页可看每一步为什么
被写入或被丢弃；世界书支持 5 种注入位置；指令注入可开关。

**多模态与语音**
图片/文档输入（HEIC 自动转码、按字节嗅探 MIME、OCR、文档正文抽取），12 家网络 TTS +
7 种云端 ASR，悬浮语音播放器支持暂停续读、±15 秒定位与 0.8–2.0 倍速。

**联网搜索**
23 个可运行的搜索 provider（Tavily / Bing / Brave / Exa / Serper / SSE 系列…），
结果以引用胶囊挂回消息，用量可统计。

**数据与备份**
一键归档为 zip，支持本机副本（保留策略 + 定时调度 + 置顶/导出/恢复）、WebDAV 远端备份、
从 Cherry Studio 与 Chatbox 导入。恢复走 ATTACH 快照 + 指纹去重的合并策略，并有
**前向兼容闸门**：高版本备份不会静默毁掉你的库。请求日志三件套（上下文/应用/请求）
带自动脱敏，出问题能自己查。

**界面**
Markdown + GFM + LaTeX 渲染、代码块折叠/换行/高亮/HTML 预览、Mermaid 与自绘可视化图、
侧边抽屉全局搜索、消息多选与导出、四列色卡的主题预设、iOS 风格控件与触觉反馈、
简中/繁中/英文三种语言。

## 下载

从 [Releases](https://github.com/3d-jq/memo/releases) 下载 APK 直接安装即可，
不需要 Google Play，也不需要 root。首次启动进「设置 → 供应商」填你自己的 API Key。

## 构建

要求：JDK 21、Android SDK（compileSdk 35 / minSdk 26 / targetSdk 35）、
**NDK 28.2.13676358 + CMake 3.22.1**（`:core:workspace` 的 `termux_pty.cpp` 需要）。

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/
```

包名是 `com.psyche.memo`，debug 变体带 `.dev` 后缀，可以和正式版共存。

> 两个环境坑：`JAVA_HOME` 指向 JDK 13 会直接构建失败；`GRADLE_USER_HOME` 若位于非 ASCII
> 用户名目录下，Gradle 的 `@argfile` worker classpath 在 CP936 JVM 上读不到，
> `testDebugUnitTest` 会以 `ClassNotFoundException: GradleWorkerMain` 失败。
> `tools/quality_gate.sh` 会 source 一个不入库的 `tools/quality_gate.local.sh`，
> 你可以把本机钉选写在那里。

## 工程结构

```
app/                 导航、界面、容器（绝大多数 UI 在这里）
core/common/         纯逻辑（技能解析、上下文压缩、日志脱敏…）
core/ui/             主题、语义色、生成的字符串资源
core/highlight/      代码高亮
core/data/           SQLite DAO + 偏好/实体键存储
core/llm/            OkHttp SSE 与三家协议客户端
core/workspace/      沙箱：rootfs 安装/补丁、proot 运行器、PTY（含 NDK C++）
feature/*            按域拆分的骨架（当前 UI 仍在 :app）
```

## 质量门禁

提交前必须全绿：

```bash
bash tools/quality_gate.sh
```

依次跑：全模块 `lintDebug` + `testDebugUnitTest` → `:app:assembleDebug` →
「每个有源码的模块至少有一个测试」→ 四个生成器重新生成后必须**零 diff**。
CI（`.github/workflows/android-pr-check.yml`）执行同样的检查，不放宽任何一步。

生成资源是**入库**的，改上游输入后要重新生成：

| 生成器 | 输入 | 输出 |
|---|---|---|
| `tools/arb_to_android.py` | `upstream/lib/l10n/*.arb` | `core/ui/.../res/values*/strings.xml` |
| `tools/settings_keys_gen.py` | `upstream/lib/core/database/business_*.dart` | `SettingsKeyRegistry.kt` |
| `tools/palettes_gen.py` | `upstream/lib/theme/palettes.dart` | `Palettes.kt` |
| `tools/drift_schema_to_sql.py` | `upstream/drift_schemas/.../drift_schema_v3.json` | `assets/memo_schema_v3.sql` |

`upstream/` 只是生成器的输入，仓库里没有任何东西由 Dart 编译。

## 许可证

AGPL-3.0，见 [`LICENSE`](LICENSE)。衍生作品必须同样以 AGPL-3.0 开源并提供源码。

## 致谢与来源

Memo 不是从零设计的，它站在两个开源项目的肩膀上：

- **[kelivo](https://github.com/Chevey339/kelivo)**（AGPL-3.0）— 产品形态、界面布局、
  交互细节、文案与数据模型都来自它。`docs/PORTING.md` 里逐屏标注了对应的 Dart 源文件与行号，
  `upstream/` 目录保留了被生成器读取的上游源文件，署名与许可证义务见 [`NOTICE.md`](NOTICE.md)。
- **[RikkaHub](https://github.com/rikkahub/rikkahub)**（AGPL-3.0）— 安卓原生侧的实现参考：
  网络 TTS/ASR、主题预设、Agent Skills、沙箱工作区与终端、部分请求管线细节都参考或移植自它。

也感谢这些直接进了依赖的开源库：commonmark / GFM 解析、jlatexmath-android、
snakeyaml、Coil、lucide-icons 等。
