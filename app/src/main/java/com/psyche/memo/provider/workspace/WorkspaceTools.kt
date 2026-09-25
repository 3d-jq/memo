package com.psyche.memo.provider.workspace

import com.psyche.memo.llm.client.LlmToolSpec
import com.psyche.memo.provider.tool.ArgViolation
import com.psyche.memo.provider.tool.ToolArgs
import com.psyche.memo.workspace.WorkspaceFileEntry
import com.psyche.memo.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonArray
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
 *
 * **本工程新增三个只读工具**（[LIST] / [GLOB] / [GREP]，用户 2026-09-22
 * 「在工作区加上工具，list，glob，grep 工具」）：上游没有这三个（RikkaHub 的工作区工具
 * 面只有上面四个），所以描述文案是按本工程口径自己写的，不是移植。它们和
 * `workspace_read_file` 同一条通道 —— 都是 proot 里的 shell 命令 + 结构化 JSON 回给模型，
 * 都默认免审批。
 */
object WorkspaceTools {

    const val READ_FILE = "workspace_read_file"
    const val WRITE_FILE = "workspace_write_file"
    const val EDIT_FILE = "workspace_edit_file"
    const val LIST = "workspace_list"
    const val GLOB = "workspace_glob"
    const val GREP = "workspace_grep"
    const val SHELL = "workspace_shell"

    val ALL_TOOL_NAMES = setOf(READ_FILE, WRITE_FILE, EDIT_FILE, LIST, GLOB, GREP, SHELL)

    /**
     * 上游 `WorkspaceToolDefaultApprovals`：只有 shell 默认要审批。
     *
     * [LIST]/[GLOB]/[GREP] 是**本工程新增**的三个只读工具（上游没有，用户 2026-09-22
     * 点名要），默认免审批 —— 它们和 `workspace_read_file` 一样只看不改。
     */
    val DEFAULT_APPROVALS: Map<String, Boolean> = mapOf(
        READ_FILE to false,
        WRITE_FILE to false,
        EDIT_FILE to false,
        LIST to false,
        GLOB to false,
        GREP to false,
        SHELL to true,
    )

    /** 上游 `resolveWorkspaceToolApproval`：工作区上的覆盖项优先，其次默认值。 */
    fun resolveApproval(name: String, overrides: Map<String, Boolean>): Boolean =
        overrides[name] ?: DEFAULT_APPROVALS[name] ?: false

    /** 免强制审批的可写安全区（上游 `WRITABLE_ROOT_PREFIXES`）。 */
    private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp")

    private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
    private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

    /** 新增三个只读工具的搜索根默认值（模型不传 `path` 时用）。 */
    const val DEFAULT_SEARCH_ROOT = "/workspace"

    /** 一次最多回给模型多少条（后面还有就截断并标 `truncated`）。 */
    private const val MAX_LIST_ENTRIES = 500
    private const val MAX_GREP_MATCHES = 200

    /** 单行 grep 结果最多留多少字符（一行几十万字符的压缩包日志会撑爆上下文）。 */
    private const val MAX_GREP_LINE_CHARS = 500

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
            LlmToolSpec(LIST, LIST_DESCRIPTION, listParametersJson()),
            LlmToolSpec(GLOB, GLOB_DESCRIPTION, globParametersJson()),
            LlmToolSpec(GREP, GREP_DESCRIPTION, grepParametersJson()),
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

    private val LIST_DESCRIPTION = """
        List the entries of one directory in the assistant's bound workspace Rootfs (not recursive).
        Paths must be absolute inside Rootfs. Use /workspace for the workspace files area; it is the default.
        Returns name, absolute path, whether it is a directory, size in bytes and modification time for each entry.
    """.trimIndent().replace("\n", " ")

    private val GLOB_DESCRIPTION = """
        Find files by path pattern in the assistant's bound workspace Rootfs.
        The pattern is shell-style globbing matched against absolute paths, and * also crosses directory separators, so '*.md' finds nested files too.
        Paths must be absolute inside Rootfs. Use /workspace for the workspace files area; it is the search root by default.
        Use this instead of shell find when you only need matching paths.
    """.trimIndent().replace("\n", " ")

