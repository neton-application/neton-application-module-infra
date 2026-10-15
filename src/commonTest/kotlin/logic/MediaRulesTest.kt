package logic

import port.MediaErrorCode
import port.MediaLifecycle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 业务类型登记表与 attach / detach / 读取 / 清理的纯规则（infra-media §3 / §6 / §7）。 */
class MediaRulesTest {

    private fun managed(
        code: String = "content.image",
        access: MediaAccess = MediaAccess.DELEGATED,
        reusable: Boolean = false,
        usages: Set<String> = setOf("content.image", "content.cover"),
    ) = FileBusinessPolicy(
        businessType = code,
        access = access,
        allowedMimeTypes = setOf("image/png"),
        maxSizeBytes = 1024,
        usages = usages,
        reusable = reusable,
        managedLifecycle = true,
        assetDir = "content-images",
    )

    private fun file(
        policy: FileBusinessPolicy?,
        lifecycle: MediaLifecycle = MediaLifecycle.TEMPORARY,
        status: Int = 1,
        uploader: Long = 7,
    ) = MediaRules.FileSnapshot(status, lifecycle, uploader, policy)

    private fun decide(
        f: MediaRules.FileSnapshot?,
        refs: List<MediaRules.Ref> = emptyList(),
        ownerId: String = "1",
        usage: String = "content.image",
        actor: Long? = 7,
    ) = MediaRules.decideAttach(f, refs, "content.item", ownerId, usage, actor)

    private fun assertRejected(code: MediaErrorCode, d: MediaRules.AttachDecision) {
        val r = assertIs<MediaRules.AttachDecision.Reject>(d)
        assertEquals(code, r.code)
    }

    // ---- 登记表 ----

    @Test
    fun defaultRegistryKeepsBuiltinTypesUnmanaged() {
        val reg = FileBusinessPolicyRegistry.default()
        assertEquals(
            setOf("member_avatar", "group_avatar", "feedback_attachment", "kyc_document", "generic_image"),
            reg.businessTypes(),
        )
        reg.all().forEach { assertFalse(it.managedLifecycle, "${it.businessType} 不能进生命周期") }
        // 旧可见性语义不变
        assertEquals(FileVisibility.PUBLIC, reg.policyOrThrow("member_avatar").visibility)
        assertEquals(FileVisibility.PUBLIC, reg.policyOrThrow("generic_image").visibility)
        assertEquals(FileVisibility.PRIVATE, reg.policyOrThrow("feedback_attachment").visibility)
        assertEquals(MediaAccess.OWNER, reg.policyOrThrow("kyc_document").access)
        // 默认用途 = 自身 code
        assertEquals(setOf("member_avatar"), reg.policyOrThrow("member_avatar").effectiveUsages)
    }

    @Test
    fun registerAddsNewTypeAndRejectsDuplicates() {
        val reg = FileBusinessPolicyRegistry.default()
        reg.register(managed())
        assertTrue("content.image" in reg.businessTypes())
        assertFailsWith<IllegalArgumentException> { reg.register(managed()) }
        assertFailsWith<IllegalArgumentException> {
            reg.register(FileBusinessPolicy("member_avatar", MediaAccess.PUBLIC, setOf("image/png"), 1))
        }
    }

    @Test
    fun registerRequiresAssetDirForNewTypes() {
        val reg = FileBusinessPolicyRegistry()
        assertFailsWith<IllegalStateException> {
            reg.register(FileBusinessPolicy("comment.image", MediaAccess.PUBLIC, setOf("image/png"), 1))
        }
        assertFailsWith<IllegalArgumentException> { reg.register(managed().copy(assetDir = "Bad/Dir")) }
        assertFailsWith<IllegalArgumentException> { reg.register(managed().copy(maxSizeBytes = 0)) }
        assertFailsWith<IllegalArgumentException> { reg.register(managed().copy(businessType = "bad code")) }
    }

