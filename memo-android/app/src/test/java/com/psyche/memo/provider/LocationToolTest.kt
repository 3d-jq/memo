package com.psyche.memo.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「获取当前位置」本地工具里能纯测的那几块：缓存新鲜度、provider 取舍、
 * 结果/错误 JSON 形状、逆地理字段拼装顺序。
 *
 * 真定位（LocationManager 回调、Geocoder）不在单测里跑 —— 那是真机口径。
 */
class LocationToolTest {

    private val now = 1_700_000_000_000L

    @Test
    fun lastKnownFixIsOnlyTrustedWithinTenMinutes() {
        assertTrue(LocationTool.isFresh(timeMs = now - 9 * 60_000L, nowMs = now))
        assertTrue(LocationTool.isFresh(timeMs = now - LocationTool.FRESH_CACHE_MS + 1, nowMs = now))
        assertFalse(LocationTool.isFresh(timeMs = now - LocationTool.FRESH_CACHE_MS, nowMs = now))
        assertFalse(LocationTool.isFresh(timeMs = now - 30 * 60_000L, nowMs = now))
        // 未来时间戳（provider 时钟漂）不算新鲜，也别拿它当缓存。
        assertFalse(LocationTool.isFresh(timeMs = now + 5_000L, nowMs = now))
    }

    /** GPS 优先（精度最好），其次网络，最后 passive；一个都没有就判 GPS 未开。 */
    @Test
    fun providerPreferenceIsGpsThenNetworkThenPassive() {
        fun enabled(vararg names: String) = LocationTool.ALL_PROVIDERS.filter { it in names }
        assertEquals(
            LocationTool.PROVIDER_GPS,
            LocationTool.pickProvider(setOf(LocationTool.PROVIDER_NETWORK, LocationTool.PROVIDER_GPS)),
        )
        assertEquals(
            LocationTool.PROVIDER_NETWORK,
            LocationTool.pickProvider(setOf(LocationTool.PROVIDER_PASSIVE, LocationTool.PROVIDER_NETWORK)),
        )
        assertEquals(LocationTool.PROVIDER_PASSIVE, LocationTool.pickProvider(setOf(LocationTool.PROVIDER_PASSIVE)))
        assertEquals(null, LocationTool.pickProvider(emptySet()))
    }

    @Test
    fun resultJsonCarriesTheUpstreamKeysAndOnlyKnownAddressParts() {
        val json = LocationTool.resultJson(
            fix = LocationTool.Fix(
                latitude = 30.2741,
                longitude = 120.1551,
                accuracyMeters = 18.0,
                altitudeMeters = 12.0,
                timeMs = now,
                provider = LocationTool.PROVIDER_GPS,
            ),
            address = LocationTool.AddressParts(
                line = "西湖区, 杭州市, 浙江省, 中国",
                street = "西湖区",
                city = "杭州市",
                region = "浙江省",
                country = "中国",
            ),
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("30.2741", obj["latitude"]?.toString()?.trim('"'))
        assertEquals("120.1551", obj["longitude"]?.toString()?.trim('"'))
        assertEquals("18", obj["accuracy"]?.toString())
        assertEquals("1700000000000", obj["timestamp"]?.toString())
        assertEquals("杭州市", obj["city"]?.toString()?.trim('"'))
        assertEquals("浙江省", obj["region"]?.toString()?.trim('"'))
        assertEquals("中国", obj["country"]?.toString()?.trim('"'))
        assertEquals(null, obj["error"])
    }

    /** 逆地理拿不到时**只出坐标**，不能把整次调用判失败（国产 ROM 上 Geocoder 常空）。 */
    @Test
    fun resultJsonWithoutAddressStillSucceedsWithoutEmptyKeys() {
        val json = LocationTool.resultJson(
            fix = LocationTool.Fix(30.0, 120.0, 25.0, 0.0, now, LocationTool.PROVIDER_NETWORK),
            address = null,
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(null, obj["address"])
        assertEquals(null, obj["city"])
        assertEquals("30.0", obj["latitude"]?.toString()?.trim('"'))
    }

    /** 地址行按「街道 → 区县 → 市 → 省 → 国」拼，空段不进串（照参考实现的顺序）。 */
    @Test
    fun addressLineSkipsBlanksAndKeepsOrder() {
        val parts = LocationTool.assembleAddress(
            street = "",
            locality = " 杭州市 ",
            subLocality = null,
            subAdministrativeArea = "西湖区",
            administrativeArea = "浙江省",
            country = "中国",
        )
        assertEquals("杭州市, 西湖区, 浙江省, 中国", parts.line)
        assertEquals("杭州市", parts.city)
        assertEquals("浙江省", parts.region)
        assertEquals("中国", parts.country)
        assertEquals(null, parts.street)
    }

    @Test
    fun emptyAddressYieldsNoLine() {
        val parts = LocationTool.assembleAddress(
            street = null, locality = "", subLocality = null,
            subAdministrativeArea = "  ", administrativeArea = null, country = null,
        )
        assertEquals("", parts.line)
        assertEquals(null, parts.city)
    }

    @Test
    fun errorsAreJsonWithACodeAndAMessage() {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(
            LocationTool.errorJson(LocationTool.ERROR_PERMISSION_DENIED, "位置权限未开启"),
        ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("permission_denied", obj["error"]?.toString()?.trim('"'))
        assertEquals("位置权限未开启", obj["message"]?.toString()?.trim('"'))
    }
}
