package com.psyche.memo.provider.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「常用环境」的纯逻辑（本工程新增，用户 2026-09-14「这个沙箱可以让用户选择 下载 node
 * gitbash 这些常用的环境吗？」）。
 *
 * 两条判据错了后果都很直接：**探测**错了会把没装的说成已装（用户以为能用，模型一跑
 * 就 command not found）；**安装命令**错了会静默不装或者装错包。
 */
class WorkspaceEnvironmentsTest {

    @Test
    fun `install command installs non-interactively without recommends`() {
        val command = WorkspaceEnvironments.installCommand(WorkspaceEnvironments.NODE)
        assertTrue(command.contains("apt-get install"))
        // 非交互：apt 会问的地方一律自动答是，并抑制 debconf 提问。
        assertTrue(command.contains("-y"))
        assertTrue(command.contains("DEBIAN_FRONTEND=noninteractive"))
        // recommends 会顺进来一堆用不上的东西。
        assertTrue(command.contains("--no-install-recommends"))
        for (pkg in WorkspaceEnvironments.NODE.packages) {
            assertTrue("缺包 $pkg：$command", command.contains(pkg))
        }
    }

    @Test
    fun `index refresh repairs dpkg first and prunes translation files`() {
        val command = WorkspaceEnvironments.updateIndexCommand()
        assertTrue(command.contains("apt-get"))
        assertTrue(command.contains("update"))
        // Translation-* 索引占几十 MB 且我们不用。
        assertTrue(command.contains("Acquire::Languages=none"))
        // 安装与刷新索引必须分开：失败要能归因到具体哪一步。
        assertFalse(command.contains("install"))
        // 上一次被打断（取消 / 进程被杀 / 换包）留下的半途 transaction，apt 会拒绝干活，
        // 所以每次刷索引前先修一次。
        assertTrue(command.contains(WorkspaceEnvironments.repairCommand()))
        assertTrue(WorkspaceEnvironments.repairCommand().contains("dpkg --configure -a"))
    }

    @Test
    fun `uninstall purges the packages and their unused dependencies`() {
        val command = WorkspaceEnvironments.uninstallCommand(WorkspaceEnvironments.NODE)
        assertTrue(command.contains("remove -y --purge"))
        assertTrue(command.contains("autoremove"))
        for (pkg in WorkspaceEnvironments.NODE.packages) {
            assertTrue("缺包 $pkg：$command", command.contains(pkg))
        }
    }

    @Test
    fun `domestic mirrors come first and the default is the fastest measured one`() {
        // 用户 2026-09-14「来点国内的镜像源呀」——默认不能是 ports.ubuntu.com。
        assertEquals(WorkspaceEnvironments.TUNA, WorkspaceEnvironments.DEFAULT_MIRROR)
        assertEquals("tuna", WorkspaceEnvironments.DEFAULT_MIRROR.id)
        // 四个都在列表里，顺序 = 界面上的先后（国内三个在前，官方兜底）。
        assertEquals(listOf("tuna", "ustc", "aliyun", "official"), WorkspaceEnvironments.MIRRORS.map { it.id })
        assertTrue(WorkspaceEnvironments.MIRRORS.all { it.uri.endsWith("/ubuntu-ports/") })
        // 国内三个必须是 https。
        assertTrue(
            WorkspaceEnvironments.MIRRORS.filter { it.id != "official" }.all { it.uri.startsWith("https://") },
        )
    }

    @Test
    fun `mirror command rewrites the deb822 uris line and is idempotent in shape`() {
        val command = WorkspaceEnvironments.mirrorCommand(WorkspaceEnvironments.TUNA)
        assertTrue(command.contains(WorkspaceEnvironments.SOURCES_FILE))
        // deb822 用 `URIs:` 行；老式 sources.list 里的同源地址也要换掉。
        assertTrue(command.contains("s|^URIs:.*|URIs: ${WorkspaceEnvironments.TUNA.uri}|"))
        assertTrue(command.contains("/etc/apt/sources.list"))
        // 只认识已知镜像，避免把用户自己配的源误改。
        for (mirror in WorkspaceEnvironments.MIRRORS) {
            assertTrue(command.contains(Regex.escape(mirror.uri)))
        }
    }

