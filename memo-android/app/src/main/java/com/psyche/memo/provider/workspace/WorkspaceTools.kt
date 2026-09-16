package com.psyche.memo.provider.workspace

import com.psyche.memo.llm.client.LlmToolSpec
import com.psyche.memo.workspace.WorkspaceFileEntry
import com.psyche.memo.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

/**
 * 沙箱工作区的工具面 —— 1:1 移植 RikkaHub `data/ai/tools/WorkspaceTools.kt`。
 *
 * 四个工具：`workspace_read_file` / `workspace_write_file` / `workspace_edit_file` /
 * `workspace_shell`。路径一律是 **rootfs 内的绝对路径**（工作区文件区挂在 `/workspace`）。
 *
 * 与上游一致的三个关键设计：
 *  1. **写文件是走 shell 的**（`cat > path` + stdin），读文件走 `rootfsFileSize` +
 *     `exportRootfsFile` —— 因为 proot 只暴露一个执行入口，直接碰宿主文件会绕过 rootfs
 *     的路径映射（bind mount 就看不懂了）。
 *  2. **免审批的安全可写区**只有 `/workspace` 与 `/tmp`；写到别处自动升级为需要审批。
 *  3. `workspace_shell` 默认需要审批（[DEFAULT_APPROVALS]），其余三个默认免审批。
 *
 * 与上游的唯一缺口：`workspace_read_file` 读**图片**那条分支没移植（上游把字节交给
 * `FilesManager` 生成图片 part；Memo 的工具结果要接进它自己的图片通道，见 PORTING）。
 * 文本读取、写入、编辑、执行四件事都是完整的。
 */
object WorkspaceTools {

    const val READ_FILE = "workspace_read_file"
    const val WRITE_FILE = "workspace_write_file"
    const val EDIT_FILE = "workspace_edit_file"
    const val SHELL = "workspace_shell"

    val ALL_TOOL_NAMES = setOf(READ_FILE, WRITE_FILE, EDIT_FILE, SHELL)

    /** 上游 `WorkspaceToolDefaultApprovals`：只有 shell 默认要审批。 */
    val DEFAULT_APPROVALS: Map<String, Boolean> = mapOf(
        READ_FILE to false,
        WRITE_FILE to false,
        EDIT_FILE to false,
        SHELL to true,
    )

    /** 上游 `resolveWorkspaceToolApproval`：工作区上的覆盖项优先，其次默认值。 */
    fun resolveApproval(name: String, overrides: Map<String, Boolean>): Boolean =
        overrides[name] ?: DEFAULT_APPROVALS[name] ?: false

    /** 免强制审批的可写安全区（上游 `WRITABLE_ROOT_PREFIXES`）。 */
    private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp")

    private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
    private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

    // ---------------------------------------------------------------- 定义

