package com.psyche.memo

import android.content.Context
import com.psyche.memo.data.db.MemoDatabase
import com.psyche.memo.data.settings.PreferenceRepository
import java.io.File

/**
 * 一次性迁移：早期版本把头像/背景写进了自创目录（`assistant_avatars` /
 * `user_avatars` / `assistant_backgrounds`），而备份打包与存储页统计都按原版
 * 目录名（`avatars` / `images`）找文件 —— 结果是那些文件既没被备份、也被算进
 * 「其他」。这里把文件搬进原版目录，并把数据库/偏好里记录的绝对路径改过去。
 *
 * 幂等：旧目录不存在（或搬空后已删除）就直接跳过；路径重写是纯字符串替换，
 * 重复跑没有副作用。
 */
internal object AssetDirMigration {

    /** 旧目录名 → 新目录名；数据库与偏好里的路径按同样规则重写。 */
    private val DIR_MOVES = listOf(
        Triple("assistant_avatars", "avatars", true),
        Triple("user_avatars", "avatars", true),
        Triple("assistant_backgrounds", "images", false),
    )

    fun run(context: Context, db: MemoDatabase, preferences: PreferenceRepository) {
        val filesDir = context.filesDir
        for ((from, to, isAvatar) in DIR_MOVES) {
            val target = if (isAvatar) AppDirs.avatars(context) else AppDirs.images(context)
            moveInto(File(filesDir, from), target)
        }
        rewriteReferences(db, preferences)
    }

    /** 把 [from] 里的文件逐个搬进 [to]（同名视为已迁移，直接丢弃旧文件）。 */
    private fun moveInto(from: File, to: File) {
        if (!from.isDirectory) return
        from.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val dest = File(to, file.name)
            if (!dest.exists()) {
                if (!file.renameTo(dest)) {
                    runCatching { file.copyTo(dest, overwrite = true) }
                }
            }
            runCatching { file.delete() }
        }
        runCatching { from.delete() }
    }

    /**
     * 把记录在库里的绝对路径从旧目录名改到新目录名：助手的头像/背景在
     * `assistant_rows.payload`，用户头像在 `preference_rows.avatar_value`。
     *
     * 只替换目录名本身，不含路径分隔符 —— Android 上是 `/`，但 Robolectric 跑在
     * 桌面（Windows 是 `\`），带分隔符的模式会漏改。
     */
    private fun rewriteReferences(db: MemoDatabase, preferences: PreferenceRepository) {
        val writable = db.writableDatabase
        for ((from, to, _) in DIR_MOVES) {
            val like = "%$from%"
            writable.execSQL(
                "UPDATE assistant_rows SET payload = REPLACE(payload, ?, ?) WHERE payload LIKE ?",
                arrayOf<Any>(from, to, like),
            )
            writable.execSQL(
                "UPDATE preference_rows SET value = REPLACE(value, ?, ?) WHERE value LIKE ?",
                arrayOf<Any>(from, to, like),
            )
        }
        // 裸 SQL 改了 preference_rows ⇒ 必须让 `PreferenceRepository` 的读缓存失效
        // （否则改过的头像路径在本次进程里读到的还是旧值）。
        preferences.invalidateCache()
    }
}
