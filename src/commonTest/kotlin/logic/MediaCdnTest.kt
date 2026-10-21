package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CDN 地址策略（infra-media §7.3）。
 *
 * 每种鉴权方式先用云厂商官方文档里的示例验证签名串与地址形态逐字一致（示例的输入、MD5 与最终地址都抄自文档），
 * 再验证时间桶确定性。
 */
class MediaCdnTest {

    // ---------------------------------------------------------------- 腾讯云（文档示例 key 与时间相同）
    // 示例时间 2020-02-27 16:10:32（UTC+8）= 1582791032

    private val tencentKey = "dimtm5evg50ijsx2hvuwyfoiu65"
    private val tencentTs = 1582791032L

    /** https://cloud.tencent.com/document/product/228/41623 （TypeA） */
    @Test
    fun tencentTypeAMatchesOfficialExample() {
        val url = CdnSigners.sign(CdnStrategy.TENCENT_A, "http://cloud.tencent.com", "/test.jpg", null, tencentTs, tencentKey, 1, rand = "im1acp76sx9sdqe601v")
        assertEquals("http://cloud.tencent.com/test.jpg?sign=1582791032-im1acp76sx9sdqe601v-0-3fbb88382c9356b6faaf9d68c7b2ae3a", url)
    }

    /** https://cloud.tencent.com/document/product/228/41871 （TypeB：时间 YYYYMMDDHHMM，UTC+8） */
    @Test
    fun tencentTypeBMatchesOfficialExample() {
        assertEquals("202002271610", CdnSigners.utc8Minute(tencentTs))
        val url = CdnSigners.sign(CdnStrategy.TENCENT_B, "http://cloud.tencent.com", "/test.jpg", null, tencentTs, tencentKey, 1)
        assertEquals("http://cloud.tencent.com/202002271610/2e03a07cfa55a47768226d3e5ea82a8d/test.jpg", url)
    }

    /** https://cloud.tencent.com/document/product/228/41624 （TypeC：十六进制时间，小写） */
    @Test
    fun tencentTypeCMatchesOfficialExample() {
        val url = CdnSigners.sign(CdnStrategy.TENCENT_C, "http://cloud.tencent.com", "/test.jpg", null, tencentTs, tencentKey, 1)
        assertEquals("http://cloud.tencent.com/7913fc0c5c9e92dd3633b7895152bbb2/5e577978/test.jpg", url)
    }

    /** https://cloud.tencent.com/document/product/228/41625 （TypeD：`sign` + `t`） */
    @Test
    fun tencentTypeDMatchesOfficialExample() {
        val url = CdnSigners.sign(CdnStrategy.TENCENT_D, "http://cloud.tencent.com", "/test.jpg", null, tencentTs, tencentKey, 1)
        assertEquals("http://cloud.tencent.com/test.jpg?sign=900a5049aa8ac1ab144527d9c2be4cea&t=1582791032", url)
    }

    // ---------------------------------------------------------------- 阿里云（文档示例 PrivateKey 相同）

    private val aliyunKey = "aliyuncdnexp1234"

    /** https://help.aliyun.com/zh/cdn/user-guide/type-a-signing （示例 rand、uid 都是 0，正是本模块的确定性取值） */
    @Test
    fun aliyunTypeAMatchesOfficialExample() {
        val url = CdnSigners.sign(CdnStrategy.ALIYUN_A, "http://domain.example.com", "/video/standard/test.mp4", null, 1444435200L, aliyunKey, 1)
        assertEquals("http://domain.example.com/video/standard/test.mp4?auth_key=1444435200-0-0-23bf85053008f5c0e791667a313e28ce", url)
    }

    /** https://help.aliyun.com/zh/cdn/user-guide/type-b-signing （时间 201508150800 为 UTC+8 = 1439596800） */
    @Test
    fun aliyunTypeBMatchesOfficialExample() {
        val url = CdnSigners.sign(CdnStrategy.ALIYUN_B, "http://domain.example.com", "/4/44/44c0909bcfc20a01afaf256ca99a8b8b.mp3", null, 1439596800L, aliyunKey, 1)
        assertEquals("http://domain.example.com/201508150800/9044548ef1527deadafa49a890a377f0/4/44/44c0909bcfc20a01afaf256ca99a8b8b.mp3", url)
    }

    /** https://help.aliyun.com/zh/cdn/user-guide/type-c-signing （路径形式；十六进制时间大写 55CE8100） */
    @Test
    fun aliyunTypeCMatchesOfficialExample() {
        val url = CdnSigners.sign(CdnStrategy.ALIYUN_C, "http://domain.example.com", "/test.flv", null, 1439596800L, aliyunKey, 1)
        assertEquals("http://domain.example.com/a37fa50a5fb8f71214b1e7c95ec7a1bd/55CE8100/test.flv", url)
    }

