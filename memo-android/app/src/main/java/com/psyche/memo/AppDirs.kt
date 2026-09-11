package com.psyche.memo

import android.content.Context
import java.io.File

/**
 * 应用内资源目录 —— 名字与原版 `AppDirectories` 一致（`upload` / `images` /
 * `avatars` / `fonts`）。
 *
 * 这不是审美问题：备份把资产目录按原版 root 名打包
 * （`BackupArchiveCodec.ASSET_ROOTS`），存储页也按这些名字归类统计
 * （`StorageSpace.classify`）。此前运行时自创了 `assistant_avatars` /
 * `user_avatars` / `assistant_backgrounds`，导致备份漏掉头像与背景、存储页把
 * 头像算进「其他」。
 *
 * 助手头像与用户头像共用 `avatars/`（原版 `getAvatarsDirectory` 就是这样），
 * 文件名各自带前缀 + 时间戳，不会冲突。
 */
object AppDirs {

    fun avatars(context: Context): File =
        File(context.filesDir, "avatars").apply { mkdirs() }

    /** 助手背景等图片（原版 `getImagesDirectory`）。 */
    fun images(context: Context): File =
        File(context.filesDir, "images").apply { mkdirs() }
}
