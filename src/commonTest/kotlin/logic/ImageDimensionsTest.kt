package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 文件头宽高读取（infra-media §7.3 / G7）：每种格式按规范手工构造最小文件头。 */
class ImageDimensionsTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.encodeToByteArray()
    private fun be32(v: Int) = bytes(v ushr 24 and 0xff, v ushr 16 and 0xff, v ushr 8 and 0xff, v and 0xff)
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + ascii(type) + payload

    @Test
    fun png() {
        val b = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + ascii("IHDR") + be32(1920) + be32(1080) + bytes(8, 6, 0, 0, 0)
        assertEquals(ImageDimensions.Size(1920, 1080), ImageDimensions.read(b))
    }

    @Test
    fun gif() {
        assertEquals(ImageDimensions.Size(320, 200), ImageDimensions.read(ascii("GIF89a") + bytes(0x40, 0x01, 0xC8, 0x00, 0, 0, 0)))
    }

    private fun sof0(w: Int, h: Int) = bytes(0xFF, 0xC0, 0x00, 0x11, 0x08, h shr 8, h and 0xff, w shr 8, w and 0xff, 3) + ByteArray(9)

    @Test
    fun jpegScansPastOtherSegmentsToSof() {
        val app0 = bytes(0xFF, 0xE0, 0x00, 0x10) + ascii("JFIF") + ByteArray(10)
        val dqt = bytes(0xFF, 0xDB, 0x00, 0x04, 0x00, 0x00)
        val b = bytes(0xFF, 0xD8) + app0 + dqt + sof0(800, 600) + bytes(0xFF, 0xDA)
        assertEquals(ImageDimensions.Size(800, 600), ImageDimensions.read(b))
        // 渐进式 SOF2
        val sof2 = sof0(640, 480).also { it[1] = 0xC2.toByte() }
        assertEquals(ImageDimensions.Size(640, 480), ImageDimensions.read(bytes(0xFF, 0xD8) + sof2))
        // DHT（C4）不是 SOF
        val dht = bytes(0xFF, 0xC4, 0x00, 0x04, 0, 0)
        assertEquals(ImageDimensions.Size(10, 20), ImageDimensions.read(bytes(0xFF, 0xD8) + dht + sof0(10, 20)))
    }

    @Test
    fun jpegExifOrientationSwapsSides() {
        // TIFF（大端）：IFD0 一项 Orientation = 6（顺时针 90°）
        val tiff = ascii("MM") + bytes(0x00, 0x2A) + be32(8) + bytes(0x00, 0x01) +
            bytes(0x01, 0x12, 0x00, 0x03) + be32(1) + bytes(0x00, 0x06, 0x00, 0x00) + be32(0)
        val payload = ascii("Exif") + bytes(0, 0) + tiff
        val app1 = bytes(0xFF, 0xE1, (payload.size + 2) shr 8, (payload.size + 2) and 0xff) + payload
        assertEquals(ImageDimensions.Size(3000, 4000), ImageDimensions.read(bytes(0xFF, 0xD8) + app1 + sof0(4000, 3000)))
    }

    @Test
    fun jpegWithoutSofIsUnknown() {
        assertNull(ImageDimensions.read(bytes(0xFF, 0xD8, 0xFF, 0xDA, 0, 2)))
        assertNull(ImageDimensions.read(bytes(0xFF, 0xD8, 0xFF, 0xC0, 0x00)), "截断")
    }

    private fun riff(chunk: String, body: ByteArray) = ascii("RIFF") + be32(0) + ascii("WEBP") + ascii(chunk) + be32(body.size) + body

    @Test
    fun webpLossyLosslessAndExtended() {
        // VP8：3 字节帧头 + 9D 01 2A + 14 位宽高（小端）
        val vp8 = bytes(0, 0, 0, 0x9D, 0x01, 0x2A, 0x90, 0x01, 0x2C, 0x01) + ByteArray(4)
        assertEquals(ImageDimensions.Size(400, 300), ImageDimensions.read(riff("VP8 ", vp8)))
        // VP8L：0x2F + (宽-1) 14 位 | (高-1) 14 位
        val w = 400 - 1; val h = 300 - 1
        val bits = w or (h shl 14)
        val vp8l = bytes(0x2F, bits and 0xff, bits shr 8 and 0xff, bits shr 16 and 0xff, bits shr 24 and 0xff) + ByteArray(5)
        assertEquals(ImageDimensions.Size(400, 300), ImageDimensions.read(riff("VP8L", vp8l)))
        // VP8X：4 字节标志 + 24 位 (宽-1) + 24 位 (高-1)
        val vp8x = bytes(0x10, 0, 0, 0, 0xFF, 0x0E, 0x00, 0x37, 0x04, 0x00)
        assertEquals(ImageDimensions.Size(3840, 1080), ImageDimensions.read(riff("VP8X", vp8x)))
    }

    @Test
    fun avifIspe() {
        val ispe = box("ispe", ByteArray(4) + be32(1280) + be32(720))
        val meta = box("meta", ByteArray(4) + box("hdlr", ByteArray(12)) + box("iprp", box("ipco", box("pixi", ByteArray(5)) + ispe)))
        val b = box("ftyp", ascii("avif") + ByteArray(4) + ascii("avifmif1")) + meta
        assertEquals(ImageDimensions.Size(1280, 720), ImageDimensions.read(b))
    }

    @Test
    fun unknownAndGarbage() {
        assertNull(ImageDimensions.read(ByteArray(0)))
        assertNull(ImageDimensions.read(ascii("hello world, not an image")))
        assertNull(ImageDimensions.read(bytes(0x89, 0x50, 0x4E, 0x47)), "截断的 PNG")
    }
}