    private val GREP_DESCRIPTION = """
        Search file contents by regular expression (POSIX extended) in the assistant's bound workspace Rootfs.
        Paths must be absolute inside Rootfs. Use /workspace for the workspace files area; it is the search root by default.
        Binary files are skipped. Returns the matching path, line number and line text.
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

    /** 三个只读工具共用的 `path` 属性：可选、缺省即搜索根。 */
    private fun searchPathProperty() = buildJsonObject {
        put("type", "string")
        put(
            "description",
            "Optional absolute path inside Rootfs. Use /workspace for the workspace files area. " +
                "Defaults to $DEFAULT_SEARCH_ROOT.",
        )
    }

    fun listParametersJson(): String = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("path", searchPathProperty()) })
    }.toString()

    fun globParametersJson(): String = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("pattern", buildJsonObject {
                put("type", "string")
                put("description", "Shell-style glob pattern, for example '*.md' or 'src/**/*.kt'")
            })
            put("path", searchPathProperty())
        })
        put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
    }.toString()

    fun grepParametersJson(): String = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("pattern", buildJsonObject {
                put("type", "string")
                put("description", "POSIX extended regular expression to search for")
            })
            put("path", searchPathProperty())
            put("glob", buildJsonObject {
                put("type", "string")
                put("description", "Optional file name filter, for example '*.kt'")
            })
            put("ignore_case", buildJsonObject {
                put("type", "boolean")
                put("description", "Whether matching is case-insensitive. Defaults to false.")
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
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
     * 同 [absolutePath]，但缺省/空白时回落到 [default] —— 只读的三个搜索工具用它，
     * 模型不传 `path` 就是「搜 /workspace」。
     */
    fun absolutePathOrDefault(args: JsonObject, name: String, default: String): String {
        val value = args[name]?.jsonPrimitive?.contentOrNull?.replace('\\', '/')?.trim()
        if (value.isNullOrBlank()) return default
        require(value.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
        require(!value.contains('\u0000')) { "$name contains invalid character" }
        return value
    }

    /**
     * glob 表达式 → 绝对路径模式：相对模式挂在 [root] 下，已经写了绝对路径就原样用
     * （模型经常会照着 `/workspace/...` 抄）。
     */
    fun globExpression(root: String, pattern: String): String {
        val cleaned = pattern.replace('\\', '/').trim().removePrefix("./")
        return if (cleaned.startsWith("/")) cleaned else "${root.trimEnd('/')}/$cleaned"
    }

    /** GNU find 的记录格式：`\0` 分隔的 `type/size/mtime/path`（同 [statEntryCommand] 的四元组）。 */
    private const val ENTRY_PRINTF = "%y\\0%s\\0%T@\\0%p\\0"

    /** 列一层目录（`-mindepth 1` 去掉自己）。 */
    fun listCommand(path: String): String = """
        dir=${shellQuote(path)}
        if [ ! -d "${'$'}dir" ]; then
          printf '%s\n' ${shellQuote("Not a directory: $path")} >&2
          exit 1
        fi
        find "${'$'}dir" -mindepth 1 -maxdepth 1 -printf ${shellQuote(ENTRY_PRINTF)}
    """.trimIndent()

    /** 按绝对路径 glob 找文件。 */
    fun globCommand(root: String, pattern: String): String = """
        root=${shellQuote(root)}
        if [ ! -d "${'$'}root" ]; then
          printf '%s\n' ${shellQuote("Not a directory: $root")} >&2
          exit 1
        fi
        find "${'$'}root" -path ${shellQuote(globExpression(root, pattern))} -printf ${shellQuote(ENTRY_PRINTF)}
    """.trimIndent()

    /**
     * 按内容搜（`-r` 递归、`-I` 跳过二进制、`-n` 行号、`-E` 扩展正则、`-Z` 用 NUL 隔开
     * 文件名 —— 文件名里的冒号会把 `path:line:text` 解析带偏）。
     *
     * `grep` 报错（正则非法）走 stderr；没匹配是退出码 1、stdout 为空 —— 两者都不该当成
     * 执行失败，所以这里不 `set -e`、调用方只看 stderr 判错。
     */
    fun grepCommand(
        root: String,
        pattern: String,
        glob: String?,
        ignoreCase: Boolean,
    ): String = buildString {
        appendLine("root=${shellQuote(root)}")
        appendLine("if [ ! -e \"\$root\" ]; then")
        appendLine("  printf '%s\\n' ${shellQuote("No such path: $root")} >&2")
        appendLine("  exit 1")
        appendLine("fi")
        append("grep -rInEZ")
        if (ignoreCase) append('i')
        if (!glob.isNullOrBlank()) append(" --include=${shellQuote(glob.trim())}")
        append(" -- ${shellQuote(pattern)} \"\$root\"")
        append(" | head -n ${MAX_GREP_MATCHES + 1}")
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

    fun entryJson(entry: WorkspaceFileEntry): String = entryObject(entry).toString()

    fun entryObject(entry: WorkspaceFileEntry): JsonObject = buildJsonObject {
        put("path", entry.path)
        put("name", entry.name)
        put("isDirectory", entry.isDirectory)
        put("sizeBytes", entry.sizeBytes)
        put("updatedAt", entry.updatedAt)
    }

    fun entryArray(entries: List<WorkspaceFileEntry>): JsonArray =
        buildJsonArray { entries.forEach { add(entryObject(it)) } }

    /**
     * [ENTRY_PRINTF] 的输出 → 条目表。
     *
     * `%T@` 带小数（秒.纳秒），只取整数秒 —— 与 [parseRootfsEntries] 的 `updatedAt`
     * 口径一致。输出被 runner 按字节截断时末尾会留半条记录，凑不满四元组的尾巴直接丢。
     */
    fun parsePrintfEntries(stdout: String): List<WorkspaceFileEntry> {
        val fields = stdout.split('\u0000').dropLastWhile { it.isEmpty() }
        val complete = fields.size - (fields.size % 4)
        return (0 until complete step 4).map { i ->
            WorkspaceFileEntry(
                path = fields[i + 3],
                name = fields[i + 3].trimEnd('/').substringAfterLast('/').ifBlank { "/" },
                isDirectory = fields[i] == "d",
                sizeBytes = fields[i + 1].toLongOrNull()
                    ?: error("Invalid file size: ${fields[i + 1]}"),
                updatedAt = (fields[i + 2].substringBefore('.').toLongOrNull()
                    ?: error("Invalid file mtime: ${fields[i + 2]}")) * 1_000L,
            )
        }
    }

    data class GrepMatch(val path: String, val line: Int, val text: String)

    /**
     * `grep -rInEZ` 的输出 → 匹配表。`-Z` 让文件名后面是 NUL 而不是冒号，于是
     * `<path>\0<line>:<text>` 能安全拆开（文件名里带冒号也不会拆错）。
     */
    fun parseGrepMatches(stdout: String, limit: Int = MAX_GREP_MATCHES): List<GrepMatch> =
        stdout.lineSequence()
            .mapNotNull { line ->
                val sep = line.indexOf('\u0000')
                if (sep < 0) return@mapNotNull null
                val rest = line.substring(sep + 1)
                val colon = rest.indexOf(':')
                if (colon < 0) return@mapNotNull null
                GrepMatch(
                    path = line.substring(0, sep),
                    line = rest.substring(0, colon).toIntOrNull() ?: return@mapNotNull null,
                    text = rest.substring(colon + 1).take(MAX_GREP_LINE_CHARS),
                )
            }
            .take(limit)
            .toList()

    fun grepMatchArray(matches: List<GrepMatch>): JsonArray = buildJsonArray {
        matches.forEach { match ->
            add(
                buildJsonObject {
                    put("path", match.path)
                    put("line", match.line)
                    put("text", match.text)
                },
            )
        }
    }

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
        appendLine("  - `workspace_list` / `workspace_glob`: list one directory, or find files by path pattern.")
        appendLine("  - `workspace_grep`: search file contents by regular expression (binary files are skipped).")
        appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
        appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
        appendLine("- Prefer the dedicated `workspace_list` / `workspace_glob` / `workspace_grep` tools over `ls`, `find` and `grep` when you just need to look around: they return structured results and need no approval.")
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
        /**
         * [instruction] = 这条失败该让模型**下一步做什么**（null 时由调用方给通用那句）。
         * 「未读不可改」这类必须点名补救动作，否则模型只会原地再敲同一颗 edit。
         */
        data class Failure(
            val error: String,
            val message: String,
            val instruction: String? = null,
            /** 参数校验失败时逐条点名（B8）；其他失败为空。 */
            val violations: List<com.psyche.memo.provider.tool.ArgViolation> = emptyList(),
        ) : Outcome
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
        /** 「读过才许改」的账本（见 [WorkspaceReadLedger]）；null = 不启用这条纪律。 */
        reads: WorkspaceReadLedger? = null,
        conversationId: String? = null,
    ): Outcome {
        // B8：先把参数校验干净，**不合格就一颗都不执行**。各工具里的
        // absolutePath/stringArg 仍然是后手，但模型该看到的是「哪颗参数、缺什么、
        // 怎么改」的清单，而不是 `IllegalStateException: path is required` 这种半句话。
        val violations = validateArguments(name, args)
        if (violations.isNotEmpty()) {
            return Outcome.Failure(
                error = "invalid_arguments",
                message = "`$name` was not executed: ${violations.size} argument problem(s).",
                instruction = "Fix the arguments listed in `violations` and call again. " +
                    "Nothing ran and nothing changed — do not describe a result.",
                violations = violations,
            )
        }
        return try {
            when (name) {
                READ_FILE -> readFile(repo, workspaceId, args, reads, conversationId)
                WRITE_FILE -> writeFile(repo, workspaceId, args, reads, conversationId)
                EDIT_FILE -> editFile(repo, workspaceId, args, reads, conversationId)
                LIST -> list(repo, workspaceId, args)
                GLOB -> glob(repo, workspaceId, args)
                GREP -> grep(repo, workspaceId, args)
                SHELL -> shell(repo, workspaceId, cwd, args)
                else -> Outcome.Failure("unknown_tool", "Unknown workspace tool: $name")
            }
        } catch (e: Exception) {
            Outcome.Failure("execution_error", e.message ?: e.toString())
        }
    }

    /** 每颗工作区工具的入参约束（与上面 `*ParametersJson` 的 schema 同口径）。 */
    private fun validateArguments(name: String, args: JsonObject): List<ArgViolation> {
        val a = ToolArgs(args)
        when (name) {
            READ_FILE -> a.absolutePath("path")
            WRITE_FILE -> {
                a.absolutePath("path")
                a.string("text")
                a.boolean("overwrite", default = true)
            }

            EDIT_FILE -> {
                a.absolutePath("path")
                a.string("old_text")
                a.string("new_text")
                a.boolean("replace_all", default = false)
            }

            LIST -> a.absolutePath("path", required = false)
            GLOB -> {
                a.string("pattern")
                a.absolutePath("path", required = false)
            }

            GREP -> {
                a.string("pattern")
                a.absolutePath("path", required = false)
                a.string("glob", required = false)
                a.boolean("ignore_case", default = false)
            }

            SHELL -> {
                a.string("command")
                a.string("cwd", required = false)
                a.int(
                    "timeout",
                    default = 30,
                    minimum = 1,
                    maximum = SHELL_TIMEOUT_MAX_SECONDS.toInt(),
                )
            }
        }
        return a.violations
    }

    private suspend fun readFile(
        repo: WorkspaceRepository,
        workspaceId: String,
        args: JsonObject,
        reads: WorkspaceReadLedger?,
        conversationId: String?,
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
        // 记下这份版本：之后要改它，必须先读过、且它没变过（B10）。
        reads?.record(conversationId, workspaceId, path, size)
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
        reads: WorkspaceReadLedger? = null,
        conversationId: String? = null,
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
        // 刚写出去的就是模型见过的那一份 —— 记下来，紧接着的 edit 不该被当成「没读过」。
        reads?.record(conversationId, workspaceId, path, entry.sizeBytes)
        return Outcome.Success(entryJson(entry))
    }

    private suspend fun editFile(
        repo: WorkspaceRepository,
        workspaceId: String,
        args: JsonObject,
        reads: WorkspaceReadLedger?,
        conversationId: String?,
    ): Outcome {
        val path = absolutePath(args, "path")
        val oldText = stringArg(args, "old_text") ?: error("old_text is required")
        val newText = stringArg(args, "new_text") ?: error("new_text is required")
        val replaceAll = booleanArg(args, "replace_all", default = false)

        val size = repo.rootfsFileSize(workspaceId, path)
        requireReadableSize(path, size)
        // B10：没读过、或读过之后文件又变了，都不许改。只按 `old_text` 匹配的话，模型
        // 猜中一段原文就能把用户手改的内容整篇盖回去，而且工具会报「替换成功」——
        // 那是最坏的一种「说了做了」（用户 2026-09-23 起在收的那条纪律）。
        when (val check = reads?.check(conversationId, workspaceId, path, size)) {
            is WorkspaceReadLedger.EditCheck.NotRead -> return Outcome.Failure(
                error = "not_read",
                message = WorkspaceEditGuards.notRead(path),
                instruction = WorkspaceEditGuards.notReadInstruction(path),
            )

            is WorkspaceReadLedger.EditCheck.Stale -> return Outcome.Failure(
                error = "stale_read",
                message = WorkspaceEditGuards.stale(path, check.seenBytes, check.nowBytes),
                instruction = WorkspaceEditGuards.staleInstruction(path),
            )

            // 账本没接（null）= 这条纪律不启用，照旧允许直接改。
            null, WorkspaceReadLedger.EditCheck.Ok -> Unit
        }
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
            // 改完要刷新账本，否则下一次 edit 会被自己刚写的那份判成「已经变了」。
            reads,
            conversationId,
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

    /** 三个只读工具共用的执行骨架：跑命令 → 看 stderr 判错 → 交给各自的解析器。 */
    private suspend fun readOnlyCommand(
        repo: WorkspaceRepository,
        workspaceId: String,
        action: String,
        command: String,
    ): com.psyche.memo.workspace.WorkspaceCommandResult =
        runRootfsCommand(
            repo = repo,
            workspaceId = workspaceId,
            action = action,
            command = command,
            failOnTruncated = false,
        )


    private suspend fun list(
        repo: WorkspaceRepository,
        workspaceId: String,
        args: JsonObject,
    ): Outcome {
        val path = absolutePathOrDefault(args, "path", DEFAULT_SEARCH_ROOT)
        val result = readOnlyCommand(repo, workspaceId, "List directory", listCommand(path))
        val entries = parsePrintfEntries(result.stdout)
            .sortedWith(compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name })
        val shown = entries.take(MAX_LIST_ENTRIES)
        return Outcome.Success(
            buildJsonObject {
                put("path", path)
                put("entries", entryArray(shown))
                put("count", shown.size)
                if (shown.size < entries.size || result.truncated) put("truncated", true)
            }.toString(),
        )
    }

    private suspend fun glob(
        repo: WorkspaceRepository,
        workspaceId: String,
        args: JsonObject,
    ): Outcome {
        val pattern = stringArg(args, "pattern")?.trim()
        require(!pattern.isNullOrBlank()) { "pattern is required" }
        val root = absolutePathOrDefault(args, "path", DEFAULT_SEARCH_ROOT)
        val result = readOnlyCommand(repo, workspaceId, "Glob files", globCommand(root, pattern))
        val entries = parsePrintfEntries(result.stdout).sortedBy { it.path }
        val shown = entries.take(MAX_LIST_ENTRIES)
        return Outcome.Success(
            buildJsonObject {
                put("root", root)
                put("pattern", globExpression(root, pattern))
                put("matches", entryArray(shown))
                put("count", shown.size)
                if (shown.size < entries.size || result.truncated) put("truncated", true)
            }.toString(),
        )
    }

    private suspend fun grep(
        repo: WorkspaceRepository,
        workspaceId: String,
        args: JsonObject,
    ): Outcome {
        val pattern = stringArg(args, "pattern")
        require(!pattern.isNullOrBlank()) { "pattern is required" }
        val root = absolutePathOrDefault(args, "path", DEFAULT_SEARCH_ROOT)
        val glob = stringArg(args, "glob")
        val ignoreCase = booleanArg(args, "ignore_case", default = false)
        val result = readOnlyCommand(
            repo = repo,
            workspaceId = workspaceId,
            action = "Search contents",
            command = grepCommand(root, pattern, glob, ignoreCase),
        )
        // grep 的报错（正则非法、路径不对）走 stderr；没匹配是退出码 1 + 空 stdout，
        // 那是正常结果不是失败 —— 所以只看 stderr。
        val stderr = result.stderr.trim()
        if (stderr.isNotEmpty()) return Outcome.Failure("grep_failed", stderr)
        val matches = parseGrepMatches(result.stdout)
        return Outcome.Success(
            buildJsonObject {
                put("root", root)
                put("pattern", pattern)
                put("matches", grepMatchArray(matches))
                put("count", matches.size)
                if (matches.size >= MAX_GREP_MATCHES || result.truncated) put("truncated", true)
            }.toString(),
        )
    }

    private suspend fun shell(
        repo: WorkspaceRepository,
        workspaceId: String,
        cwd: String?,
        args: JsonObject,
    ): Outcome {        val command = stringArg(args, "command") ?: error("command is required")
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
        failOnTruncated: Boolean = true,
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
        // 列目录/找文件/搜内容是**结果集**，截断只是「回给你的少一点」：解析器会丢掉
        // 半条记录、结果里带 `truncated: true`，不该整次调用失败。
        if (failOnTruncated && result.truncated) error("$action output is too large")
        return result
    }
}
