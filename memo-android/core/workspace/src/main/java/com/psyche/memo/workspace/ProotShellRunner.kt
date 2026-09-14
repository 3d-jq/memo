package com.psyche.memo.workspace

import java.io.File

/**
 * PRoot 版命令执行器 —— 上游 `workspace` 模块 `ProotShellRunner.kt:5-131` 1:1 移植。
 *
 * 与 [HostShellRunner] 的区别只在 argv：用 `nativeLibraryDir` 里的 `libproot_exec.so`
 * 起 rootfs，`-b` 把工作区文件区挂到 `/workspace`、再挂调用方的 bind mount 与宿主内核
 * 伪文件系统，最后用 `/usr/bin/env -i` 清环境跑 `/bin/bash -l`。命令文本通过位置参数传入，
 * 不做任何转义（`eval "$2"` 只求值一次，等价于 `bash -c "$cmd"`）。
 *
 * 唯一的品牌化差异：`argv[0]` 占位符由上游的字符串改成 `memo`（只用于 `$0`，
 * bash `-l` 登录 shell 会拿它拼提示符/日志前缀）。
 */
data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val process = ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                loaderEnvironment(context.tempDir).forEach { (key, value) ->
                    environment()[key] = value
                }
            }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    /**
     * PTY 会话与 `ProcessBuilder` 都要设的三个环境变量 —— proot 靠 `PROOT_LOADER`
     * 找 loader，`PROOT_TMP_DIR`/`TMPDIR` 放它的临时文件。**同一个来源**，否则
     * 交互式终端与一次性命令会跑在两套环境里。
     */
    fun loaderEnvironment(tempDir: File): Map<String, String> = mapOf(
        "PROOT_LOADER" to File(nativeLibraryDir, PROOT_LOADER).absolutePath,
        "PROOT_TMP_DIR" to tempDir.absolutePath,
        "TMPDIR" to tempDir.absolutePath,
    )

    /**
     * 交互式终端的完整 argv（上游 `createWorkspaceTerminalSession`）。
     *
     * 与 [execute] 共用同一段前缀（proot 参数 + 工作区文件区 + bind mount + 内核伪文件系统），
     * 区别只在结尾：不传 `-c`，直接起一个交互式登录 bash，也不带 `CI`/`NO_COLOR`/`PAGER`
     * 那三个「非交互执行约定」变量。
     */
    fun buildInteractiveArgv(
        linuxDir: File,
        filesDir: File,
        bindMounts: List<WorkspaceBindMount>,
    ): List<String> {
        val command = prootPrefix(
            proot = File(nativeLibraryDir, PROOT_EXEC),
            linuxDir = linuxDir,
            filesDir = filesDir,
            bindMounts = bindMounts,
            cwd = WORKSPACE_DIR,
        )
        command += listOf("/usr/bin/env", "-i")
        command += INTERACTIVE_ENV
        command += "/bin/bash"
        return command
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        val command = prootPrefix(
            proot = proot,
            linuxDir = context.linuxDir,
            filesDir = context.filesDir,
            bindMounts = context.bindMounts,
            cwd = context.prootCwd(),
        )

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
            "/bin/bash",
            "-l",
            "-c",
            // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
            "cd -- \"\$1\" && eval \"\$2\"",
            "memo",
            context.prootCwd(),
            context.command,
        )
        return command
    }

    /** proot 参数段：rootfs 根、工作目录、工作区文件区、bind mount 表、内核伪文件系统。 */
    private fun prootPrefix(
        proot: File,
        linuxDir: File,
        filesDir: File,
        bindMounts: List<WorkspaceBindMount>,
        cwd: String,
    ): MutableList<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            cwd,
            "-b",
            "${filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR

        /**
         * 交互式登录 shell 的环境（上游 `createWorkspaceTerminalSession` 里那一段）。
         * 与一次性命令的差别：有 `USER`/`SHELL`（登录 shell 与提示符要用），没有
         * `CI`/`NO_COLOR`/`PAGER`（那三个是「非交互」约定，会让交互式程序行为异常）。
         */
        private val INTERACTIVE_ENV = listOf(
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "USER=root",
            "SHELL=/bin/bash",
        )
    }
}
