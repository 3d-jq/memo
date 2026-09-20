package com.psyche.memo.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **asset 路径 → 已解码位图**的进程内缓存（2026-09-20 卡顿修复）。
 *
 * 背景：供应商/模型行每滚出一行就要把品牌 logo 与 `deepthink.svg` **冷解码一次**
 * （coil-svg → androidsvg，用户 2026-09-20「设置里面获取模型界面的滑动，感觉帧率好低」
 * 的头号嫌疑）。图标总数只有 59 枚，同一张图在同一屏里被重复解码几十次。
 *
 * 解码**仍走 Coil 自己的管线**（`imageLoader.execute`），所以像素与原来 `AsyncImage`
 * 画出来的完全一致：同一个 `SvgDecoder`、同一个目标尺寸、软件位图（Coil 对
 * `DataSource.ASSETS` 本来就禁用硬件位图）。渲染尺寸取 `Dp.roundToPx()`，与
 * `Modifier.size(dp)` 解析出来的约束是同一个取整，因此落位几何也不变。
 *
 * 着色（`ColorFilter.tint`）**没有烘进位图**，仍旧在绘制时施加 —— 与现状一致，
 * 缓存键因此只跟「资产路径 + 目标像素」有关，与主题无关。
 *
 * 未就绪时本函数返回 null，调用方保持现在的 `AsyncImage` 分支不变（那一帧
 * `AsyncImage` 自己也还没解码完，画的是同一枚空底色圆），所以**首帧外观不变**。
 */

/** 缓存键（纯函数，可单测）。同一资产在不同尺寸下是两份产物。 */
internal fun svgIconCacheKey(asset: String, px: Int): String = "$asset@$px"

/**
 * 只有走 asset 的 SVG 才接管：远程/文件头像、PNG 一类仍旧交给 Coil，
 * 免得把非 SVG 的解码语义搅进来。
 */
internal fun usesCachedSvgDecoding(asset: String): Boolean =
    asset.startsWith("file:///android_asset/") && asset.endsWith(".svg", ignoreCase = true)

private object SvgIconCache {
    /** 按条目数计（一枚 28dp@3.5 ≈ 40KB，128 枚上限 ≈ 5MB，够放全部 59 个品牌图标 × 2 种尺寸）。 */
    private const val MAX_ENTRIES = 128

    private val bitmaps = LruCache<String, ImageBitmap>(MAX_ENTRIES)

    /** 同键只解码一次：后来的行 await 同一个结果。 */
    private val pending = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    /** 解码失败过的键不再重试（否则会随每一行重发一次请求）。 */
    private val failed = HashSet<String>()

    private var loader: ImageLoader? = null

    fun peek(key: String): ImageBitmap? = bitmaps.get(key)

    fun isFailed(key: String): Boolean = synchronized(failed) { key in failed }

    suspend fun load(context: Context, asset: String, px: Int): ImageBitmap? {
        val key = svgIconCacheKey(asset, px)
        peek(key)?.let { return it }
        if (isFailed(key)) return null
        val fresh = CompletableDeferred<ImageBitmap?>()
        val running = synchronized(pending) { pending.putIfAbsent(key, fresh) }
        if (running != null) return running.await()
        val decoded = runCatching { decode(context.applicationContext, asset, px) }.getOrNull()
        if (decoded != null) bitmaps.put(key, decoded) else synchronized(failed) { failed += key }
        synchronized(pending) { pending.remove(key, fresh) }
        fresh.complete(decoded)
        return decoded
    }

    private suspend fun decode(appContext: Context, asset: String, px: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val current = loader ?: appContext.imageLoader.also { loader = it }
            val drawable = current.execute(
                ImageRequest.Builder(appContext)
                    .data(asset)
                    .size(px, px)
                    .allowHardware(false)
                    .build(),
            ).drawable ?: return@withContext null
            drawable.toBitmap(px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
}

/**
 * 取一缓存的 SVG 位图；未就绪返回 null（调用方回落到 `AsyncImage`）。
 *
 * 组合期只做一次内存查表（[SvgIconCache.peek]），解码在 `Dispatchers.IO` 上跑，
 * 符合 PORTING §5.13「组合期不许读文件/解码」。
 */
@Composable
internal fun cachedSvgIcon(asset: String?, size: Dp): ImageBitmap? {
    if (asset == null || !usesCachedSvgDecoding(asset)) return null
    val appContext = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { size.roundToPx() }
    if (px <= 0) return null
    var icon by remember(asset, px) { mutableStateOf(SvgIconCache.peek(svgIconCacheKey(asset, px))) }
    LaunchedEffect(asset, px) {
        val key = svgIconCacheKey(asset, px)
        if (icon == null && !SvgIconCache.isFailed(key)) {
            icon = SvgIconCache.load(appContext, asset, px)
        }
    }
    return icon
}
