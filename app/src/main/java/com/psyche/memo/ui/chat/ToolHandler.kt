package com.psyche.memo.ui.chat

import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.BuiltInToolCatalog
import com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * tool_handler_service.dart buildToolCallHandler 的 Native 分派：本地工具
 * （[com.psyche.memo.provider.LocalToolExecutors]）→ ask_user 交互服务 →
 * 上游那道"审批门"按用户 2026-09-25 的指示整块拆除，这里不再挂起等人批准。
 * Agent 浏览器（[com.psyche.memo.provider.browser.BrowserTools] 那一族 13 颗，app 级：不要求助手
 * 绑定任何东西，执行侧自己复查全局开关，且排在 MCP 之前、不进 `LocalToolNames`）→
 * MCP 透传（[com.psyche.memo.provider.mcp]）→ 兜底 execution_error。
 * 到这里没被接住的只有两种情况：模型编出不存在的工具名，或该工具在本机没有执行器
 * （iOS-only 的定位/天气/健康/提醒，以及未移植的 STDIO MCP）——如实回 execution_error
 * 让模型自行处置。
 *
 * **取消（用户点「停止」）不是工具失败**：本函数里每一支 `catch` 都先原样 rethrow
 * [CancellationException]，只有真异常才归一成写给模型的 `tool_error`。把它读成失败等于叫
 * 模型去重试一颗可能有副作用的动作，也和「打断请求必须随生成终止释放」这条纪律打架
 * （AGENTS / PORTING §5.59）。
 */
