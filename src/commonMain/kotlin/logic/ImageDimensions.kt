package logic

/**
 * 从文件头读出图片的像素宽高（infra-media §7.3 / 缺口 G7：网页端按宽高占位，避免布局偏移）。
 *
 * 只读文件头，不解码像素：PNG（IHDR）、GIF（逻辑屏幕描述符）、JPEG（扫描到 SOF 段；EXIF 方向 5–8
 * 交换宽高——浏览器缺省按 EXIF 方向显示）、WebP（VP8 / VP8L / VP8X）、AVIF / HEIF（`meta/iprp/ipco/ispe`）。
 * 认不出或数据截断时返回 null，调用方照常落库，只是没有宽高。
 */
object ImageDimensions {

    data class Size(val width: Int, val height: Int)

    /** 宽高上限：超出视为数据损坏（防溢出与恶意头）。 */
    private const val MAX_SIDE = 65_535 * 4

    fun read(bytes: ByteArray): Size? = try {
        when {
            isPng(bytes) -> png(bytes)
            isGif(bytes) -> gif(bytes)
            isJpeg(bytes) -> jpeg(bytes)
            isWebp(bytes) -> webp(bytes)
            isIsoBmff(bytes) -> isoBmff(bytes)
            else -> null
        }?.takeIf { it.width in 1..MAX_SIDE && it.height in 1..MAX_SIDE }
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    // ---- 工具 ----

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xff
    private fun be16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun le16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun be32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    private fun ascii(b: ByteArray, i: Int, s: String): Boolean =
        i + s.length <= b.size && s.indices.all { u8(b, i + it) == s[it].code }

    // ---- PNG ----

    private fun isPng(b: ByteArray) = b.size >= 24 && u8(b, 0) == 0x89 && ascii(b, 1, "PNG") && ascii(b, 12, "IHDR")
    private fun png(b: ByteArray) = Size(be32(b, 16).toInt(), be32(b, 20).toInt())

    // ---- GIF ----

    private fun isGif(b: ByteArray) = b.size >= 10 && (ascii(b, 0, "GIF87a") || ascii(b, 0, "GIF89a"))
    private fun gif(b: ByteArray) = Size(le16(b, 6), le16(b, 8))

    // ---- JPEG ----

    private fun isJpeg(b: ByteArray) = b.size >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8

    /** SOF0–SOF15，除去 DHT(C4)、JPG(C8)、DAC(CC)。 */
    private fun isSof(marker: Int) = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    private fun jpeg(b: ByteArray): Size? {
        var i = 2
        var orientation = 1
        while (i + 4 <= b.size) {
            if (u8(b, i) != 0xFF) return null
            val marker = u8(b, i + 1)
            when {
                marker == 0xFF -> { i++; continue }                       // 填充字节
                marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> { i += 2; continue }  // 无长度的标记
                marker == 0xD9 || marker == 0xDA -> return null           // 到了 EOI / SOS 还没有 SOF
            }
            val len = be16(b, i + 2)
            if (len < 2) return null
            val seg = i + 4
            if (marker == 0xE1 && len >= 8 && ascii(b, seg, "Exif")) {
                orientation = exifOrientation(b, seg + 6, minOf(b.size, i + 2 + len)) ?: orientation
            }
            if (isSof(marker)) {
                val h = be16(b, seg + 1)
                val w = be16(b, seg + 3)
                return if (orientation in 5..8) Size(h, w) else Size(w, h)
            }
            i += 2 + len
        }
        return null
    }

    /** TIFF 头里 IFD0 的 Orientation（0x0112）。 */
    private fun exifOrientation(b: ByteArray, tiff: Int, end: Int): Int? {
        if (tiff + 8 > end) return null
        val little = ascii(b, tiff, "II")
        if (!little && !ascii(b, tiff, "MM")) return null
        fun r16(i: Int) = if (little) le16(b, i) else be16(b, i)
        fun r32(i: Int): Long = if (little) {
            u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
        } else be32(b, i)
        val ifd = tiff + r32(tiff + 4).toInt()
        if (ifd + 2 > end) return null
        val count = r16(ifd)
        for (n in 0 until count) {
            val e = ifd + 2 + n * 12
            if (e + 12 > end) return null
            if (r16(e) == 0x0112) return r16(e + 8)
        }
        return null
    }

    // ---- WebP ----

    private fun isWebp(b: ByteArray) = b.size >= 30 && ascii(b, 0, "RIFF") && ascii(b, 8, "WEBP")

    private fun webp(b: ByteArray): Size? = when {
        // 有损：帧头 3 字节 + 起始码 9D 01 2A，之后 14 位宽高（高 2 位是缩放）
        ascii(b, 12, "VP8 ") -> if (u8(b, 23) == 0x9D && u8(b, 24) == 0x01 && u8(b, 25) == 0x2A) {
            Size(le16(b, 26) and 0x3FFF, le16(b, 28) and 0x3FFF)
        } else null
        // 无损：签名 0x2F，之后 14 位 (宽-1)、14 位 (高-1)
        ascii(b, 12, "VP8L") -> if (u8(b, 20) == 0x2F) {
            val bits = u8(b, 21).toLong() or (u8(b, 22).toLong() shl 8) or (u8(b, 23).toLong() shl 16) or (u8(b, 24).toLong() shl 24)
            Size(((bits and 0x3FFF) + 1).toInt(), (((bits shr 14) and 0x3FFF) + 1).toInt())
        } else null
        // 扩展：24 位 (画布宽-1)、24 位 (画布高-1)
        ascii(b, 12, "VP8X") -> Size(le24(b, 24) + 1, le24(b, 27) + 1)
        else -> null
    }

    // ---- AVIF / HEIF（ISO BMFF）----

    private fun isIsoBmff(b: ByteArray) = b.size >= 16 && ascii(b, 4, "ftyp")

    private fun isoBmff(b: ByteArray): Size? {
        val meta = findBox(b, 0, b.size, "meta") ?: return null
        // meta 是 FullBox：先跳过 4 字节 version/flags
        val iprp = findBox(b, meta.first + 4, meta.second, "iprp") ?: return null
        val ipco = findBox(b, iprp.first, iprp.second, "ipco") ?: return null
        val ispe = findBox(b, ipco.first, ipco.second, "ispe") ?: return null
        // ispe 也是 FullBox：version/flags 之后 32 位宽、32 位高
        val p = ispe.first + 4
        if (p + 8 > ispe.second) return null
        return Size(be32(b, p).toInt(), be32(b, p + 4).toInt())
    }

    /** 在 [start, end) 里找第一个类型为 [type] 的盒子，返回其载荷区间。 */
    private fun findBox(b: ByteArray, start: Int, end: Int, type: String): Pair<Int, Int>? {
        var i = start
        while (i + 8 <= end) {
            var size = be32(b, i)
            var header = 8
            if (size == 1L) {
                if (i + 16 > end) return null
                size = (be32(b, i + 8) shl 32) or be32(b, i + 12)
                header = 16
            } else if (size == 0L) {
                size = (end - i).toLong()
            }
            if (size < header || i + size > end) return null
            if (ascii(b, i + 4, type)) return (i + header) to (i + size.toInt())
            i += size.toInt()
        }
        return null
    }
}
