package logic

import neton.security.crypto.HmacSha256
import neton.security.digest.Md5

/**
 * CDN 地址策略（infra-media §7.3，产品决定 1）。
 *
 * 公开内容的图片一律给 CDN 地址；是否带令牌由部署的 `config/infra.conf [media.cdn]` 决定：
 *
 * | strategy | 地址 |
 * | --- | --- |
 * | `none` | 不用 CDN，行为同 §7.2（公开类型长期地址，其余 10 分钟签名中转地址） |
 * | `public` | `{base_url}{path}`，CDN 未开鉴权；存储路径内容寻址，地址永不变化 |
 * | `tencent-a` … `tencent-d` | 腾讯云 CDN 鉴权方式 A / B / C / D |
 * | `aliyun-a` … `aliyun-c` | 阿里云 CDN URL 鉴权方式 A / B / C（C 用路径形式） |
 * | `hmac` | 通用：`?exp=<过期时刻>&sig=hex(HMAC-SHA256(key, 路径与处理参数 + "\n" + exp))` |
 *
 * **时间桶确定性签名**：签名时间戳不取「现在」，而是向下取整到 `bucket_seconds` 的整数倍；随机数字段固定 `0`、
 * uid 固定 `0`。同一个桶内，同一文件同一规格对所有人生成逐字节相同的地址，缓存的 HTML 与 CDN 上的图片照样命中。
 * 地址有效期 = 桶长 + 宽限期：桶内任何时刻签出的地址都至少还有「宽限期」那么久可用。云厂商控制台的
 * 「鉴权 URL 有效时长」必须配成同一个值（[MediaCdnConfig.validitySeconds]）。
 */
enum class CdnStrategy(val code: String, val signed: Boolean) {
    NONE("none", false),
    PUBLIC("public", false),
    TENCENT_A("tencent-a", true),
    TENCENT_B("tencent-b", true),
    TENCENT_C("tencent-c", true),
    TENCENT_D("tencent-d", true),
    ALIYUN_A("aliyun-a", true),
    ALIYUN_B("aliyun-b", true),
    ALIYUN_C("aliyun-c", true),
    HMAC("hmac", true),
    ;

    /** 签名时间戳按分钟精度（`YYYYMMDDHHMM`）的方式：桶长必须是 60 的整数倍。 */
    val minuteTimestamp: Boolean get() = this == TENCENT_B || this == ALIYUN_B

