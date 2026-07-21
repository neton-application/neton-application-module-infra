package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 锁定 [FileUploadLogic.storageKeyOf] 的物理路径规则
 * （spec MODULE_MEMBER_PROFILE_SPEC §4.2）：
 *
 * ```
 * {businessType}/{sha256[0..2)}/{sha256[2..4)}/{sha256}.{ext}
 * ```
 *
 * 重要不变量（写在测试里防回归）：
 * - **不带 ownerUid**：内容寻址，多 owner 同内容共享同一物理对象
 * - 两层 sha256 前缀分片，防单目录倾斜
 * - 完整 sha256 是文件名的一部分，无碰撞风险
 */
class FileUploadLogicStorageKeyTest {

    @Test
    fun member_avatar_png_shards_by_sha256_prefix() {
        val key = FileUploadLogic.storageKeyOf(
            businessType = "member_avatar",
            sha256Hex = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            ext = "png",
        )
        assertEquals(
            "member_avatar/ab/cd/abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890.png",
            key,
        )
    }

    @Test
    fun group_avatar_webp_uses_first_two_bytes_as_shards() {
        val key = FileUploadLogic.storageKeyOf(
            businessType = "group_avatar",
            sha256Hex = "0123456789abcdef" + "0".repeat(48),
            ext = "webp",
        )
        // 前 2 hex = "01"，第 3-4 hex = "23"
        assertEquals(
            "group_avatar/01/23/0123456789abcdef000000000000000000000000000000000000000000000000.webp",
            key,
        )
    }

    @Test
    fun owner_uid_is_not_in_storage_key_even_implicitly() {
        // Storage key 函数签名里就没有 ownerUid。这里固定一个具体输出，
        // 防止有人加回 ownerUid 重载或修改算法时偷偷把 uid 拼进路径。
        val key = FileUploadLogic.storageKeyOf(
            businessType = "kyc_document",
            sha256Hex = "deadbeef" + "0".repeat(56),
            ext = "pdf",
        )
        assertEquals(
            "kyc_document/de/ad/deadbeef00000000000000000000000000000000000000000000000000000000.pdf",
            key,
        )
        // 任何形如 owner_uid 的数字段都不能出现在 path
        val pathSegments = key.split("/")
        for (seg in pathSegments) {
            // shard / sha256 都是 hex；目录段不应纯数字看起来像 uid
            // （sha256 第 1-2 / 3-4 偶发为纯数字是允许的，所以只断言"不出现纯长数字段"）
            check(seg.length != 18) { "suspicious uid-like segment: $seg" } // privchat uid 现 17~18 位
        }
    }

    @Test
    fun extension_is_preserved_verbatim() {
        val key = FileUploadLogic.storageKeyOf(
            businessType = "feedback_attachment",
            sha256Hex = "0".repeat(64),
            ext = "txt",
        )
        assertEquals("feedback_attachment/00/00/${"0".repeat(64)}.txt", key)
    }

    @Test
    fun rejects_too_short_sha256() {
        assertFailsWith<IllegalArgumentException> {
            FileUploadLogic.storageKeyOf(
                businessType = "member_avatar",
                sha256Hex = "abc",
                ext = "png",
            )
        }
    }
}
