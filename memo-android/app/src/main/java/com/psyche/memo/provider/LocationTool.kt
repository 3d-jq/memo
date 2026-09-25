package com.psyche.memo.provider

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 「获取当前位置」`get_current_location` 的执行器。
 *
 * ⚠️ **超出上游的平台能力**：上游 kelivo 的 `DeviceLocalTools.locationSupported` 是 iOS-only
 * （`iosDeviceToolsSupported`），Android 侧压根没实现。工具**名字、空参数、描述文案、
 * 不在需要审批的名单里**（那份名单 2026-09-25 已整块拆除）全部照上游（`local_tools_service.dart` 的
 * `_currentLocationDefinition`），只有执行是安卓侧自写：运行时策略照搬已在真机用过的参考
 * 实现 —— 先看权限 → 再看定位服务开关 → **10 分钟内的缓存位置直接秒回** → 否则实时定位
 * 10 秒超时 → 超时回退过期缓存 → 全拿不到才报错；逆地理用平台 [Geocoder]（零 API key、
 * 零网络依赖），**拿不到地址就只给坐标，不判失败**（国产 ROM 没有 Google 服务时 Geocoder
 * 常返回空）。
 *
 * 工程里没有 Play Services，所以用 [LocationManager] 而不是融合定位 Provider。
 */
object LocationTool {

    const val TOOL_NAME = "get_current_location"

    const val PROVIDER_GPS = LocationManager.GPS_PROVIDER
    const val PROVIDER_NETWORK = LocationManager.NETWORK_PROVIDER
    const val PROVIDER_PASSIVE = LocationManager.PASSIVE_PROVIDER

    /** 优先级从高到低；[pickProvider] 与这个顺序一致。 */
    val ALL_PROVIDERS = listOf(PROVIDER_GPS, PROVIDER_NETWORK, PROVIDER_PASSIVE)

    /** 缓存位置算「新鲜」的上限（参考实现同值：10 分钟）。 */
    const val FRESH_CACHE_MS = 10 * 60_000L

    /** 实时定位的等待上限。 */
    const val FIX_TIMEOUT_MS = 10_000L

    const val ERROR_PERMISSION_DENIED = "permission_denied"
    const val ERROR_SERVICE_DISABLED = "location_service_disabled"
    const val ERROR_TIMEOUT = "timeout"

    /** 给模型看的那段描述（照上游 `_currentLocationDefinition` 的措辞）。 */
    const val DESCRIPTION =
        "Get the user's current location from the device (one-shot, When In Use). " +
        "Returns latitude, longitude, accuracy in meters, timestamp, and optional " +
        "city/region/country from reverse geocoding. Do not request this unless the " +
        "user asked for their location or it is needed for weather. " +
        "Requires the Location permission; if it is not granted, an error is returned."