    companion object {
        fun fromCode(code: String): CdnStrategy? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/** 图片处理参数的方言（infra-media §7.3 `image_params`）。 */
enum class ImageParamsDialect(val code: String) {
    /** 腾讯云数据万象：`?imageMogr2/thumbnail/<W>x/format/webp`。 */
    TENCENT("tencent"),
    /** 阿里云：`?x-oss-process=image/resize,w_<W>/format,webp`。 */
    ALIYUN("aliyun"),
    /** 不做缩放，只给原图。 */
    NONE("none"),
    ;

    companion object {
        fun fromCode(code: String): ImageParamsDialect? = entries.firstOrNull { it.code == code.trim().lowercase() }
    }
}

/**
 * 派生规格（固定档位，前后端共用的契约：键名进 `MediaView.variants`）。
 * `w*` 限宽等比缩放（不放大：比原图宽的档位不输出）；`og` 是分享卡片的 1200×675 居中裁切。
 */
data class MediaVariant(val key: String, val width: Int, val height: Int? = null) {
    val crop: Boolean get() = height != null

    companion object {
        val W180 = MediaVariant("w180", 180)
        val W360 = MediaVariant("w360", 360)
        val W720 = MediaVariant("w720", 720)
        val W1080 = MediaVariant("w1080", 1080)
        val W1440 = MediaVariant("w1440", 1440)
        val OG = MediaVariant("og", 1200, 675)
        val LADDER: List<MediaVariant> = listOf(W180, W360, W720, W1080, W1440, OG)

        fun byKey(key: String): MediaVariant? = LADDER.firstOrNull { it.key == key }
    }
}

/** 解析与校验后的 `[media.cdn]` 配置。密钥在这里只存值，不进日志。 */
data class MediaCdnConfig(
    val strategy: CdnStrategy = CdnStrategy.NONE,
    /** `scheme://host[:port]`，不带路径、不带末尾斜杠。 */
    val baseUrl: String = "",
    val key: String = "",
    val bucketSeconds: Long = DEFAULT_BUCKET_SECONDS,
    val graceSeconds: Long = DEFAULT_GRACE_SECONDS,
    val imageParams: ImageParamsDialect = ImageParamsDialect.NONE,
) {
    /** 签名地址的有效期（秒）= 桶长 + 宽限期。 */
    val validitySeconds: Long get() = bucketSeconds + graceSeconds

    val enabled: Boolean get() = strategy != CdnStrategy.NONE

    /** 只打印不含密钥的部分。 */
    override fun toString(): String =
        "MediaCdnConfig(strategy=${strategy.code}, baseUrl=$baseUrl, key=${if (key.isEmpty()) "" else "***"}, " +
            "bucketSeconds=$bucketSeconds, graceSeconds=$graceSeconds, imageParams=${imageParams.code})"

    companion object {
        const val DEFAULT_BUCKET_SECONDS = 1800L
        const val DEFAULT_GRACE_SECONDS = 1800L
        /** 通用 HMAC 的最短密钥（字节）。 */
        const val MIN_HMAC_KEY_BYTES = 32
        /** 云厂商主鉴权 Key 的长度范围（两家控制台都是 6–40 位字母数字，阿里 6–32；取并集，交给控制台校验上限）。 */
        private val VENDOR_KEY = Regex("^[A-Za-z0-9]{6,40}$")
        private val ORIGIN = Regex("^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$")
        private val ENV_NAME = Regex("^[A-Z_][A-Z0-9_]*$")

        val DISABLED = MediaCdnConfig()

        /**
         * 从 `infra.conf` 的 `[media.cdn]`（已合并 `NETON_INFRA__MEDIA__CDN__*` 覆盖）解析。
         * 任何一项不合法都抛 [MediaCdnConfigException]：启动失败，绝不静默退回无鉴权。
         *
         * @param section `media.cdn` 段（没有该段时为 null → 策略 none）
         * @param env 读环境变量（密钥）
         */
        fun parse(section: Map<String, Any?>?, env: (String) -> String?): MediaCdnConfig {
            if (section == null) return DISABLED
            fun str(name: String): String? = section[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            fun long(name: String, default: Long): Long {
                val raw = section[name] ?: return default
                return (raw as? Number)?.toLong() ?: raw.toString().trim().toLongOrNull()
                    ?: throw MediaCdnConfigException("media.cdn.$name must be an integer")
            }

            val strategyCode = str("strategy") ?: CdnStrategy.NONE.code
            val strategy = CdnStrategy.fromCode(strategyCode)
                ?: throw MediaCdnConfigException("media.cdn.strategy '$strategyCode' is not one of ${CdnStrategy.entries.joinToString { it.code }}")
            if (strategy == CdnStrategy.NONE) return DISABLED

            val baseUrl = str("base_url")?.trimEnd('/')
                ?: throw MediaCdnConfigException("media.cdn.base_url is required for strategy ${strategy.code}")
            if (!ORIGIN.matches(baseUrl)) {
                throw MediaCdnConfigException("media.cdn.base_url must be an http(s) origin without a path (scheme://host[:port])")
            }

            val paramsCode = str("image_params") ?: ImageParamsDialect.NONE.code
            val imageParams = ImageParamsDialect.fromCode(paramsCode)
                ?: throw MediaCdnConfigException("media.cdn.image_params '$paramsCode' is not one of tencent, aliyun, none")

            val bucket = long("bucket_seconds", DEFAULT_BUCKET_SECONDS)
            val grace = long("grace_seconds", DEFAULT_GRACE_SECONDS)
            if (bucket <= 0) throw MediaCdnConfigException("media.cdn.bucket_seconds must be > 0")
            if (grace < 0) throw MediaCdnConfigException("media.cdn.grace_seconds must be >= 0")
            if (strategy.minuteTimestamp && bucket % 60 != 0L) {
                throw MediaCdnConfigException("media.cdn.bucket_seconds must be a multiple of 60 for ${strategy.code} (minute timestamps)")
            }

            val key = if (!strategy.signed) "" else {
                val keyEnv = str("key_env")
                    ?: throw MediaCdnConfigException("media.cdn.key_env is required for strategy ${strategy.code}")
                if (!ENV_NAME.matches(keyEnv)) throw MediaCdnConfigException("media.cdn.key_env must be an environment variable name")
                val value = env(keyEnv)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw MediaCdnConfigException("environment variable $keyEnv (media.cdn.key_env) is not set")
                when (strategy) {
                    CdnStrategy.HMAC -> if (value.encodeToByteArray().size < MIN_HMAC_KEY_BYTES) {
                        throw MediaCdnConfigException("$keyEnv must be at least $MIN_HMAC_KEY_BYTES bytes for strategy hmac")
                    }
                    else -> if (!VENDOR_KEY.matches(value)) {
                        throw MediaCdnConfigException("$keyEnv must be 6-40 letters and digits (the CDN console's primary auth key)")
                    }
                }
                value
            }
            return MediaCdnConfig(strategy, baseUrl, key, bucket, grace, imageParams)
        }
    }
}

class MediaCdnConfigException(message: String) : IllegalStateException(message)

/** 对外公布的地址策略（`GET /app/infra/media/policy`），网页服务启动时与站点配置交叉校验。 */
data class MediaUrlPolicyView(
    val strategy: String,
    /** 公开 HTML 里媒体地址的有效期（秒）；`public` 永不过期为 null，`none` 是短期签名地址的有效期。 */
    val validitySeconds: Long?,
    /** 时间桶（秒）；不按桶签名时为 null。 */
    val bucketSeconds: Long?,
    val imageParams: String,
)

/**
 * 按 [MediaCdnConfig] 生成 CDN 地址。纯函数（时钟注入），与数据库无关。
 *
 * @param nowSeconds 当前 epoch 秒
 */
class MediaCdnUrls(val config: MediaCdnConfig, private val nowSeconds: () -> Long) {

    data class CdnUrl(val url: String, /** epoch 秒；不过期为 null */ val expiresAt: Long?)

    val enabled: Boolean get() = config.enabled

    /** 当前 epoch 秒（同一次展开的原图与各规格用同一时刻，落在同一个桶里）。 */
    fun currentSeconds(): Long = nowSeconds()

    /** 当前时间所在桶的起点。 */
    fun bucketStart(now: Long = nowSeconds()): Long = now - (now).mod(config.bucketSeconds)

    /**
     * 原图或某个规格的地址。[storagePath] 是存储里的对象路径（`infra_files.path`，不带前导斜杠）。
     * [variant] 为 null 时给原图；图片处理方言为 none 时任何规格都退回原图。
     */
    fun urlFor(storagePath: String, variant: MediaVariant? = null, now: Long = nowSeconds()): CdnUrl {
        check(config.enabled) { "CDN strategy is none" }
        val path = encodePath(storagePath)
        val params = variant?.let { processingQuery(it) }
        if (!config.strategy.signed) return CdnUrl(config.baseUrl + path + (params?.let { "?$it" } ?: ""), null)
        val ts = bucketStart(now)
        return CdnUrl(CdnSigners.sign(config.strategy, config.baseUrl, path, params, ts, config.key, config.validitySeconds), ts + config.validitySeconds)
    }

    /**
     * 图片的派生规格地址（键 = [MediaVariant.key]）。非图片、SVG、方言 none 时为空。
     * 已知原图宽度时不输出比原图宽的 `w*` 档位（不放大）。
     */
    fun variants(storagePath: String, mimeType: String?, width: Int?, now: Long = nowSeconds()): Map<String, String> {
        if (!config.enabled || config.imageParams == ImageParamsDialect.NONE) return emptyMap()
        if (!isProcessableImage(mimeType)) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (v in MediaVariant.LADDER) {
            if (!v.crop && width != null && v.width > width) continue
            out[v.key] = urlFor(storagePath, v, now).url
        }
        return out
    }

    fun policy(relayTtlSeconds: Long): MediaUrlPolicyView = MediaUrlPolicyView(
        strategy = config.strategy.code,
        validitySeconds = when {
            config.strategy == CdnStrategy.NONE -> relayTtlSeconds
            config.strategy.signed -> config.validitySeconds
            else -> null
        },
        bucketSeconds = if (config.strategy.signed) config.bucketSeconds else null,
        imageParams = config.imageParams.code,
    )

    /**
     * 规格 → 所配方言的处理参数（查询串，不含 `?`）。
     *
     * 宽度档同时转成 WebP（CLIENT_SPEC §7：页面图片用 WebP，体积约为同质量 JPEG 的 2/3，现行浏览器全部支持）；
     * `og` 分享图保持原格式：微信等抓取分享卡片的客户端对 WebP 支持不一。
     */
    internal fun processingQuery(v: MediaVariant): String? = when (config.imageParams) {
        ImageParamsDialect.NONE -> null
        ImageParamsDialect.TENCENT ->
            if (v.crop) "imageMogr2/thumbnail/${v.width}x${v.height}r/gravity/center/crop/${v.width}x${v.height}"
            else "imageMogr2/thumbnail/${v.width}x/format/webp"
        ImageParamsDialect.ALIYUN ->
            if (v.crop) "x-oss-process=image/resize,m_fill,w_${v.width},h_${v.height}"
            else "x-oss-process=image/resize,w_${v.width}/format,webp"
    }

    companion object {
        fun disabled(): MediaCdnUrls = MediaCdnUrls(MediaCdnConfig.DISABLED) { 0 }

        fun isProcessableImage(mimeType: String?): Boolean {
            val m = mimeType?.lowercase() ?: return false
            return m.startsWith("image/") && m != "image/svg+xml"
        }

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /**
         * 存储路径 → URL 路径（前导 `/`，每段按 RFC 3986 百分号编码，`/` 保留）。两家云厂商都要求先编码再签名，
         * 签名串里用的就是编码后的路径。
         */
        fun encodePath(storagePath: String): String {
            val sb = StringBuilder("/")
            val segments = storagePath.trim('/').split('/')
            segments.forEachIndexed { i, seg ->
                if (i > 0) sb.append('/')
                for (b in seg.encodeToByteArray()) {
                    val c = (b.toInt() and 0xff).toChar()
                    if (c in UNRESERVED) sb.append(c) else {
                        sb.append('%').append(HEX[(b.toInt() shr 4) and 0xf]).append(HEX[b.toInt() and 0xf])
                    }
                }
            }
            return sb.toString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}

/**
 * 各鉴权方式的签名串与地址形态，严格按云厂商官方文档（测试里复现了文档的示例）：
 *
 * - 腾讯云 A：`{path}?sign={ts}-{rand}-{uid}-md5({path}-{ts}-{rand}-{uid}-{key})`，ts 十进制秒
 * - 腾讯云 B：`/{YYYYMMDDHHMM}/md5({key}{YYYYMMDDHHMM}{path}){path}`，时间为 UTC+8
 * - 腾讯云 C：`/md5({key}{path}{hexTs})/{hexTs}{path}`，hexTs 小写十六进制
 * - 腾讯云 D：`{path}?sign=md5({key}{path}{ts})&t={ts}`
 * - 阿里云 A：`{path}?auth_key={ts}-{rand}-{uid}-md5({path}-{ts}-{rand}-{uid}-{key})`
 * - 阿里云 B：`/{YYYYMMDDHHMM}/md5({key}{YYYYMMDDHHMM}{path}){path}`，时间为 UTC+8
 * - 阿里云 C：`/md5({key}{path}{HEXTS})/{HEXTS}{path}`，HEXTS 大写十六进制（路径形式）
 * - 通用 HMAC：`{path}?{params&}exp={E}&sig=hex(HMAC-SHA256(key, {path}[?{params}] + "\n" + E))`，E = ts + 有效期
 *
 * 云厂商的签名只覆盖路径（文档：「URI 不包含参数」），图片处理参数照原样放在查询串里；
 * 通用 HMAC 的签名覆盖路径与处理参数。rand、uid 固定为 0（确定性签名）。
 */
object CdnSigners {
    private const val RAND = "0"
    private const val UID = "0"

    fun sign(
        strategy: CdnStrategy,
        baseUrl: String,
        path: String,
        params: String?,
        ts: Long,
        key: String,
        validitySeconds: Long,
        /** A 型的随机串。生产恒为 "0"（确定性签名）；只有复现云厂商文档示例的测试会传别的值。 */
        rand: String = RAND,
    ): String {
        val q = params?.let { "$it&" } ?: ""
        val tail = params?.let { "?$it" } ?: ""
        return when (strategy) {
            CdnStrategy.TENCENT_A -> "$baseUrl$path?${q}sign=$ts-$rand-$UID-${Md5.hex("$path-$ts-$rand-$UID-$key")}"
            CdnStrategy.ALIYUN_A -> "$baseUrl$path?${q}auth_key=$ts-$rand-$UID-${Md5.hex("$path-$ts-$rand-$UID-$key")}"
            CdnStrategy.TENCENT_B, CdnStrategy.ALIYUN_B -> {
                val t = utc8Minute(ts)
                "$baseUrl/$t/${Md5.hex("$key$t$path")}$path$tail"
            }
            CdnStrategy.TENCENT_C -> {
                val h = ts.toString(16)
                "$baseUrl/${Md5.hex("$key$path$h")}/$h$path$tail"
            }
            CdnStrategy.ALIYUN_C -> {
                val h = ts.toString(16).uppercase()
                "$baseUrl/${Md5.hex("$key$path$h")}/$h$path$tail"
            }
            CdnStrategy.TENCENT_D -> "$baseUrl$path?${q}sign=${Md5.hex("$key$path$ts")}&t=$ts"
            CdnStrategy.HMAC -> {
                val exp = ts + validitySeconds
                val signed = path + (params?.let { "?$it" } ?: "")
                "$baseUrl$path?${q}exp=$exp&sig=${hmacSignature(key, signed, exp)}"
            }
            CdnStrategy.NONE, CdnStrategy.PUBLIC -> error("strategy ${strategy.code} does not sign")
        }
    }

    /** 通用 HMAC 的签名值（自建边缘校验时用同一函数）：`hex(HMAC-SHA256(key, signedPath + "\n" + exp))`。 */
    fun hmacSignature(key: String, signedPath: String, exp: Long): String =
        HmacSha256.signHex(key.encodeToByteArray(), "$signedPath\n$exp".encodeToByteArray())

    /** epoch 秒 → UTC+8 的 `YYYYMMDDHHMM`（两家 B 型的时间格式）。 */
    fun utc8Minute(epochSeconds: Long): String {
        val local = epochSeconds + 8 * 3600
        val days = (local).floorDiv(86_400L)
        val secOfDay = (local).mod(86_400L)
        val (y, m, d) = civilFromDays(days)
        val hh = secOfDay / 3600
        val mm = (secOfDay % 3600) / 60
        return buildString {
            append(y.toString().padStart(4, '0'))
            append(m.toString().padStart(2, '0'))
            append(d.toString().padStart(2, '0'))
            append(hh.toString().padStart(2, '0'))
            append(mm.toString().padStart(2, '0'))
        }
    }

    /** 公历日期（H. Hinnant 的 days-from-civil 逆算法），不依赖时区库。 */
    private fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
        val z = z0 + 719_468
        val era = (z).floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple(if (m <= 2) y + 1 else y, m, d)
    }
}