    /** 助手没绑工作区（`workspaceId` 为空）时整颗不提供。 */
    fun buildDefinitions(workspaceId: String?, cwd: String? = null): List<LlmToolSpec> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        return catalogDefinitions(cwd)
    }

    /**
     * 不依赖「助手绑没绑工作区」的定义副本 —— 设置 →「工具描述」的工具目录用它列条目。
     * [cwd] 只影响 `workspace_shell` 描述里那句「默认工作目录」，目录里用默认值。
     */
    fun catalogDefinitions(cwd: String? = null): List<LlmToolSpec> {
        val defaultCwd = shellCwd(cwd)
        return listOf(
            LlmToolSpec(READ_FILE, READ_DESCRIPTION, readParametersJson()),
            LlmToolSpec(WRITE_FILE, WRITE_DESCRIPTION, writeParametersJson()),
            LlmToolSpec(EDIT_FILE, EDIT_DESCRIPTION, editParametersJson()),
            LlmToolSpec(SHELL, shellDescription(defaultCwd), shellParametersJson(defaultCwd)),
        )
    }

    private val READ_DESCRIPTION = """
        Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " ")

    private val WRITE_DESCRIPTION = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " ")

    private val EDIT_DESCRIPTION = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " ")

    private fun shellDescription(defaultCwd: String?): String = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) append("Defaults to '$defaultCwd'. ")
        append("Requires Rootfs to be installed and ready.")
    }

    /** 上游 `shellCwd`：把 `/workspace/...` 剥成相对工作区根的路径。 */
    fun shellCwd(cwd: String?): String? =
        cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    private fun pathProperty(required: Boolean) = buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            },
        )
    }

    fun readParametersJson(): String = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("path", pathProperty(required = true)) })
        put("required", buildJsonArray { add(JsonPrimitive("path")) })
    }.toString()

    fun writeParametersJson(): String = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", pathProperty(required = true))
            put("text", buildJsonObject {
                put("type", "string")
                put("description", "UTF-8 text content to write")
            })
            put("overwrite", buildJsonObject {
                put("type", "boolean")
                put("description", "Whether to overwrite an existing file. Defaults to true.")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("path")); add(JsonPrimitive("text")) })
    }.toString()

    fun editParametersJson(): String = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", pathProperty(required = true))
            put("old_text", buildJsonObject {
                put("type", "string")
                put("description", "Exact text to replace")
            })
            put("new_text", buildJsonObject {
                put("type", "string")
                put("description", "Replacement text")
            })
            put("replace_all", buildJsonObject {
                put("type", "boolean")
                put("description", "Whether to replace every occurrence. Defaults to false.")
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("path")); add(JsonPrimitive("old_text")); add(JsonPrimitive("new_text"))
        })
    }.toString()

    fun shellParametersJson(defaultCwd: String? = null): String = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("command", buildJsonObject {
                put("type", "string")
                put("description", "Shell command to run")
            })
            put("cwd", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    if (!defaultCwd.isNullOrBlank()) {
                        "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                    } else {
                        "Working directory relative to the workspace files root. Defaults to root."
                    },
                )
            })
            put("timeout", buildJsonObject {
                put("type", "integer")
                put(
                    "description",
                    "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS.",
                )
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("command")) })
    }.toString()

    // ---------------------------------------------------------------- 纯工具

    /** 上游 `putPathProperty`/`absolutePath`：必须是 rootfs 内的绝对路径。 */
    fun absolutePath(args: JsonObject, name: String): String {
        val path = args[name]?.jsonPrimitive?.contentOrNull?.replace('\\', '/')?.trim()
            ?: error("$name is required")
        require(path.isNotBlank()) { "$name is required" }
        require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
        require(!path.contains('\u0000')) { "$name contains invalid character" }
        return path
    }

    /**
     * 写到 `/workspace`、`/tmp` 之外要不要额外审批（上游 `pathOutsideWritableRoots`）。
     *
     * **有意偏离上游的一处**：参数里根本解析不出绝对路径时返回 `null`（上游那版在这里是
     * `getOrDefault(true)`，于是这个调用会先弹一次审批、用户点完只会收到
     * 「path is required」）。这种调用无论路径是什么都执行不了 —— 模型发来一个截断/坏掉的
     * tool call 参数就会走到这里（用户 2026-09-16「我关闭了确认 为什么还有确认呀」，
     * 库里那条 `workspace_write_file` 的 arguments 就是被截断的），不该占用用户一次确认。
     * 调用方判据用 `== true`，`null` 表示「参数不可用，直接让工具报参数错误」。
     */
    fun pathOutsideWritableRoots(args: JsonObject, name: String): Boolean? = runCatching {
        isOutsideWritableRoots(absolutePath(args, name))
    }.getOrNull()

    fun isOutsideWritableRoots(path: String): Boolean {
        val normalized = path.trimEnd('/').ifBlank { "/" }
        return WRITABLE_ROOT_PREFIXES.none { prefix ->
            normalized == prefix || normalized.startsWith("$prefix/")
        }
    }

    /** POSIX 单引号转义（上游 `shellQuote`）。 */
    fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"

    /** 上游 `statEntryCommand`：`\0` 分隔的 `type/size/mtime/path` 四元组。 */
    fun statEntryCommand(path: String): String {
        val pathArg = shellQuote(path)
        return """
            if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
            entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
            entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
            printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
        """.trimIndent()
    }

    /** 上游 `parseRootfsEntries`。 */
    fun parseRootfsEntries(stdout: String): List<WorkspaceFileEntry> {
        val fields = stdout.split('\u0000').dropLastWhile { it.isEmpty() }
        require(fields.size % 4 == 0) { "Invalid file metadata output" }
        return fields.chunked(4).map { chunk ->
            WorkspaceFileEntry(
                path = chunk[3],
                name = chunk[3].trimEnd('/').substringAfterLast('/').ifBlank { "/" },
                isDirectory = chunk[0] == "d",
                sizeBytes = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}"),
                updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L,
            )
        }
    }

    fun entryJson(entry: WorkspaceFileEntry): String = buildJsonObject {
        put("path", entry.path)
        put("name", entry.name)
        put("isDirectory", entry.isDirectory)
        put("sizeBytes", entry.sizeBytes)
        put("updatedAt", entry.updatedAt)
    }.toString()

    /** shell 结果 → 模型看的 JSON（上游 `createShellTool` 的 execute 尾部）。 */
    fun commandResultJson(
        exitCode: Int,
        stdout: String,
        stderr: String,
        timedOut: Boolean,
        truncated: Boolean,
    ): String = buildJsonObject {
        put("exitCode", exitCode)
        put("stdout", stdout)
        put("stderr", stderr)
        put("timedOut", timedOut)
        if (truncated) put("truncated", true)
    }.toString()

    // ---------------------------------------------------------------- 编辑

    data class ReplaceResult(val updated: String, val replacements: Int, val strategy: String)

    /**
     * 三级替换阶梯（精确 → 逐行 trim → 首尾锚点），实现在 [WorkspaceTextReplacers]
     * —— 照上游 `TextReplacers.kt` 1:1 移植，连「按匹配处缩进重排 new_text」都在。
     */
    fun replaceText(original: String, oldText: String, newText: String, replaceAll: Boolean): ReplaceResult {
        val result = replaceWorkspaceText(original, oldText, newText, replaceAll)
        return ReplaceResult(result.updated, result.replacements, result.strategy)
    }

    /** 读文件的字节上限（上游 `MAX_READ_FILE_BYTES` = 8MB）。 */
    fun requireReadableSize(path: String, size: Long) {
        require(size <= MAX_READ_FILE_BYTES) {
            "File is too large to read: $path (${size / 1024 / 1024}MB, " +
                "max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). " +
                "Use shell commands like head, tail, or grep to read parts of it."
        }
    }

    fun timeoutMillis(args: JsonObject): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return seconds?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
    }

    fun stringArg(args: JsonObject, name: String): String? =
        args[name]?.jsonPrimitive?.contentOrNull

    fun booleanArg(args: JsonObject, name: String, default: Boolean): Boolean =
        args[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: default

    /** 把一份字节读进内存（上游 `readRootfsBuffer`）。 */
    fun readBuffer(stream: ByteArrayOutputStream): String =
        stream.toString(Charsets.UTF_8.name())

    fun emptyBuffer(sizeHint: Long): ByteArrayOutputStream =
        ByteArrayOutputStream(sizeHint.toInt())

    // ---------------------------------------------------------------- 提示词

    /**
     * 系统提示词里的工作区引导 —— 逐字移植上游 `WorkspaceReminderTransformer`
     * 的 `buildWorkspacePrompt`。
     *
     * 触发条件也照上游：助手绑了工作区**且** `shellStatus == READY`（没装好就说有沙箱
     * 会骗模型）。`/skills` 与 `/upload` 那两段对应容器里的 bind mount；`/upload` 特别
     * 写明**只读**，免得模型去改用户上传的原件。
     *
     * **本工程新增的一句**（用户 2026-09-16 点头）：沙箱与手机共享内存/CPU，重运行时容易
     * 起不来 —— 真机上模型就是先给 .NET 设 `DOTNET_GCHeapHardLimit`、再放弃改用
     * python-docx，白烧了好几轮（取证见 `docs/PORTING.md` §5.16①）。
     */
    fun buildSystemPromptBlock(workspaceName: String, cwd: String? = null): String = buildString {
        appendLine("<workspace>")
        appendLine("You have access to a persistent Linux workspace named \"$workspaceName\", running in a sandboxed proot rootfs environment.")
        appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
        appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
        appendLine("- Available tools:")
        appendLine("  - `workspace_read_file`: read file contents.")
        appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
        appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
        appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
        appendLine("- CPU and memory are shared with the host device, which is usually a phone under memory pressure: heavy runtimes (for example .NET or the JVM) often fail to start with out-of-memory errors. Prefer Python, Node, or plain shell tooling, and only install a heavy runtime when the task really requires it.")
        appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
        appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
        if (!cwd.isNullOrBlank()) {
            appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
        }
        append("</workspace>")
    }

    // ---------------------------------------------------------------- 执行

    /** 工具结果附带的图片：原始字节 + 文件名，由调用方（`ToolHandler`）落盘成文件。 */
    class ToolImageBytes(val name: String, val bytes: ByteArray)

    sealed interface Outcome {
        data class Success(val json: String, val image: ToolImageBytes? = null) : Outcome
        data class Failure(val error: String, val message: String) : Outcome
    }

    /** 图片扩展名（1:1 上游 `WorkspaceTools.kt` 的 `IMAGE_EXTENSIONS`）。 */
    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
    )

    /**
     * 图片的读取结果：正文只留路径与描述（1:1 上游 `readImageInRootfs` 的文本部分），
     * 字节交给调用方落盘成图片附件。
     */
    internal fun imageReadOutcome(path: String, bytes: ByteArray): Outcome.Success =
        Outcome.Success(
            json = buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString(),
            image = ToolImageBytes(name = path.substringAfterLast('/'), bytes = bytes),
        )

    internal fun isImagePath(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    /**
     * 执行一次工作区工具。失败不抛异常 —— 调用方（`ToolHandler`）要把话讲给模型听，
     * 让它自己改路径或换命令。
     */
    suspend fun execute(
        repo: WorkspaceRepository,
        workspaceId: String,
        cwd: String?,
        name: String,
        args: JsonObject,
    ): Outcome = try {
        when (name) {
            READ_FILE -> readFile(repo, workspaceId, args)
            WRITE_FILE -> writeFile(repo, workspaceId, args)
            EDIT_FILE -> editFile(repo, workspaceId, args)
            SHELL -> shell(repo, workspaceId, cwd, args)
            else -> Outcome.Failure("unknown_tool", "Unknown workspace tool: $name")
        }
    } catch (e: Exception) {
        Outcome.Failure("execution_error", e.message ?: e.toString())
    }

    private suspend fun readFile(
        repo: WorkspaceRepository,
        workspaceId: String,
        args: JsonObject,
    ): Outcome {
        val path = absolutePath(args, "path")
        val size = repo.rootfsFileSize(workspaceId, path)
        requireReadableSize(path, size)
        val buffer = emptyBuffer(size)
        repo.exportRootfsFile(workspaceId, path, buffer)
        // 图片不当文本读（那样只会给模型一串乱码）—— 照上游把它作为工具结果里的
        // image 部分回传：正文换成一段描述，字节交给调用方落盘后随工具结果一起走。
        if (isImagePath(path)) {
            return imageReadOutcome(path, buffer.toByteArray())
        }
        return Outcome.Success(
            buildJsonObject {
                put("path", path)
                put("text", readBuffer(buffer))
            }.toString(),
        )
    }

    private suspend fun writeFile(
        repo: WorkspaceRepository,
        workspaceId: String,
        args: JsonObject,
    ): Outcome {
        val path = absolutePath(args, "path")
        val text = stringArg(args, "text") ?: error("text is required")
        val overwrite = booleanArg(args, "overwrite", default = true)
        val pathArg = shellQuote(path)
        val result = runRootfsCommand(
            repo = repo,
            workspaceId = workspaceId,
            action = "Write file",
            command = """
                if [ -e $pathArg ] && [ ${if (!overwrite) 1 else 0} = 1 ]; then
                  printf '%s\n' ${shellQuote("File already exists: $path")} >&2
                  exit 1
                fi
                if [ -e $pathArg ] && [ ! -f $pathArg ]; then
                  printf '%s\n' ${shellQuote("Path is not a file: $path")} >&2
                  exit 1
                fi
                parent=${'$'}(dirname -- $pathArg) || exit 1
                mkdir -p -- "${'$'}parent" || exit 1
                cat > $pathArg || exit 1
                ${statEntryCommand(path)}
            """.trimIndent(),
            stdin = text.toByteArray(Charsets.UTF_8),
        )
        val entry = parseRootfsEntries(result.stdout).singleOrNull()
            ?: error("Invalid file metadata output")
        return Outcome.Success(entryJson(entry))
    }

    private suspend fun editFile(
        repo: WorkspaceRepository,
        workspaceId: String,
        args: JsonObject,
    ): Outcome {
        val path = absolutePath(args, "path")
        val oldText = stringArg(args, "old_text") ?: error("old_text is required")
        val newText = stringArg(args, "new_text") ?: error("new_text is required")
        val replaceAll = booleanArg(args, "replace_all", default = false)

        val size = repo.rootfsFileSize(workspaceId, path)
        requireReadableSize(path, size)
        val buffer = emptyBuffer(size)
        repo.exportRootfsFile(workspaceId, path, buffer)
        val original = readBuffer(buffer)

        val replaced = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            return Outcome.Failure("no_match", "${e.message} (path: $path)")
        }

        val write = writeFile(
            repo,
            workspaceId,
            buildJsonObject {
                put("path", JsonPrimitive(path))
                put("text", JsonPrimitive(replaced.updated))
                put("overwrite", JsonPrimitive(true))
            },
        )
        if (write !is Outcome.Success) return write

        // writeFile 返回的就是 entry 的 JSON，这里补上替换统计再回给模型。
        return Outcome.Success(
            buildString {
                append(write.json.removeSuffix("}"))
                append(",\"replacements\":").append(replaced.replacements)
                if (replaced.strategy != STRATEGY_EXACT) {
                    append(",\"matchStrategy\":\"").append(replaced.strategy).append('"')
                }
                append('}')
            },
        )
    }

    private suspend fun shell(
        repo: WorkspaceRepository,
        workspaceId: String,
        cwd: String?,
        args: JsonObject,
    ): Outcome {
        val command = stringArg(args, "command") ?: error("command is required")
        val effectiveCwd = shellCwd(stringArg(args, "cwd") ?: cwd).orEmpty()
        val result = repo.executeCommand(
            id = workspaceId,
            command = command,
            cwd = effectiveCwd,
            timeoutMillis = timeoutMillis(args),
        )
        return Outcome.Success(
            commandResultJson(
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                timedOut = result.timedOut,
                truncated = result.truncated,
            ),
        )
    }

    /** 上游 `runRootfsCommand`：超时/非零退出/输出被截断都当成失败讲给模型。 */
    private suspend fun runRootfsCommand(
        repo: WorkspaceRepository,
        workspaceId: String,
        action: String,
        command: String,
        stdin: ByteArray? = null,
    ): com.psyche.memo.workspace.WorkspaceCommandResult {
        val result = repo.executeCommand(
            id = workspaceId,
            command = command,
            timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
            stdin = stdin,
        )
        if (result.timedOut) error("$action timed out")
        if (result.exitCode != 0) {
            val message = result.stderr.ifBlank { result.stdout }.trim()
            error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
        }
        if (result.truncated) error("$action output is too large")
        return result
    }
}
