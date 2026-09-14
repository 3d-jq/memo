package com.psyche.memo.provider.workspace

import com.psyche.memo.ui.R as UiR

/**
 * 工作区「常用环境」预设 —— **本工程新增，RikkaHub 没有这个功能**（它的工作区只有
 * 列表 / 详情 / 文件编辑 / 终端四个面，没有任何环境安装入口）。
 *
 * 用户 2026-09-14：「这个沙箱可以让用户选择 下载 node gitbash 这些常用的环境吗？」。
 * 实现方式就是把几条 `apt-get install` 做成一键按钮 —— rootfs 是 Ubuntu 24.04，
 * `apt`/`dpkg`/包源/包索引都齐（见 PORTING §4-46），装到**每个工作区自己的 rootfs**
 * 里（谁装谁的，互不影响）。
 *
 * 这里是纯逻辑：命令拼装与状态解析都能单测，真正的执行在 UI 侧走
 * `WorkspaceRepository.executeCommand`（与终端同一条 proot 通道、同一份挂载表）。
 */
internal data class WorkspaceEnvironment(
    /** 稳定 id（状态映射的键）。 */
    val id: String,
    val labelRes: Int,
    val descriptionRes: Int,
    /** apt 包名；**全部装齐**才算这个环境可用。 */
    val packages: List<String>,
)

internal object WorkspaceEnvironments {

    val NODE = WorkspaceEnvironment(
        id = "node",
        labelRes = UiR.string.workspace_env_node,
        descriptionRes = UiR.string.workspace_env_node_desc,
        packages = listOf("nodejs", "npm"),
    )

    val PYTHON = WorkspaceEnvironment(
        id = "python",
        labelRes = UiR.string.workspace_env_python,
        descriptionRes = UiR.string.workspace_env_python_desc,
        packages = listOf("python3", "python3-pip", "python3-venv"),
    )

    val CLI = WorkspaceEnvironment(
        id = "cli",
        labelRes = UiR.string.workspace_env_cli,
        descriptionRes = UiR.string.workspace_env_cli_desc,
        packages = listOf(
            "curl", "wget", "unzip", "xz-utils", "less", "vim", "nano", "jq", "ripgrep", "tree",
        ),
    )

    val ALL: List<WorkspaceEnvironment> = listOf(NODE, PYTHON, CLI)

    /**
     * apt 软件源 —— 真机实测（2026-09-14，同一个 `dists/noble/Release` 文件）：
     * 官方 `ports.ubuntu.com` 86 KB/s、**清华 295 KB/s**、中科大 235 KB/s、阿里云 69 KB/s。
     *
     * rootfs 出厂就是官方源，而 `apt-get update` 要拉几十 MB 索引 —— 按 86 KB/s 算是
     * 几十分钟。用户 2026-09-14「好慢呀 怎么回事呀」「来点国内的镜像源呀」就是这个
     * 原因，所以默认给清华。
     */
    data class AptMirror(
        /** 稳定 id（状态映射的键）。 */
        val id: String,
        val labelRes: Int,
        /** deb822 `URIs:` 行的值。 */
        val uri: String,
    )

    val TUNA = AptMirror(
        id = "tuna",
        labelRes = UiR.string.workspace_env_mirror_tuna,
        uri = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/",
    )
    val USTC = AptMirror(
        id = "ustc",
        labelRes = UiR.string.workspace_env_mirror_ustc,
        uri = "https://mirrors.ustc.edu.cn/ubuntu-ports/",
    )
    val ALIYUN = AptMirror(
        id = "aliyun",
        labelRes = UiR.string.workspace_env_mirror_aliyun,
        uri = "https://mirrors.aliyun.com/ubuntu-ports/",
    )
    val OFFICIAL = AptMirror(
        id = "official",
        labelRes = UiR.string.workspace_env_mirror_official,
        uri = "http://ports.ubuntu.com/ubuntu-ports/",
    )

    val MIRRORS: List<AptMirror> = listOf(TUNA, USTC, ALIYUN, OFFICIAL)

    /** 没探测到已知源时用哪一个（国内实测最快的）。 */
    val DEFAULT_MIRROR: AptMirror = TUNA

    /** rootfs 里的软件源文件（Ubuntu 24.04 用 deb822 格式）。 */
    const val SOURCES_FILE = "/etc/apt/sources.list.d/ubuntu.sources"

    /** 读当前软件源：把 `URIs:` 行打出来（读不到就什么都不打）。 */
    fun mirrorProbeCommand(): String =
        "grep -h '^URIs:' $SOURCES_FILE 2>/dev/null | head -1 || true"