    @Test
    fun storageKeyUsesDeclaredAssetDir() {
        val sha = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        assertEquals("platform/content-images/ab/cd/$sha.png", FileUploadLogic.storageKeyOf(managed(), sha, "png"))
        assertEquals(
            FileUploadLogic.storageKeyOf("member_avatar", sha, "png"),
            FileUploadLogic.storageKeyOf(FileBusinessPolicyRegistry.default().policyOrThrow("member_avatar"), sha, "png"),
        )
    }

    // ---- attach ----

    @Test
    fun attachRejectsMissingDeletedAndUnknown() {
        assertRejected(MediaErrorCode.FILE_NOT_FOUND, decide(null))
        assertRejected(MediaErrorCode.FILE_DELETED, decide(file(managed(), MediaLifecycle.DELETED, status = 0)))
        assertRejected(MediaErrorCode.FILE_DELETED, decide(file(managed(), MediaLifecycle.DELETED)))
        assertRejected(MediaErrorCode.FILE_DELETED, decide(file(managed(), MediaLifecycle.TEMPORARY, status = 0)))
        assertRejected(MediaErrorCode.UNKNOWN_BUSINESS_TYPE, decide(file(null)))
    }

    @Test
    fun attachRequiresAllowedUsage() {
        assertRejected(MediaErrorCode.USAGE_NOT_ALLOWED, decide(file(managed()), usage = "product.gallery"))
        assertIs<MediaRules.AttachDecision.Attach>(decide(file(managed()), usage = "content.cover"))
    }

    @Test
    fun temporaryFileOnlyByUploaderOrSystem() {
        assertIs<MediaRules.AttachDecision.Attach>(decide(file(managed()), actor = 7))
        assertIs<MediaRules.AttachDecision.Attach>(decide(file(managed()), actor = null))
        assertRejected(MediaErrorCode.NOT_FILE_OWNER, decide(file(managed()), actor = 8))
    }

    @Test
    fun sameReferenceIsIdempotent() {
        val refs = listOf(MediaRules.Ref("content.item", "1", "content.image"))
        assertIs<MediaRules.AttachDecision.AlreadyAttached>(decide(file(managed(), MediaLifecycle.ATTACHED), refs, actor = 8))
    }

    @Test
    fun reuseByAnotherOwnerNeedsReusable() {
        val refs = listOf(MediaRules.Ref("content.item", "1", "content.image"))
        val attached = file(managed(), MediaLifecycle.ATTACHED)
        assertRejected(MediaErrorCode.NOT_REUSABLE, decide(attached, refs, ownerId = "2"))
        // 同一对象换个用途不算复用
        assertIs<MediaRules.AttachDecision.Attach>(decide(attached, refs, ownerId = "1", usage = "content.cover"))

        val reusable = file(managed(reusable = true), MediaLifecycle.ATTACHED)
        assertIs<MediaRules.AttachDecision.Attach>(decide(reusable, refs, ownerId = "2", actor = 7))
        assertIs<MediaRules.AttachDecision.Attach>(decide(reusable, refs, ownerId = "2", actor = null))
        // 非上传者复用 DELEGATED：要能看到现有引用对象
        assertIs<MediaRules.AttachDecision.AttachIfActorCanViewExistingOwner>(decide(reusable, refs, ownerId = "2", actor = 9))
    }

    @Test
    fun reuseReadCheckFollowsAccess() {
        val refs = listOf(MediaRules.Ref("content.item", "1", "content.image"))
        val pub = file(managed(access = MediaAccess.PUBLIC, reusable = true), MediaLifecycle.ATTACHED)
        assertIs<MediaRules.AttachDecision.Attach>(decide(pub, refs, ownerId = "2", actor = 9))
        val own = file(managed(access = MediaAccess.OWNER, reusable = true), MediaLifecycle.ATTACHED)
        assertRejected(MediaErrorCode.NOT_FILE_OWNER, decide(own, refs, ownerId = "2", actor = 9))
    }

    @Test
    fun orphanedFileByNonUploaderIsReuse() {
        val orphan = file(managed(), MediaLifecycle.ORPHANED)
        assertIs<MediaRules.AttachDecision.Attach>(decide(orphan, actor = 7))
        assertRejected(MediaErrorCode.NOT_REUSABLE, decide(orphan, actor = 9))
    }

