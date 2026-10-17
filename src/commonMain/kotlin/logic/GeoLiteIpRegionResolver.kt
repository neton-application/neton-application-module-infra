package logic

import com.netonstream.geolite4k.GeoLite4K
import com.netonstream.geolite4k.GeoLiteLookupResult
import com.netonstream.geolite4k.GeoLiteResolver
import contract.core.IpRegion
import contract.core.IpRegionResolver
import contract.core.IpRegions
import neton.core.config.ConfigLoader

/**
 * 套件默认的 [IpRegionResolver]（spec content-platform-architecture §5.6）：本地 GeoLite2 City 库，进程内查询，不出网。
 *
 * 库路径来自 `config/geolite.conf` 的 `path`（缺省 [GeoLite4K.DEFAULT_DATABASE_PATH]）。库在**第一次查询**时整体读入内存，
 * 之后全部是内存查询；不用属地的宿主不付这份内存。库缺失或损坏时对公网地址返回 null（使用方不记录属地），
 * 私有 / 保留地址不需要库，直接返回 [IpRegion.UNKNOWN]。
 *
 * 只在 infra 实现一次：account（后台展示注册 / 登录属地）、comment（评论的 IP 属地）等经端口使用同一个实例。
 */
class GeoLiteIpRegionResolver(open: () -> GeoLiteResolver) : IpRegionResolver {

    private val resolver: GeoLiteResolver by lazy(open)

    override suspend fun region(ip: String): IpRegion? = lookup(ip)

    fun lookup(ip: String): IpRegion? {
        val address = ip.trim().substringBefore('%')
        return when (IpRegions.classify(address)) {
            IpRegions.Kind.INVALID -> null
            IpRegions.Kind.NON_PUBLIC -> IpRegion.UNKNOWN
            IpRegions.Kind.PUBLIC -> when (val r = runCatching { resolver.lookup(address) }.getOrNull()) {
                is GeoLiteLookupResult.Found -> r.location.let { IpRegion.of(it.countryCode, it.country, it.province, it.city) }
                GeoLiteLookupResult.NotFound -> IpRegion.UNKNOWN
                else -> null
            }
        }
    }

    /** 库状态（后台、健康检查用）：ready / unavailable / invalid。 */
    fun status(): String = when (resolver.status) {
        is com.netonstream.geolite4k.GeoLiteDatabaseStatus.Ready -> "ready"
        is com.netonstream.geolite4k.GeoLiteDatabaseStatus.Unavailable -> "unavailable"
        is com.netonstream.geolite4k.GeoLiteDatabaseStatus.Invalid -> "invalid"
    }

    companion object {
        /** 从 `config/geolite.conf` 读库路径。 */
        fun fromConfig(): GeoLiteIpRegionResolver = GeoLiteIpRegionResolver {
            val config = ConfigLoader.loadModuleConfig("geolite")
            val path = ConfigLoader.getString(config, "path")?.trim()?.takeIf(String::isNotEmpty) ?: GeoLite4K.DEFAULT_DATABASE_PATH
            GeoLite4K.open(path)
        }
    }
}