class ToolHandler(
    private val askUserService: AskUserInteractionService?,
    private val conversationId: String?,
    private val assistant: Assistant?,
    private val searchEngine: com.psyche.memo.provider.search.SearchEngine? = null,
    private val searchService: com.psyche.memo.data.model.SearchServiceOptions? = null,
    private val searchCommonOptions: com.psyche.memo.data.model.SearchCommonOptions =
        com.psyche.memo.data.model.SearchCommonOptions(),
    private val container: com.psyche.memo.AppContainerImpl? = null,
    private val isTemporary: Boolean = false,
) {

    /**
     * 处理一个工具调用，返回写回模型的内容（tool_error 为 JSON 字符串）。
     *
     * [onImage] 收集工具结果附带的图片 —— 上游 RikkaHub 的工具结果本身就是
     * Text/Image 混排的 part 列表，Memo 的 `handle` 只回字符串，所以图片用回调
     * 交给调用方（它负责把图片挂进工具 part 的 payload）。
     */
    suspend fun handle(
        name: String,
        args: JsonObject,
        toolCallId: String?,
        onImage: (com.psyche.memo.data.model.ToolImage) -> Unit = {},
    ): String {
        return try {
            // Search tool (tool_handler_service.dart L435-439).
            if (name == com.psyche.memo.provider.search.SearchToolService.TOOL_NAME &&
                assistant?.searchEnabled == true
            ) {
                val query = (args["query"] as? JsonPrimitive)?.contentOrNull ?: ""
                val engine = searchEngine
                    ?: return toolError(
                        error = "search_unavailable",
                        message = "Search engine is unavailable.",
                        tool = name,
                        instruction = "Web search could not run. Answer from your own knowledge " +
                            "and tell the user the result may be out of date.",
                    )
                return com.psyche.memo.provider.tool.ToolRunner.cap(com.psyche.memo.provider.search.SearchToolService.executeSearch(
                    query = query,
                    engine = engine,
                    service = searchService,
                    common = searchCommonOptions,
                ))
            }
            // 沙箱工作区（WorkspaceTools）：助手绑定了工作区才生效。审批门已拆除
            // （用户 2026-09-25「工具的权限审批全部去掉」），连 shell 也直接执行 ——
            // 它跑在 proot rootfs 里，搞坏的是沙箱自己；写到可写区之外由工具自己拒。
            if (container != null && assistant != null &&
                name in com.psyche.memo.provider.workspace.WorkspaceTools.ALL_TOOL_NAMES
            ) {
                val workspaceId = assistant.workspaceId
                if (!workspaceId.isNullOrBlank()) {
                    val tools = com.psyche.memo.provider.workspace.WorkspaceTools
                    return when (
                        val outcome = tools.execute(
                            repo = container.workspaceRepository,
                            workspaceId = workspaceId,
                            cwd = assistant.workspaceCwd,
                            name = name,
                            args = args,
                            reads = container.workspaceReadLedger,
                            conversationId = conversationId,
                        )
                    ) {
                        is com.psyche.memo.provider.workspace.WorkspaceTools.Outcome.Success -> {
                            // 读图片：字节落盘成一个文件，再作为工具结果的图片交给调用方
                            // （上游把字节交给 FilesManager 出 Image part，等价物就是这里）。
                            outcome.image?.let { bytes -> persistToolImage(bytes)?.let(onImage) }
                            com.psyche.memo.provider.tool.ToolRunner.cap(outcome.json)
                        }
                        is com.psyche.memo.provider.workspace.WorkspaceTools.Outcome.Failure ->
                            toolError(
                                error = outcome.error,
                                message = outcome.message,
                                tool = name,
                                // 「未读不可改」「读过之后文件又变了」这类必须点名下一步，
                                // 通用那句会让模型原地再敲同一颗 edit。
                                instruction = outcome.instruction
                                    ?: com.psyche.memo.provider.tool.ToolResults.ADJUST_AND_RETRY,
                                violations = outcome.violations,
                            )
                    }
                }
                // 助手没绑工作区却调了 `workspace_*`：直说缺什么（工具纪律第 3 条）。
                // 让它落到下面那句「本机没有执行器」会误报成平台不支持，那是另一个原因。
                return toolError(
                    error = "workspace_not_bound",
                    message = "This assistant is not bound to a workspace, so `$name` cannot run.",
                    tool = name,
                    instruction = "Ask the user to pick a workspace for this assistant (input bar " +
                        "→ workspace). Do not claim any file was read, written or executed.",
                )
            }

            // 生成图片 / 生成视频（GenerationTools，自研功能）：助手在「生成图片 /
            // 生成视频」tab 里选了服务才提供。图片结果作为工具附带的图片挂进工具 part；
            // 视频是异步长任务，这里一直等到终态（或超时）再返回，产物是一个 mp4 路径。
            if (container != null && assistant != null &&
                name in com.psyche.memo.provider.generation.GenerationTools.ALL_TOOL_NAMES
            ) {
                val tools = com.psyche.memo.provider.generation.GenerationTools
                val isImage = name == tools.GENERATE_IMAGE
                val binding = if (isImage) assistant.imageGeneration else assistant.videoGeneration
                val service = tools.serviceFor(container.generationServices, binding)
                    ?: return toolError(
                        error = "generation_unavailable",
                        message = "No $name service is configured for this assistant. " +
                            "Ask the user to pick one in the assistant's generation tab.",
                        tool = name,
                    )
                return try {
                    val result = tools.execute(
                        service = service,
                        binding = binding,
                        name = name,
                        args = args,
                        clients = com.psyche.memo.provider.generation.GenerationTools.Clients(
                            images = com.psyche.memo.provider.generation.ImageGenerationClient(
                                container.httpClient,
                                container.generatedMediaStore,
                            ),
                            videos = com.psyche.memo.provider.generation.VideoGenerationClient(
                                container.httpClient,
                                container.generatedMediaStore,
                            ),
                        ),
                    )
                    // 产物不挂工具 part：由调用方（ChatViewModel）把生成结果作为一条
                    // 消息插进对话（与 ➕ 面板同一条路径），避免同一张图出现两次。
                    result.json
                } catch (e: CancellationException) {
                    throw e // 取消不是生成失败，见类注释
                } catch (e: Exception) {
                    toolError(
                        error = "generation_failed",
                        message = e.message ?: e.toString(),
                        tool = name,
                        instruction = "Image/video generation failed. Tell the user what went wrong.",
                    )
                }
            }

            // Agent 浏览器（BrowserTools 那一族 13 颗）：app 级工具，只看全局开关，不要求助手
            // 绑定任何东西。必须在 MCP 分支之前 —— 同名 MCP 工具不该顶掉它们（offeredTools 那边
            // 也留了名）。
            if (name in com.psyche.memo.provider.browser.BrowserTools.ALL_TOOL_NAMES &&
                container != null
            ) {
                // **执行侧复查开关**（与上面搜索那支复查 `assistant?.searchEnabled` 同口径）：
                // `offeredTools()` 只是不再递这族工具，模型照旧发它们是现实路径（被污染的网页正文
                // 指挥它 —— spec §4/§9 记的残余风险）。必须排在 `sessionFor` **之前**：那一句才
                // 是建实例的地方，排在它后面就等于用户关了开关还送出真 WebView + cookie jar。
                // 这是一句**拒绝**，不是审批（用户 2026-09-25 明令整块拆除，PORTING §5.68）：
                // 不弹确认、不挂起等人。
                com.psyche.memo.provider.browser.BrowserTools.rejectIfDisabled(
                    com.psyche.memo.ui.DisplayPrefs.browserEnabled(container),
                )?.let { return it }
                val gateway = container.browserSessions.sessionFor(conversationId ?: "")
                // 走 ToolRunner.run 而不是裸 cap：异常归一 + 超时口径与所有本地工具一致
                // （取消由下面 `catch (e: CancellationException)` 那支透传，不再靠这里绕）。
                return com.psyche.memo.provider.tool.ToolRunner.run(tool = name) {
                    com.psyche.memo.provider.browser.BrowserTools.execute(name, gateway, args) { bytes ->
                        persistToolImage(bytes)?.let(onImage)
                    }
                } ?: toolError(
                    // 可空性接缝：`run` 的返回是 `String?`（null = 该工具无人负责，继续派发），
                    // 而 BrowserTools.execute 永远回话 ⇒ 这一支到不了。超时不是这里：超时由 `run`
                    // 自己回 tool_timeout。留着只为让编译器相信「一定有写给模型的东西」。
                    error = "browser_unavailable",
                    message = "The browser session returned nothing.",
                    tool = name,
                    instruction = "Tell the user the browser did not respond this time; do not " +
                        "claim any page was read or any action was taken.",
                )
            }

            // MCP tools: the assistant's bound servers, tool names not reserved
            // by built-ins.
            if (container != null && assistant != null && name !in com.psyche.memo.ui.BuiltInToolCatalog.LocalToolNames.all) {
                val serverId = assistant.mcpServerIds.firstOrNull { id ->
                    container.mcpConnections.isConnected(id) &&
                        container.mcpConnections.toolsFor(id).any { it.name == name }
                }
                if (serverId != null) {
                    return try {
                        com.psyche.memo.provider.tool.ToolRunner.cap(container.mcpConnections.callTool(serverId, name, args))
                    } catch (e: CancellationException) {
                        throw e // 取消不是远端调用失败，见类注释
                    } catch (e: Exception) {
                        toolError(
                            error = "execution_error",
                            message = e.toString(),
                            tool = name,
                            instruction = "The tool execution failed unexpectedly. You may try again with different parameters or inform the user about the issue.",
                        )
                    }
                }
            }

            // Memory tools (memory_tools.dart handle)：enableMemory 才生效。
            container?.let { c ->
                com.psyche.memo.provider.MemoryTools.handle(
                    container = c,
                    assistant = assistant,
                    conversationId = conversationId,
                    isTemporary = isTemporary,
                    name = name,
                    args = args,
                )?.let { return it }
            }

            // Agent Skills（SkillsTools.execute）：只有助手启用、且磁盘上存在的技能
            // 才允许加载；模型编出来的名字或目录外路径都如实报错让它自己纠正。
            if (name == com.psyche.memo.provider.SkillTools.USE_SKILL &&
                container != null &&
                assistant != null
            ) {
                val skillName = (args["name"] as? JsonPrimitive)?.contentOrNull
                    ?: return toolError(
                        error = "invalid_use_skill_request",
                        message = "name is required",
                        tool = name,
                        instruction = "Call $name with the exact skill name from the " +
                            "available-skills catalog.",
                    )
                val available = com.psyche.memo.provider.SkillTools.availableSkills(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = container.skillStore.listSkills(),
                )
                val skill = available.firstOrNull { it.name == skillName }
                    ?: return toolError(
                        // 名字失效时**当场**把墓碑再立一次：历史里那次成功的调用还留在模型的
                        // 上下文里，它不然会一直敲同一个旧名字。
                        error = "skill_not_available",
                        message = if (available.isEmpty()) {
                            "Skill '$skillName' is not available. " +
                                com.psyche.memo.provider.SkillTools.NO_SKILLS_TOMBSTONE
                        } else {
                            "Skill '$skillName' is not available. " +
                                "Available skills: ${'$'}{available.joinToString { it.name }}"
                        },
                        tool = name,
                        instruction = if (available.isEmpty()) {
                            "Do not try other skill names. Tell the user to import or enable a " +
                                "skill, then continue without one."
                        } else {
                            "Load one of the listed skills instead, or answer without a skill " +
                                "and say which skill you expected."
                        },
                    )
                val path = (args["path"] as? JsonPrimitive)?.contentOrNull
                return when (val outcome = com.psyche.memo.provider.SkillTools.execute(skill, path)) {
                    is com.psyche.memo.provider.SkillTools.Outcome.Success ->
                        com.psyche.memo.provider.tool.ToolRunner.cap(outcome.content)
                    is com.psyche.memo.provider.SkillTools.Outcome.Failure ->
                        toolError(outcome.error, outcome.message, name)
                }
            }

            // Local tools (local_tools_service.dart tryHandleToolCall 451-529):
            // time_info + the executor subset in LocalToolExecutors.
            if (name == LocalToolNames.TIME_INFO &&
                assistant != null &&
                assistant.localToolIds.contains(name)
            ) {
                return timeInfoJson()
            }
            if (assistant != null &&
                assistant.localToolIds.contains(name) &&
                name in com.psyche.memo.provider.LocalToolExecutors.EXECUTABLE &&
                container != null
            ) {
                // 统一骨架（dsh 的 execute() 契约，见 provider/tool/ToolExecution.kt）：
                // deadline + 异常归一 + 结果上限，都在 ToolRunner 里，不散进各工具。
                com.psyche.memo.provider.tool.ToolRunner
                    .run(tool = name) {
                        com.psyche.memo.provider.LocalToolExecutors
                            .execute(
                                context = container.appContext,
                                name = name,
                                args = args,
                                // 图表工具要跟主题取色（外壳跟主题、系列色固定）。
                                chartPalette = com.psyche.memo.provider.chart.VisualTools
                                    .paletteFor(container),
                                // 定位工具要有运行时权限，而只有界面手里有 ActivityResultRegistry
                                // ⇒ 借容器那根「挂起等弹窗结果」的通道（见 LocationPermissionService）。
                                locationPermission = {
                                    container.locationPermissionService.awaitGrant()
                                },
                            )
                    }
                    ?.let { return it }
            }

            if (name == AskUserToolNames.ASK_USER &&
                assistant != null &&
                assistant.localToolIds.contains(AskUserToolNames.ASK_USER)
            ) {
                if (askUserService == null) {
                    return toolError(
                        error = "ask_user_unavailable",
                        message = "Ask user interaction service is unavailable.",
                        tool = name,
                        instruction = "You cannot put a question to the user right now. State the " +
                            "ambiguity in your reply and pick the most reasonable interpretation.",
                    )
                }
                return try {
                    askUserService.requestAnswer(
                        toolCallId = toolCallId?.trim()?.takeIf { it.isNotEmpty() }
                            ?: "${name}_${System.currentTimeMillis() * 1000}",
                        arguments = args,
                        conversationId = conversationId,
                    ).await().jsonString
                } catch (e: AskUserInvalidRequestException) {
                    toolError(
                        error = "invalid_ask_user_request",
                        message = e.message ?: "",
                        tool = name,
                        instruction = "Fix the question payload (each question needs a text, and " +
                            "multiple-choice questions need at least one option) and ask again.",
                    )
                }
            }

            // Dart falls through to the MCP call here; the native executor set
            // is unported, so an offered-but-unexecutable tool reports honestly.
            toolError(
                error = "execution_error",
                message = "Tool '$name' has no executor on this platform.",
                tool = name,
                instruction = "The tool execution failed unexpectedly. You may try again with different parameters or inform the user about the issue.",
            )
        } catch (e: CancellationException) {
            // **取消透传，不许被读成工具失败**（用户点「停止」= 协程被取消，不是一次执行出错）。
            // 这一支过去没有：`CancellationException extends RuntimeException`，所以下面那支通用
            // `catch (e: Exception)` 把它归成 `execution_error` + 「You may try again with
            // different parameters」——三个后果：① 对 click/type/写文件这类**有副作用**的动作，
            // 模型在「可能已经做了」的情况下被告知重试（`ToolRunner` 的 tool_timeout 分支刻意
            // 躲的就是这一类）；② 与本仓「打断请求必须随生成终止释放」的纪律对不上
            //（AGENTS / PORTING §5.59）；③ 上层 `generationJob` 的取消语义被一条正常返回的工具
            // 结果冒充掉。影响面是全仓工具（ask_user 与所有本地工具同一处误标一起修好）。
            throw e
        } catch (e: Exception) {
            toolError(
                error = "execution_error",
                message = e.toString(),
                tool = name,
                instruction = "The tool execution failed unexpectedly. You may try again with different parameters or inform the user about the issue.",
            )
        }
    }

    /**
     * tool_handler_service.dart _toolError 179-192 —— 形状只有一个生产者
     * ([com.psyche.memo.provider.tool.ToolResults])：`type=tool_error` + `status=error` +
     * `tool` + **必带的 `instruction`**。每条错误都得交代下一步，否则模型只会原地再敲
     * 同一颗调用（deepseek-harness 的 post-execute 块就是这个道理）。
     */
    private fun toolError(
        error: String,
        message: String,
        tool: String,
        instruction: String = com.psyche.memo.provider.tool.ToolResults.ADJUST_AND_RETRY,
        violations: List<com.psyche.memo.provider.tool.ArgViolation> = emptyList(),
    ): String = com.psyche.memo.provider.tool.ToolResults.error(
        code = error,
        message = message,
        tool = tool,
        instruction = instruction,
        violations = violations,
    )

    /** local_tools_service.dart 460-461 — get_time_info 返回 `jsonEncode(_buildTimeInfoPayload(...))`。 */
    private fun timeInfoJson(): String =
        buildTimeInfoPayload(ZonedDateTime.now()).toString()

    /** local_tools_service.dart `_buildTimeInfoPayload` 1048-1077 的 java.time 移植。 */
    internal fun buildTimeInfoPayload(now: ZonedDateTime): JsonObject {
        val totalSeconds = now.offset.totalSeconds
        val sign = if (totalSeconds < 0) "-" else "+"
        val absSeconds = kotlin.math.abs(totalSeconds)
        val offsetHours = (absSeconds / 3600).toString().padStart(2, '0')
        val offsetMinutes = ((absSeconds % 3600) / 60).toString().padStart(2, '0')

        val year = now.year.toString().padStart(4, '0')
        val month = now.monthValue.toString().padStart(2, '0')
        val day = now.dayOfMonth.toString().padStart(2, '0')
        val hour = now.hour.toString().padStart(2, '0')
        val minute = now.minute.toString().padStart(2, '0')
        val second = now.second.toString().padStart(2, '0')
        val weekdayEn = englishWeekdayName(now.dayOfWeek)

        return buildJsonObject {
            put("year", now.year)
            put("month", now.monthValue)
            put("day", now.dayOfMonth)
            put("weekday", weekdayEn)
            put("weekday_en", weekdayEn)
            put("weekday_index", now.dayOfWeek.value)
            put("date", "$year-$month-$day")
            put("time", "$hour:$minute:$second")
            // DateTime.toIso8601String() — local, always 6-digit microseconds.
            put("datetime", isoLocalDateTime(now))
            // Dart timeZoneName (local name); native exposes the zone id instead.
            put("timezone", now.zone.id)
            put("utc_offset", "$sign$offsetHours:$offsetMinutes")
            put("timestamp_ms", now.toInstant().toEpochMilli())
        }
    }

    private fun isoLocalDateTime(now: ZonedDateTime): String {
        val dt = now.toLocalDateTime()
        return String.format(
            "%04d-%02d-%02dT%02d:%02d:%02d.%06d",
            dt.year, dt.monthValue, dt.dayOfMonth,
            dt.hour, dt.minute, dt.second, dt.nano / 1000,
        )
    }

    private fun englishWeekdayName(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "Monday"
        DayOfWeek.TUESDAY -> "Tuesday"
        DayOfWeek.WEDNESDAY -> "Wednesday"
        DayOfWeek.THURSDAY -> "Thursday"
        DayOfWeek.FRIDAY -> "Friday"
        DayOfWeek.SATURDAY -> "Saturday"
        DayOfWeek.SUNDAY -> "Sunday"
    }

    /**
     * 把工具读到的图片字节落成文件 —— 图片要随工具结果持久化（重开对话还在），
     * 所以放 `filesDir` 而不是 cacheDir；目录独立于 upload/，不污染上传管理器列表。
     */
    private fun persistToolImage(
        image: com.psyche.memo.provider.tool.ToolImageBytes,
    ): com.psyche.memo.data.model.ToolImage? {
        val context = container?.appContext ?: return null
        return runCatching {
            val dir = java.io.File(context.filesDir, TOOL_IMAGES_DIR).apply { mkdirs() }
            val file = java.io.File(dir, "${System.currentTimeMillis()}_${image.name}")
            file.writeBytes(image.bytes)
            com.psyche.memo.data.model.ToolImage(uri = file.absolutePath, mime = null)
        }.onFailure { error ->
            android.util.Log.w("ToolHandler", "Failed to persist tool image", error)
        }.getOrNull()
    }

    private companion object {
        const val TOOL_IMAGES_DIR = "tool_images"
    }
}
