package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 锁定 [FileUploadLogic.storageKeyOf] 的物理路径规则
 * （spec MODULE_MEMBER_PROFILE_SPEC §4.2bis）：
 *
 * ```
 * platform/{assetDir}/{sha256[0..2)}/{sha256[2..4)}/{sha256}.{ext}
 * ```
 *
 * 重要不变量（写在测试里防回归）：
 * - **系统前缀 `platform/`**：与 privchat-server 的 `chat/` 分开，两套系统的内部
 *   分目录规则不会相撞
 * - **目录名 ≠ businessType**：businessType 是可改名的业务分类，路径写进库就不能动
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
            "platform/avatars/ab/cd/abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890.png",
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
            "platform/group-avatars/01/23/0123456789abcdef000000000000000000000000000000000000000000000000.webp",
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
            "platform/kyc/de/ad/deadbeef00000000000000000000000000000000000000000000000000000000.pdf",
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
        assertEquals("platform/feedback/00/00/${"0".repeat(64)}.txt", key)
    }

    /**
     * 用户给的路径样例（2026-09-09）：`platform/avatars/32/9e/329e…jpg`。
     * 这条是「照着需求原样钉一遍」，别的用例改坏了它也会跟着红。
     */
    @Test
    fun matches_the_agreed_avatar_layout() {
        val sha = "329e612ae747790422b0a2cb9f18b3c9" + "0".repeat(32)
        val key = FileUploadLogic.storageKeyOf(
            businessType = "member_avatar",
            sha256Hex = sha,
            ext = "jpg",
        )
        assertEquals("platform/avatars/32/9e/$sha.jpg", key)
    }

    /**
     * 新增业务类型时必须同时决定目录名。少了映射就在这里炸——而不是安静地把业务名
     * 当目录写进去，等以后业务改名时才发现路径被绑死了。
     */
    @Test
    fun an_unmapped_business_type_fails_loudly() {
        assertFailsWith<IllegalStateException> {
            FileUploadLogic.storageKeyOf(
                businessType = "brand_new_type",
                sha256Hex = "0".repeat(64),
                ext = "png",
            )
        }
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
