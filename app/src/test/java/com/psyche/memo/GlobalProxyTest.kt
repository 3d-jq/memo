package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.settings.PreferenceRepository
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 网络代理的接线检查：network_proxy_page 写的七个键必须真的被 OkHttp 的
 * selector/authenticator 读到（此前是七个键写了没人读的死设置）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlobalProxyTest {

    private lateinit var container: AppContainerImpl
    private lateinit var prefs: PreferenceRepository

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
        prefs = container.preferenceRepository
    }

    private fun write(key: String, value: String) = prefs.writeJson(key, value)

    private fun enable(
        type: String = "http",
        host: String = "proxy.local",
        port: Int = 7890,
        bypass: String = "localhost,127.0.0.1,10.0.0.0/8,::1",
    ) {
        write("global_proxy_enabled_v1", "true")
        write("global_proxy_type_v1", "\"$type\"")
        write("global_proxy_host_v1", "\"$host\"")
        write("global_proxy_port_v1", "\"$port\"")
        write("global_proxy_username_v1", "\"user\"")
        write("global_proxy_password_v1", "\"pass\"")
        write("global_proxy_bypass_v1", "\"$bypass\"")
    }

    private fun selector() = GlobalProxy.selector(prefs)

    @Test
    fun `disabled proxy selects direct`() {
        write("global_proxy_enabled_v1", "false")
        val selected = selector().select(URI("https://api.example.com/v1"))
        assertEquals(listOf(Proxy.NO_PROXY), selected)
        assertNull(GlobalProxy.credentialsFor(prefs))
    }

    @Test
    fun `http proxy is selected with unresolved address`() {
        enable()
        val selected = selector().select(URI("https://api.example.com/v1"))
        val proxy = selected.single()
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("proxy.local", address.hostName)
        assertEquals(7890, address.port)
        // http 代理带 Basic 认证（addProxyCredentials）。
        assertTrue(GlobalProxy.credentialsFor(prefs)!!.startsWith("Basic "))
    }

    @Test
    fun `socks5 selects the socks type`() {
        enable(type = "socks5")
        val proxy = selector().select(URI("https://api.example.com/v1")).single()
        assertEquals(Proxy.Type.SOCKS, proxy.type())
    }

    @Test
    fun `bypass rules skip the proxy for local hosts and cidr ranges`() {
        enable()
        val selector = selector()
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://localhost:11434")))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://127.0.0.1:1234")))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://10.1.2.3:80")))
        // 外网照常走代理。
        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://api.example.com")).single().type())
    }

    @Test
    fun `isBypassed handles suffix and cidr forms`() {
        assertTrue(GlobalProxy.isBypassed("localhost", "localhost,::1"))
        assertTrue(GlobalProxy.isBypassed("api.local", ".local"))
        assertTrue(GlobalProxy.isBypassed("192.168.1.5", "192.168.0.0/16"))
        assertTrue(!GlobalProxy.isBypassed("api.example.com", "localhost,10.0.0.0/8"))
    }

    @Test
    fun `an invalid host or port reads as no proxy`() {
        write("global_proxy_enabled_v1", "true")
        write("global_proxy_type_v1", "\"http\"")
        write("global_proxy_host_v1", "\"\"")
        write("global_proxy_port_v1", "\"8080\"")
        assertNull(GlobalProxy.read(prefs))
    }
}