    @Test
    fun lifecycleTransitions() {
        assertEquals(MediaLifecycle.ATTACHED, MediaRules.lifecycleAfterAttach(MediaLifecycle.TEMPORARY))
        assertEquals(MediaLifecycle.ATTACHED, MediaRules.lifecycleAfterAttach(MediaLifecycle.ORPHANED))
        assertEquals(MediaLifecycle.ATTACHED, MediaRules.lifecycleAfterAttach(MediaLifecycle.ATTACHED))

        assertEquals(MediaLifecycle.ORPHANED, MediaRules.lifecycleAfterDetach(managed(), MediaLifecycle.ATTACHED, 0))
        assertEquals(MediaLifecycle.ATTACHED, MediaRules.lifecycleAfterDetach(managed(), MediaLifecycle.ATTACHED, 1))
        // 非托管类型永远不变 orphaned
        val builtin = FileBusinessPolicyRegistry.default().policyOrThrow("generic_image")
        assertEquals(MediaLifecycle.ATTACHED, MediaRules.lifecycleAfterDetach(builtin, MediaLifecycle.ATTACHED, 0))
    }

    // ---- 清理判断 ----

    @Test
    fun expiryRespectsGraceRefsAndManagement() {
        val p = managed()
        val now = 100 * MediaRules.DAY_MS
        val tempAt = now - 25 * MediaRules.HOUR_MS
        assertTrue(MediaRules.isExpired(p, 1, MediaLifecycle.TEMPORARY, tempAt, 0, now))
        assertFalse(MediaRules.isExpired(p, 1, MediaLifecycle.TEMPORARY, now - 23 * MediaRules.HOUR_MS, 0, now))
        assertFalse(MediaRules.isExpired(p, 1, MediaLifecycle.TEMPORARY, tempAt, 1, now), "有引用不清理")
        assertFalse(MediaRules.isExpired(p, 1, MediaLifecycle.ATTACHED, 0, 0, now))
        assertFalse(MediaRules.isExpired(p, 0, MediaLifecycle.TEMPORARY, tempAt, 0, now))
        assertTrue(MediaRules.isExpired(p, 1, MediaLifecycle.ORPHANED, now - 8 * MediaRules.DAY_MS, 0, now))
        assertFalse(MediaRules.isExpired(p, 1, MediaLifecycle.ORPHANED, now - 6 * MediaRules.DAY_MS, 0, now))
        val builtin = FileBusinessPolicyRegistry.default().policyOrThrow("generic_image")
        assertFalse(MediaRules.isExpired(builtin, 1, MediaLifecycle.TEMPORARY, 0, 0, now), "内置类型永不清理")
    }

    // ---- 读取 ----

    @Test
    fun readRules() {
        val reg = FileBusinessPolicyRegistry.default()
        val pub = reg.policyOrThrow("member_avatar")
        val own = reg.policyOrThrow("kyc_document")
        val del = managed()
        val a = MediaLifecycle.ATTACHED
        assertEquals(true, MediaRules.readWithoutReferences(pub, 1, a, 7, null))
        assertEquals(false, MediaRules.readWithoutReferences(pub, 0, a, 7, 7))
        assertEquals(true, MediaRules.readWithoutReferences(own, 1, a, 7, 7))
        assertEquals(false, MediaRules.readWithoutReferences(own, 1, a, 7, 8))
        assertEquals(false, MediaRules.readWithoutReferences(own, 1, a, 7, null))
        assertEquals(true, MediaRules.readWithoutReferences(del, 1, a, 7, 7))
        assertNull(MediaRules.readWithoutReferences(del, 1, a, 7, 8), "非上传者按引用判断")
        assertEquals(false, MediaRules.readWithoutReferences(del, 1, MediaLifecycle.TEMPORARY, 7, 8))
        assertEquals(false, MediaRules.readWithoutReferences(del, 1, MediaLifecycle.DELETED, 7, 7))
        assertEquals(false, MediaRules.readWithoutReferences(null, 1, a, 7, 7))
    }
}
