package logic

import com.netonstream.geolite4k.GeoLite4K
import contract.core.IpRegion
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 用 geolite4k 仓里 MaxMind 官方的 GeoLite2-City 测试库（测试从模块目录运行）。 */
class GeoLiteIpRegionResolverTest {
    private val testDb = "../geolite4k/src/nativeTest/resources/GeoLite2-City-Test.mmdb"

    @Test
    fun resolvesPublicAddressesWithTheSharedDisplayRule() = runBlocking {
        val r = GeoLiteIpRegionResolver { GeoLite4K.open(testDb) }
        assertEquals("ready", r.status())
        val cn = r.region("175.16.199.1")!!
        assertEquals("CN", cn.countryCode)
        assertEquals(cn.province?.removeSuffix("省"), cn.display) // 中国大陆显示省级
        val gb = r.region("81.2.69.142")!!
        assertEquals("GB", gb.countryCode)
        assertEquals("英国", gb.display) // 其他国家显示国家名
        assertEquals(IpRegion.UNKNOWN, r.region("8.8.8.8")) // 库里没有
    }

    @Test
    fun privateAndInvalidAddresses() = runBlocking {
        var opened = 0
        val r = GeoLiteIpRegionResolver { opened++; GeoLite4K.open(testDb) }
        assertEquals(IpRegion.UNKNOWN, r.region("192.168.1.10"))
        assertEquals(IpRegion.UNKNOWN, r.region("::1"))
        assertNull(r.region("not-an-ip"))
        assertEquals(0, opened) // 私有地址与非法地址不需要加载库
    }

    @Test
    fun missingDatabaseYieldsNoConclusion() = runBlocking {
        val r = GeoLiteIpRegionResolver { GeoLite4K.open("build/does-not-exist.mmdb") }
        assertNull(r.region("81.2.69.142"))
        assertEquals(IpRegion.UNKNOWN, r.region("10.0.0.1"))
        assertEquals("unavailable", r.status())
    }
}