    // ---------------------------------------------------------------- 通用 HMAC

    /** 向量由独立实现算出：python3 `hmac.new(b"k"*32, b"/a/b.png?imageMogr2/thumbnail/180x\n1700003600", "sha256")`。 */
    @Test
    fun hmacCoversPathAndProcessingParams() {
        val key = "k".repeat(32)
        val url = CdnSigners.sign(CdnStrategy.HMAC, "https://img.example.com", "/a/b.png", "imageMogr2/thumbnail/180x", 1_700_000_000L, key, 3600)
        assertEquals(
            "https://img.example.com/a/b.png?imageMogr2/thumbnail/180x&exp=1700003600&sig=6fd79c934f07397e0bcb208123ef0de6328a63f4bde2d4fca50b449e11368a3e",
            url,
        )
        assertNotEquals(
            CdnSigners.hmacSignature(key, "/a/b.png?imageMogr2/thumbnail/180x", 1_700_003_600),
            CdnSigners.hmacSignature(key, "/a/b.png?imageMogr2/thumbnail/360x", 1_700_003_600),
            "改处理参数签名必须变",
        )
    }

    @Test
    fun vendorSignaturesKeepProcessingParamsOutsideTheSignedPath() {
        val plain = CdnSigners.sign(CdnStrategy.TENCENT_A, "https://c.example", "/x.jpg", null, 100, "abcdef", 1)
        val sized = CdnSigners.sign(CdnStrategy.TENCENT_A, "https://c.example", "/x.jpg", "imageMogr2/thumbnail/180x", 100, "abcdef", 1)
        assertEquals("https://c.example/x.jpg?imageMogr2/thumbnail/180x&" + plain.substringAfter('?'), sized)
        val b = CdnSigners.sign(CdnStrategy.ALIYUN_B, "https://c.example", "/x.jpg", "x-oss-process=image/resize,w_180", 1439596800, "abcdef", 1)
        assertTrue(b.endsWith("/x.jpg?x-oss-process=image/resize,w_180"))
    }

    // ---------------------------------------------------------------- 时间桶确定性

    private fun urls(strategy: CdnStrategy, now: () -> Long, params: ImageParamsDialect = ImageParamsDialect.TENCENT) = MediaCdnUrls(
        MediaCdnConfig(strategy, "https://img.example.com", if (strategy == CdnStrategy.HMAC) "h".repeat(32) else "abcdef123", 1800, 1800, params),
        now,
    )

    @Test
    fun sameBucketSameUrlForEveryone() {
        for (s in CdnStrategy.entries.filter { it.signed }) {
            var clock = 1_700_001_000L // 桶 [1_699_999_200, 1_700_001_000) 之后的第一个桶起点
            val cdn = urls(s, { clock })
            val first = cdn.urlFor("app/content-images/ab/cd/abcd.png", MediaVariant.W360)
            clock += 1799
            assertEquals(first, cdn.urlFor("app/content-images/ab/cd/abcd.png", MediaVariant.W360), "${s.code}: 桶内逐字节相同")
            clock += 1
            assertNotEquals(first.url, cdn.urlFor("app/content-images/ab/cd/abcd.png", MediaVariant.W360).url, "${s.code}: 下一个桶换签名")
        }
    }

    @Test
    fun validityIsBucketPlusGraceFromBucketStart() {
        val cdn = urls(CdnStrategy.TENCENT_D, { 1_700_001_500L })
        val u = cdn.urlFor("p/x.png")
        assertEquals(1_700_001_000L, cdn.bucketStart())
        assertEquals(1_700_001_000L + 3600, u.expiresAt)
        assertTrue(u.url.endsWith("&t=1700001000"))
        assertEquals(3600L, cdn.policy(600).validitySeconds)
        assertEquals(1800L, cdn.policy(600).bucketSeconds)
    }

    @Test
    fun publicStrategyIsStableAndNeverExpires() {
        val cdn = urls(CdnStrategy.PUBLIC, { 1L })
        val u = cdn.urlFor("app/content-images/ab/cd/abcd.png")
        assertEquals("https://img.example.com/app/content-images/ab/cd/abcd.png", u.url)
        assertNull(u.expiresAt)
        assertEquals("https://img.example.com/app/content-images/ab/cd/abcd.png?imageMogr2/thumbnail/720x", cdn.urlFor("app/content-images/ab/cd/abcd.png", MediaVariant.W720).url)
        assertNull(cdn.policy(600).validitySeconds)
        assertNull(cdn.policy(600).bucketSeconds)
    }

    @Test
    fun noneStrategyReportsRelayTtl() {
        val p = MediaCdnUrls.disabled().policy(600)
        assertEquals("none", p.strategy)
        assertEquals(600L, p.validitySeconds)
    }

