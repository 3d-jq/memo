# Memo

原生 Android 的大模型聊天客户端（Kotlin + Jetpack Compose）。
A native Android LLM chat client, written in Kotlin and Jetpack Compose.

Memo 是 Flutter 应用 **[kelivo](https://github.com/Chevey339/kelivo)**（AGPL-3.0）的
**1:1 原生移植**：界面、布局尺寸、文案、图标与行为都按 Dart 源码逐屏翻译，不自创 UI。
本项目与上游同为 AGPL-3.0，署名与第三方来源见 [`NOTICE.md`](NOTICE.md)，逐批翻译记录与
**有意偏离上游的清单**见 [`docs/PORTING.md`](docs/PORTING.md)。

仓库：**<https://github.com/3d-jq/memo>** —— Issue 与 PR 都开在这里。

> 本仓库与上游**不互通数据**：数据库是 `memo.db`，包名是 `com.psyche.memo`。
> 表结构仍由上游 drift schema 生成，只因为那是一套被验证过的 schema。

## 功能

- **多供应商对话**：OpenAI Chat Completions / Responses、Anthropic Messages、Gemini；流式 SSE、
  自动重试、fallback 模型链、思考预算、上下文压缩（阈值式锚定摘要）
- **工具**：本地工具（时间/剪贴板/TTS/计算/屏幕时间/日历/定位…）、`ask_user` 问询、工具审批、
  MCP 服务器（含 OAuth）、Agent Skills、沙箱工作区（proot + 交互式 PTY）、可视化绘图
- **记忆**：抽取/蒸馏 pipeline、智能合并、世界书、指令注入
- **多模态**：图片/文档输入与转码、OCR、语音（12 家网络 TTS + 7 种 ASR）、悬浮语音播放器
- **数据**：备份（本机副本 / WebDAV / Cherry Studio / Chatbox 导入）、用量统计、请求日志
- **界面**：抽屉、侧栏搜索、主题预设、Markdown/LaTeX/代码高亮、中英繁三语

## 模块

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

## 构建

要求：JDK 21、Android SDK（compileSdk 35 / minSdk 26 / targetSdk 35）、
**NDK 28.2.13676358 + CMake 3.22.1**（`:core:workspace` 的 `termux_pty.cpp` 需要）。

```bash
# local.properties 里指向你的 SDK
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/
```

> `JAVA_HOME` 若指向 JDK 13 之类的旧版本会直接构建失败；`GRADLE_USER_HOME` 若位于
> 非 ASCII 用户目录下，Gradle 的 `@argfile` worker classpath 在 CP936 JVM 上会读不到，
> `testDebugUnitTest` 会以 `ClassNotFoundException: GradleWorkerMain` 失败。
> `tools/quality_gate.sh` 会把两者钉住。

## 质量门禁

提交前必须全绿：

```bash
bash tools/quality_gate.sh
```

依次跑：全模块 `lintDebug` + `testDebugUnitTest` → `:app:assembleDebug` →
"每个有源码的模块至少有一个测试" → 四个生成器重新生成后必须**零 diff**。
CI（`.github/workflows/android-pr-check.yml`）执行同样的检查。

生成资源是**入库**的，改上游输入后要重新生成：

| 生成器 | 输入 | 输出 |
|---|---|---|
| `tools/arb_to_android.py` | `upstream/lib/l10n/*.arb` | `core/ui/.../res/values*/strings.xml` |
| `tools/settings_keys_gen.py` | `upstream/lib/core/database/business_*.dart` | `SettingsKeyRegistry.kt` |
| `tools/palettes_gen.py` | `upstream/lib/theme/palettes.dart` | `Palettes.kt` |
| `tools/drift_schema_to_sql.py` | `upstream/drift_schemas/.../drift_schema_v3.json` | `assets/memo_schema_v3.sql` |

`upstream/` 是上游 Dart 源里被生成器读取的那一小部分，随本仓库一起以 AGPL-3.0 再分发，
这样独立 checkout 也能跑完整门禁；仓库内没有任何东西由 Dart 编译。
（在包含上游 Flutter 工程的开发布局下，生成器会自动回退去读同级的 `lib/`。）

## 许可证

AGPL-3.0，见 [`LICENSE`](LICENSE) 与 [`NOTICE.md`](NOTICE.md)。
本项目不是 kelivo 官方的一部分，与原作者无隶属关系。