    /**
     * sed 的分隔符不能出现在它自己的模式里。
     *
     * 用户 2026-09-14 实测踩到：交替匹配那条写成 `s|(a|b|c)|…|g`，模式里的 `|` 被 sed
     * 当成 `s` 命令的结束 → `sed: -e expression #1, char 105: unknown option to 's'`，
     * 而这条命令开头是 `set -e`，于是**三个预设全部安装失败**（报的还都是同一句）。
     */
    @Test
    fun `no sed delimiter appears inside its own pattern`() {
        val command = WorkspaceEnvironments.mirrorCommand(WorkspaceEnvironments.TUNA)
        // 交替匹配那条用 `#`：URI 里不会有 `#`，模式里的 `|` 才是正则交替。
        assertTrue(command.contains("s#("))
        assertTrue(WorkspaceEnvironments.MIRRORS.none { it.uri.contains('#') })
        // `URIs:` 那条用 `|`：它的模式是 `^URIs:.*`，不含 `|`。
        assertTrue(command.contains("s|^URIs:.*|"))
        assertFalse("^URIs:.*".contains('|'))
    }

    @Test
    fun `mirror probe output maps back to a known mirror or null`() {
        assertEquals(
            WorkspaceEnvironments.USTC,
            WorkspaceEnvironments.parseMirror("URIs: ${WorkspaceEnvironments.USTC.uri}"),
        )
        assertEquals(
            WorkspaceEnvironments.OFFICIAL,
            WorkspaceEnvironments.parseMirror("URIs: ${WorkspaceEnvironments.OFFICIAL.uri}\n"),
        )
        // 认不出的自定义源 → null（界面就退回默认清华，不会显示错的高亮）。
        assertEquals(null, WorkspaceEnvironments.parseMirror("URIs: http://example.com/ubuntu/"))
        assertEquals(null, WorkspaceEnvironments.parseMirror(""))
        // 探测命令与解析要配套：它必须打的是 `URIs:` 行。
        assertTrue(WorkspaceEnvironments.mirrorProbeCommand().contains("^URIs:"))
    }

    @Test
    fun `every preset has packages and its own id`() {
        val ids = WorkspaceEnvironments.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (environment in WorkspaceEnvironments.ALL) {
            assertTrue(environment.id.isNotBlank())
            assertTrue(environment.packages.isNotEmpty())
            // 包名是写死的常量，命令里直接拼；不能出现空串或带空格的"包名"。
            assertTrue(environment.packages.none { it.isBlank() || it.contains(' ') })
        }
    }

    @Test
    fun `probe asks dpkg about every package exactly once`() {
        val probe = WorkspaceEnvironments.probeCommand()
        val packages = WorkspaceEnvironments.ALL.flatMap { it.packages }.distinct()
        for (pkg in packages) {
            // 用 dpkg-query 的 Status 字段而不是 command -v：包名≠命令名（ripgrep→rg）时才算得准。
            assertTrue("$pkg 未被探测", probe.contains("dpkg-query -W -f='\${Status}' $pkg"))
        }
        assertEquals(packages.size, probe.lines().count { it.isNotBlank() })
        assertTrue(probe.contains("install ok installed"))
    }

    @Test
    fun `a preset counts as installed only when every package is installed`() {
        val all = WorkspaceEnvironments.ALL.flatMap { it.packages }.distinct()
        val everything = all.joinToString("\n") { "$it=1" }
        assertTrue(WorkspaceEnvironments.parseInstalled(everything).values.all { it })

        // nodejs 装了但 npm 没装 → node 这个环境不算可用。
        val partial = all.joinToString("\n") { "$it=1" } + "\nnpm=0"
        val parsed = WorkspaceEnvironments.parseInstalled(partial)
        assertFalse(parsed.getValue(WorkspaceEnvironments.NODE.id))
        assertTrue(parsed.getValue(WorkspaceEnvironments.PYTHON.id))
    }

    @Test
    fun `missing or garbage probe output fails closed`() {
        // 解不出信息的包一律按未安装（宁可让人再点一次安装，也不谎报已装）。
        val parsed = WorkspaceEnvironments.parseInstalled("")
        assertEquals(WorkspaceEnvironments.ALL.size, parsed.size)
        assertTrue(parsed.values.none { it })

        val garbage = "no separator here\n=1\nnodejs\n\n  npm = 1  "
        val mixed = WorkspaceEnvironments.parseInstalled(garbage)
        // npm 认出来了，但 nodejs 没有 → node 仍不算装齐。
        assertFalse(mixed.getValue(WorkspaceEnvironments.NODE.id))
        assertTrue(mixed.values.none { it })
    }
}
