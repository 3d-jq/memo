package com.psyche.memo.provider.workspace

import com.psyche.memo.data.assistant.AssistantStore
import com.psyche.memo.data.workspace.WorkspaceEntity
import com.psyche.memo.data.workspace.WorkspaceStore
import com.psyche.memo.workspace.RootfsInstallProgress
import com.psyche.memo.workspace.RootfsInstaller
import com.psyche.memo.workspace.WorkspaceCommandResult
import com.psyche.memo.workspace.WorkspaceFileEntry
import com.psyche.memo.workspace.WorkspaceManager
import com.psyche.memo.workspace.WorkspaceShellStatus
import com.psyche.memo.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * 工作区仓储 —— 移植 RikkaHub `data/repository/WorkspaceRepository.kt`，把记录
 * （[WorkspaceStore]）与目录/rootfs/命令执行（core:workspace 的 [WorkspaceManager]）编排到一起。
 *
 * 与上游的两处结构差异（都是 Memo 的既定形态）：
 *  1. 上游用 Room 的 `Flow` 发变更；Memo 没有 Room，改用 [version] 计数器 —— 与
 *     `MemoryProviderV2` / 其它 Memo 仓储同一套「改完 ++、界面 collect」的做法。
 *  2. 上游从 `SettingsStore` 里清助手的 `workspaceId`；Memo 用 [AssistantStore]。
 *
 * 其余语义逐条照上游：目录缺失只标 BROKEN 不删记录（恢复备份后工作区文件可能还没回来）、
 * rootfs 丢了把 READY/INSTALLING 重置回 DISABLED、安装走 `runInterruptible` 让协程取消
 * 能打断阻塞的下载/解压。
 */
class WorkspaceRepository(
    private val store: WorkspaceStore,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val assistants: AssistantStore,
) {
    private val _version = MutableStateFlow(0)

    /** 记录集合的变更计数（界面 collect 它来重读列表）。 */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun list(): List<WorkspaceEntity> = store.getAll()

    fun get(id: String): WorkspaceEntity? = store.get(id)

    /** 目录/rootfs 与记录对齐；只改状态，绝不自动删记录。 */
    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        for (workspace in store.getAll()) {
            if (!manager.workspaceDir(workspace.root).exists()) {
                // 目录缺失（例如恢复备份后工作区文件没跟着回来）：标 BROKEN 保留记录与助手绑定。
                if (workspace.shellStatus != WorkspaceShellStatus.BROKEN.name) {
                    store.setShellStatus(workspace.id, WorkspaceShellStatus.BROKEN.name)
                }
                continue
            }
            val status = workspace.shellStatus
            val live = status == WorkspaceShellStatus.READY.name || status == WorkspaceShellStatus.INSTALLING.name
            if (live && !manager.hasRootfs(workspace.root)) {
                store.setShellStatus(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
        _version.value++
    }

    suspend fun create(name: String): WorkspaceEntity = withContext(Dispatchers.IO) {
        val entity = store.create(name)
        manager.ensureWorkspace(entity.root)
        _version.value++
        entity
    }

    suspend fun rename(id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val ok = store.rename(id, name)
        if (ok) _version.value++
        ok
    }

    suspend fun isNameTaken(name: String, excludeId: String?): Boolean = withContext(Dispatchers.IO) {
        store.isNameTaken(name, excludeId)
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val ok = store.setToolApproval(id, toolName, needsApproval)
            if (ok) _version.value++
            ok
        }

    /** 记录一次访问（`last_access_at`），供列表排序/「最近使用」用。 */
    suspend fun touch(id: String) = withContext(Dispatchers.IO) {
        store.touch(id)
        _version.value++
    }

    /**
     * 下载并安装 rootfs。安装期间状态是 INSTALLING，成功 READY、抛错 BROKEN；
     * 取消（协程取消或被中断）时把状态还原回安装前的值。
     */
    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = store.get(id) ?: return false
        store.setShellStatus(id, WorkspaceShellStatus.INSTALLING.name)
        _version.value++
        try {
            // runInterruptible：把协程取消转成线程中断，打断 install 里阻塞的下载/解压循环。
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            store.setShellStatus(id, WorkspaceShellStatus.READY.name)
            _version.value++
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.setShellStatus(id, workspace.shellStatus) }
            _version.value++
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) { store.setShellStatus(id, workspace.shellStatus) }
            _version.value++
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            store.setShellStatus(id, WorkspaceShellStatus.BROKEN.name)
            _version.value++
            throw e
        }
    }

    // ---------------------------------------------------------------- 文件区

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = store.get(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root)
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(id: String, path: String): String = withContext(Dispatchers.IO) {
        val workspace = store.get(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.readText(workspace.root, path)
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = store.get(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /**
     * 应用内预览/编辑用的读取，支持两个存储区。FILES 区走 [WorkspaceManager.readText]
     * （自带大小保护）；LINUX 区是整份读进内存，所以这里显式挡一道大小。
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = store.get(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) { "文件过大, 无法预览 ($size bytes)" }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = store.get(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(id: String, area: WorkspaceStorageArea, path: String): Long =
        withContext(Dispatchers.IO) {
            val workspace = store.get(id) ?: error("Workspace not found: $id")
            manager.fileSize(workspace.root, path, area)
        }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = store.get(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    /** 按 rootfs 内绝对路径读大小（支持 /workspace、bind mount 与 rootfs 内部路径）。 */
    suspend fun rootfsFileSize(id: String, path: String): Long = withContext(Dispatchers.IO) {
        val workspace = store.get(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.rootfsFileSize(workspace.root, path)
    }

    /** 按 rootfs 内绝对路径导出内容（工具读技能/上传文件走这条）。 */
    suspend fun exportRootfsFile(id: String, path: String, outputStream: OutputStream) =
        withContext(Dispatchers.IO) {
            val workspace = store.get(id) ?: error("Workspace not found: $id")
            manager.ensureWorkspace(workspace.root)
            manager.exportRootfsFile(workspace.root, path, outputStream)
        }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = store.get(id) ?: return@withContext false
        manager.deleteFile(workspace.root, path, recursive, area)
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = store.get(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult {
        val workspace = store.get(id) ?: error("Workspace not found: $id")
        // runInterruptible：取消时打断阻塞的 Process.waitFor 并杀掉进程。
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root)
            manager.executeCommand(workspace.root, command, cwd, timeoutMillis, stdin)
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = store.get(id) ?: return false
        store.delete(id)
        withContext(Dispatchers.IO) { manager.deleteWorkspace(workspace.root) }
        clearAssistantBindings(id)
        _version.value++
        return true
    }

    /** 删工作区时把绑定它的助手解绑（上游 `cleanupAssistantReferences`）。 */
    private fun clearAssistantBindings(workspaceId: String) {
        assistants.getAll().forEach { assistant ->
            if (assistant.workspaceId == workspaceId) {
                assistants.update(assistant.copy(workspaceId = null, workspaceCwd = null))
            }
        }
    }

    companion object {
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }
}
