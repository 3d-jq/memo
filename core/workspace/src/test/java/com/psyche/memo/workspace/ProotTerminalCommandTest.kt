package com.psyche.memo.workspace

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交互式终端 PTY 的 argv 组装。
 *
 * 这条链只有真机才能端到端验证（要 NDK 编出来的 `libtermux.so` + 装好的 rootfs），
 * 但拼错的后果很隐蔽 —— 少一个 `-b` 用户在沙箱里就看不到 `/skills`，多一个 `-c`
 * 交互式 shell 直接退化成一次性命令、进去就退出。所以把形状钉在单测里。
 */
class ProotTerminalCommandTest {

    private fun runner(nativeDir: File) = ProotShellRunner(nativeLibraryDir = nativeDir)

    @Test
    fun `interactive argv reuses the same mount table as one-shot commands`() {
        val nativeDir = Files.createTempDirectory("native").toFile()
        val linuxDir = Files.createTempDirectory("linux").toFile()
        val filesDir = Files.createTempDirectory("files").toFile()
        // bind mount 的源必须存在才会进 argv（照 ProotShellRunner.prootPrefix）
        val skillsDir = Files.createTempDirectory("skills").toFile()
        val missingDir = File(Files.createTempDirectory("gone").toFile(), "not-there")

        val argv = runner(nativeDir).buildInteractiveArgv(
            linuxDir = linuxDir,
            filesDir = filesDir,
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills"),
                WorkspaceBindMount(source = missingDir, target = "/upload"),
            ),
        )

        // argv[0] 是 proot 本体（TerminalSession 的 shellPath），参数从 --root-id 开始
        assertEquals(File(nativeDir, "libproot_exec.so").absolutePath, argv.first())
        assertTrue(argv.contains("--root-id"))
        assertTrue(argv.contains("--link2symlink"))
        assertTrue(argv.contains("--kill-on-exit"))
        assertEquals(linuxDir.absolutePath, argv[argv.indexOf("-r") + 1])
        assertEquals(WorkspaceManager.ROOTFS_WORKSPACE_DIR, argv[argv.indexOf("-w") + 1])

        // 工作区文件区固定挂到 /workspace；容器 bind mount 照传进来的表挂（源不存在则跳过）
        assertTrue("-b ${filesDir.absolutePath}:${WorkspaceManager.ROOTFS_WORKSPACE_DIR}" in argv.pairs())
        assertTrue("-b ${skillsDir.absolutePath}:/skills" in argv.pairs())
        assertFalse(argv.any { it.contains("$missingDir") })
    }

    @Test
    fun `interactive argv starts a login shell instead of running a command`() {
        val nativeDir = Files.createTempDirectory("native").toFile()
        val linuxDir = Files.createTempDirectory("linux").toFile()
        val filesDir = Files.createTempDirectory("files").toFile()

        val argv = runner(nativeDir).buildInteractiveArgv(linuxDir, filesDir, emptyList())

        // 结尾是 env -i <交互式环境> /bin/bash
        assertEquals("/bin/bash", argv.last())
        assertEquals("/usr/bin/env", argv[argv.size - 3 - INTERACTIVE_ENV_COUNT])
        assertTrue("TERM=xterm-256color" in argv)
        // 登录 shell 要有 USER/SHELL
        assertTrue("USER=root" in argv)
        assertTrue("SHELL=/bin/bash" in argv)
        // 这三个是「非交互执行约定」，交互式终端带上会让程序和提示符行为异常
        assertFalse("CI=true" in argv)
        assertFalse("NO_COLOR=1" in argv)
        assertFalse("PAGER=cat" in argv)
        // 不能有 -c：有它就是一次性命令，进去就退出
        assertFalse("-c" in argv)
    }

    @Test
    fun `loader environment points proot at the native dir and workspace tmp`() {
        val nativeDir = Files.createTempDirectory("native").toFile()
        val tempDir = Files.createTempDirectory("tmp").toFile()

        val env = runner(nativeDir).loaderEnvironment(tempDir)

        assertEquals(File(nativeDir, "libproot_loader.so").absolutePath, env["PROOT_LOADER"])
        assertEquals(tempDir.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals(tempDir.absolutePath, env["TMPDIR"])
    }

    /** 把 `-b <src>:<dst>` 两两配成 "flag value" 便于断言。 */
    private fun List<String>.pairs(): List<String> {
        val result = ArrayList<String>()
        forEachIndexed { index, value ->
            if (value == "-b" && index + 1 < size) result.add("-b ${this[index + 1]}")
        }
        return result
    }

    private companion object {
        /** 交互式环境变量的条数（HOME/PATH/TERM/LANG/LC_ALL/USER/SHELL）。 */
        const val INTERACTIVE_ENV_COUNT = 7
    }
}