    /** 解析 [mirrorProbeCommand] 的输出 → 已知镜像；认不出（自定义源/读不到）返回 null。 */
    fun parseMirror(output: String): AptMirror? {
        val uri = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("URIs:") }
            ?.removePrefix("URIs:")
            ?.trim()
            .orEmpty()
        if (uri.isEmpty()) return null
        return MIRRORS.firstOrNull { it.uri == uri }
    }

    /**
     * 改写软件源：deb822 的 `URIs:` 行，以及老式 `sources.list` 里出现的任一同源地址。
     * 幂等 —— 已经是目标镜像也照改，结果不变。
     *
     * ⚠️ **两条 sed 的分隔符都不能出现在各自的模式里**：交替匹配那条的模式里有 `|`
     * （`(a|b|c)`），所以它必须用别的分隔符 —— 之前两条都写 `|`，sed 把模式里的 `|`
     * 当成了 `s` 命令的结束，报 `unknown option to 's'`（用户 2026-09-14 实测：
     * 「安装失败 … sed: -e expression #1, char 105: unknown option to's'」）。
     */
    fun mirrorCommand(mirror: AptMirror): String {
        val known = MIRRORS.joinToString("|") { Regex.escape(it.uri) }
        return buildString {
            appendLine("set -e")
            appendLine("if [ -f $SOURCES_FILE ]; then")
            appendLine("  sed -i -E 's|^URIs:.*|URIs: ${mirror.uri}|' $SOURCES_FILE")
            appendLine("fi")
            appendLine("if [ -f /etc/apt/sources.list ]; then")
            // 分隔符用 `#`（URI 里不会有它），模式里的 `|` 才是正则交替。
            appendLine("  sed -i -E 's#($known)#${mirror.uri}#g' /etc/apt/sources.list")
            append("fi")
        }
    }

    /**
     * 一次 shell 调用探测所有包的安装状态 —— 每个包打一行 `包名=1` 或 `包名=0`。
     *
     * 用 `dpkg-query -W -f='${Status}'` 而不是 `command -v`：环境是「一组包全装齐才算
     * 可用」，按包问最准（`command -v` 会把 `ripgrep` 这种「包名≠命令名」的情况搞错）。
     */
    fun probeCommand(environments: List<WorkspaceEnvironment> = ALL): String {
        val packages = environments.flatMap { it.packages }.distinct()
        return packages.joinToString("\n") { pkg ->
            "if dpkg-query -W -f='\${Status}' $pkg 2>/dev/null | grep -q 'install ok installed'; " +
                "then echo '$pkg=1'; else echo '$pkg=0'; fi"
        }
    }

    /**
     * 修 dpkg 没跑完的 transaction。会被打断的路径有三条：用户点取消、App 进程被杀、
     * 装机换了 APK。任一条之后 dpkg 都处于半途状态，**下一次 apt 会直接拒绝执行**
     * （"dpkg was interrupted, you must manually run 'dpkg --configure -a'"），
     * 所以这里做成一个可复用的步骤：取消后跑一次，装之前也跑一次。
     */
    fun repairCommand(): String =
        "export DEBIAN_FRONTEND=noninteractive\n" +
            "dpkg --configure -a || true"

    /**
     * 刷新包索引。**单独一步**是因为它才是「装个东西怎么这么慢」的主要耗时，
     * 而且「源不通」与「装不上」是两种错，界面要能分开说。
     *
     * 前面先修一次 dpkg（见 [repairCommand]）；`-o Acquire::Languages=none` 砍掉
     * Translation-* 索引（几十 MB 里不小的一块），我们不需要它们。
     */
    fun updateIndexCommand(): String =
        repairCommand() + "\n" +
            "apt-get -o Acquire::Languages=none update -qq"

    /**
     * 第二步：安装。非交互、不装推荐包（recommends 会顺进来一堆用不上的东西）。
     *
     * 装的位置是 rootfs 的 `/usr`、`/usr/lib` —— 在 `/workspace`、`/tmp` 之外。这里
     * **不走工具审批**：这是用户自己在界面上点的动作，不是模型发起的工具调用（跟他在
     * 终端里手敲 apt 同一性质）。
     */
    fun installCommand(environment: WorkspaceEnvironment): String =
        "export DEBIAN_FRONTEND=noninteractive\n" +
            "apt-get install -y --no-install-recommends " +
            environment.packages.joinToString(" ")

    /**
     * 卸载：`remove --purge` 连配置一起删，再 `autoremove` 把只为它装上的依赖带走
     * （node 会拉几十 MB 依赖，不 autoremove 等于没省下空间）。rootfs 是沙箱，
     * autoremove 的副作用可以接受。
     */
    fun uninstallCommand(environment: WorkspaceEnvironment): String =
        "export DEBIAN_FRONTEND=noninteractive\n" +
            "apt-get remove -y --purge " + environment.packages.joinToString(" ") + "\n" +
            "apt-get -y autoremove"

    /**
     * 解析 [probeCommand] 的输出 → 每个环境是否可用（**全部包都装齐**才算 true）。
     * 解不出任何信息的包按未安装处理（失败关闭：宁可让人再点一次安装，也不谎报已装）。
     */
    fun parseInstalled(
        output: String,
        environments: List<WorkspaceEnvironment> = ALL,
    ): Map<String, Boolean> {
        val installed = HashMap<String, Boolean>()
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            val separator = trimmed.lastIndexOf('=')
            if (separator <= 0) continue
            val name = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (name.isEmpty()) continue
            installed[name] = value == "1"
        }
        return environments.associate { environment ->
            environment.id to environment.packages.all { installed[it] == true }
        }
    }
}