    /** 一次定位结果（换算成工具输出口径）。 */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Double,
        val altitudeMeters: Double,
        val timeMs: Long,
        val provider: String,
    )

    /** 逆地理出来的地址片段；[line] 为空表示没拿到。 */
    data class AddressParts(
        val line: String,
        val street: String?,
        val city: String?,
        val region: String?,
        val country: String?,
    )

    /**
     * @param askPermission 未授权时向界面要一次系统弹窗（挂起等结果，见
     *   [com.psyche.memo.ui.chat.LocationPermissionService]）。已授权时**不会**被调用。
     */
    suspend fun execute(context: Context, askPermission: suspend () -> Boolean): String {
        val appContext = context.applicationContext
        if (!hasPermission(appContext) && !askPermission()) {
            return errorJson(
                ERROR_PERMISSION_DENIED,
                appContext.getString(com.psyche.memo.ui.R.string.location_tool_error_permission),
            )
        }
        val provider = pickProvider(enabledProviders(appContext))
            ?: return errorJson(
                ERROR_SERVICE_DISABLED,
                appContext.getString(com.psyche.memo.ui.R.string.location_tool_error_service),
            )

        val now = System.currentTimeMillis()
        lastKnownFix(appContext)?.takeIf { isFresh(it.timeMs, now) }?.let { return succeed(appContext, it) }

        // 实时拿不到就用过期缓存 —— 模型要的是「在哪个城市」，旧一点好过没有。
        val fix = requestFix(appContext, provider) ?: lastKnownFix(appContext)
            ?: return errorJson(
                ERROR_TIMEOUT,
                appContext.getString(com.psyche.memo.ui.R.string.location_tool_error_timeout),
            )
        return succeed(appContext, fix)
    }

    // ------------------------------------------------------------------ 纯逻辑（可单测）

    /** 缓存位置能不能直接用：10 分钟内；未来时间戳（provider 时钟会漂）不算。 */
    fun isFresh(timeMs: Long, nowMs: Long): Boolean = (nowMs - timeMs) in 0 until FRESH_CACHE_MS

    /** GPS > 网络 > passive；一个都没开返回 null（= 定位服务未开启）。 */
    fun pickProvider(enabled: Set<String>): String? = ALL_PROVIDERS.firstOrNull { it in enabled }

    /**
     * 地址片段拼装，顺序照参考实现（街道 → 市 → 区县 → 省 → 国），空段不进串。
     * 参数分开传而不是收一个 [Address]，是为了让这层能纯测。
     */
    fun assembleAddress(
        street: String?,
        locality: String?,
        subLocality: String?,
        subAdministrativeArea: String?,
        administrativeArea: String?,
        country: String?,
    ): AddressParts {
        fun trimmed(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
        return AddressParts(
            line = listOfNotNull(
                trimmed(street),
                trimmed(locality),
                trimmed(subAdministrativeArea),
                trimmed(administrativeArea),
                trimmed(country),
            ).joinToString(", "),
            street = trimmed(street),
            city = trimmed(locality) ?: trimmed(subLocality),
            region = trimmed(administrativeArea) ?: trimmed(subAdministrativeArea),
            country = trimmed(country),
        )
    }

    /** 上游口径：latitude / longitude / accuracy / timestamp + 可选 city/region/country。 */
    fun resultJson(fix: Fix, address: AddressParts?): String =
        buildJsonObject {
            put("latitude", JsonPrimitive(fix.latitude))
            put("longitude", JsonPrimitive(fix.longitude))
            put("accuracy", JsonPrimitive(fix.accuracyMeters.roundToInt()))
            put("altitude", JsonPrimitive(fix.altitudeMeters.roundToInt()))
            put("timestamp", JsonPrimitive(fix.timeMs))
            put("provider", fix.provider)
            address?.takeIf { it.line.isNotEmpty() }?.let { parts ->
                put("address", parts.line)
                parts.city?.let { put("city", it) }
                parts.region?.let { put("region", it) }
                parts.country?.let { put("country", it) }
            }
        }.toString()

    /**
     * 工具错误走规范形状（`type=tool_error` + `status=error` + `tool`），并且**每种失败
     * 各带一句自己的补救办法** —— 定位的三种失败对用户是完全不同的动作（给权限 /
     * 打开定位服务 / 等一下），只回一句「失败了」模型就只会再敲一次同一颗调用。
     */
    fun errorJson(code: String, message: String): String =
        com.psyche.memo.provider.tool.ToolResults.error(
            code = code,
            message = message,
            tool = TOOL_NAME,
            instruction = when (code) {
                ERROR_PERMISSION_DENIED ->
                    "Location permission was refused. Tell the user to grant it (system dialog " +
                        "or app settings) and that you cannot state a location until then."
                ERROR_SERVICE_DISABLED ->
                    "Location services are switched off on the device. Ask the user to turn them " +
                        "on; do not state a location."
                ERROR_TIMEOUT ->
                    "No fix arrived in time. Ask the user which city they are in, or retry once " +
                        "later — do not guess a location."
                else -> com.psyche.memo.provider.tool.ToolResults.REPORT_FAILURE
            },
        )

    // ------------------------------------------------------------------ 平台侧

    fun hasPermission(context: Context): Boolean {
        fun granted(permission: String) =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return granted(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun manager(context: Context): LocationManager? =
        runCatching { context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }.getOrNull()

    private fun enabledProviders(context: Context): Set<String> {
        val locationManager = manager(context) ?: return emptySet()
        return runCatching {
            ALL_PROVIDERS.filter { locationManager.isProviderEnabled(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun Location.toFix(provider: String) = Fix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.toDouble(),
        altitudeMeters = altitude,
        timeMs = time,
        provider = provider,
    )

    /** 各 provider 的 last-known 里取最新的（同样新时取更准的那个）。 */
    @SuppressLint("MissingPermission")
    private fun lastKnownFix(context: Context): Fix? {
        val locationManager = manager(context) ?: return null
        return ALL_PROVIDERS.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider)?.toFix(provider) }.getOrNull()
        }.maxWithOrNull(compareBy({ it.timeMs }, { -it.accuracyMeters }))
    }

    /** 等一次实时定位；[FIX_TIMEOUT_MS] 内拿不到返回 null（调用方回退缓存）。 */
    @SuppressLint("MissingPermission")
    private suspend fun requestFix(context: Context, provider: String): Fix? =
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
            val locationManager = manager(context) ?: return@withTimeoutOrNull null
            suspendCancellableCoroutine<Fix?> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = finishWith(location)

                    override fun onProviderDisabled(disabled: String) = finishWith(null)

                    @Deprecated("旧 API，仅需覆写以兼容")
                    override fun onStatusChanged(source: String?, status: Int, extras: Bundle?) {}

                    private fun finishWith(location: Location?) {
                        runCatching { locationManager.removeUpdates(this) }
                        if (continuation.isActive) {
                            continuation.resume(location?.toFix(provider))
                        }
                    }
                }
                val registered = runCatching {
                    locationManager.requestLocationUpdates(provider, 1L, 0f, listener, Looper.getMainLooper())
                }.isSuccess
                if (!registered) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { runCatching { locationManager.removeUpdates(listener) } }
            }
        }

    private suspend fun succeed(context: Context, fix: Fix): String =
        resultJson(fix, reverseGeocode(context, fix))

    /** 逆地理；任何失败都退化成「只给坐标」。 */
    private suspend fun reverseGeocode(context: Context, fix: Fix): AddressParts? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!Geocoder.isPresent()) return@withContext null
                val first: Address = Geocoder(context, Locale.getDefault())
                    .getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull()
                    ?: return@withContext null
                assembleAddress(
                    street = first.thoroughfare,
                    locality = first.locality,
                    subLocality = first.subLocality,
                    subAdministrativeArea = first.subAdminArea,
                    administrativeArea = first.adminArea,
                    country = first.countryName,
                )
            }.getOrNull()
        }
}