    // ---------------------------------------------------------------- 规格

    @Test
    fun variantLadderSkipsUpscalingAndNonImages() {
        val cdn = urls(CdnStrategy.PUBLIC, { 1L })
        val v = cdn.variants("p/x.jpg", "image/jpeg", 800)
        assertEquals(listOf("w180", "w360", "w720", "og"), v.keys.toList())
        assertEquals("https://img.example.com/p/x.jpg?imageMogr2/thumbnail/1200x675r/gravity/center/crop/1200x675", v["og"])
        assertEquals(MediaVariant.LADDER.map { it.key }, cdn.variants("p/x.jpg", "image/jpeg", null).keys.toList(), "宽度未知时全给")
        assertTrue(cdn.variants("p/x.svg", "image/svg+xml", 800).isEmpty())
        assertTrue(cdn.variants("p/x.mp4", "video/mp4", 800).isEmpty())
        assertTrue(urls(CdnStrategy.PUBLIC, { 1L }, ImageParamsDialect.NONE).variants("p/x.jpg", "image/jpeg", 800).isEmpty())
    }

    @Test
    fun aliyunDialect() {
        val cdn = urls(CdnStrategy.PUBLIC, { 1L }, ImageParamsDialect.ALIYUN)
        assertEquals("https://img.example.com/p/x.jpg?x-oss-process=image/resize,w_180", cdn.urlFor("p/x.jpg", MediaVariant.W180).url)
        assertEquals("https://img.example.com/p/x.jpg?x-oss-process=image/resize,m_fill,w_1200,h_675", cdn.urlFor("p/x.jpg", MediaVariant.OG).url)
    }

    @Test
    fun pathIsPercentEncodedBeforeSigning() {
        assertEquals("/app/%E5%9B%BE/a%20b.png", MediaCdnUrls.encodePath("app/图/a b.png"))
        assertEquals("/a/b.png", MediaCdnUrls.encodePath("/a/b.png"))
    }

    // ---------------------------------------------------------------- 配置

    private val env = mapOf("CDN_KEY" to "abcdef123", "HMAC_KEY" to "z".repeat(32))

    private fun parse(vararg kv: Pair<String, Any?>) = MediaCdnConfig.parse(mapOf(*kv)) { env[it] }

    @Test
    fun configParsing() {
        assertEquals(CdnStrategy.NONE, MediaCdnConfig.parse(null) { null }.strategy)
        assertEquals(CdnStrategy.NONE, parse("strategy" to "none").strategy)
        val c = parse("strategy" to "tencent-a", "base_url" to "https://img.example.com/", "key_env" to "CDN_KEY", "bucket_seconds" to 600, "grace_seconds" to "300", "image_params" to "tencent")
        assertEquals("https://img.example.com", c.baseUrl)
        assertEquals(900L, c.validitySeconds)
        assertEquals(ImageParamsDialect.TENCENT, c.imageParams)
        assertTrue("abcdef123" !in c.toString(), "密钥不进日志")
        assertEquals("", parse("strategy" to "public", "base_url" to "http://127.0.0.1:18300").key)
    }

    @Test
    fun configRejectsUnsafeOrIncompleteSettings() {
        assertFailsWith<MediaCdnConfigException> { parse("strategy" to "akamai") }
        assertFailsWith<MediaCdnConfigException> { parse("strategy" to "public") }
        assertFailsWith<MediaCdnConfigException> { parse("strategy" to "public", "base_url" to "ftp://x.example") }
        assertFailsWith<MediaCdnConfigException> { parse("strategy" to "public", "base_url" to "https://x.example/img") }
        assertFailsWith<MediaCdnConfigException> { parse("strategy" to "tencent-a", "base_url" to "https://x.example") }
        assertFailsWith<MediaCdnConfigException> { parse("strategy" to "tencent-a", "base_url" to "https://x.example", "key_env" to "MISSING") }
        assertFailsWith<MediaCdnConfigException> { parse("strategy" to "hmac", "base_url" to "https://x.example", "key_env" to "CDN_KEY") }
        assertEquals(CdnStrategy.HMAC, parse("strategy" to "hmac", "base_url" to "https://x.example", "key_env" to "HMAC_KEY").strategy)
        assertFailsWith<MediaCdnConfigException> { parse("strategy" to "aliyun-b", "base_url" to "https://x.example", "key_env" to "CDN_KEY", "bucket_seconds" to 90) }
        assertFailsWith<MediaCdnConfigException> { parse("strategy" to "public", "base_url" to "https://x.example", "image_params" to "imgix") }
        assertFailsWith<MediaCdnConfigException> { parse("strategy" to "public", "base_url" to "https://x.example", "bucket_seconds" to 0) }
    }
}
