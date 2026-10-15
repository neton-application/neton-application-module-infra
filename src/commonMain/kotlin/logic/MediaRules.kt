package logic

import port.MediaErrorCode
import port.MediaLifecycle

/**
 * attach / detach / 读取判断的纯规则（infra-media §6.2 / §7.1），与数据库无关，便于单测。
 * [MediaLogic] 在行锁内取快照后调用这里做决定。
 */
object MediaRules {

    /** 行锁内读到的文件快照。 */
    data class FileSnapshot(
        val status: Int,
        val lifecycle: MediaLifecycle,
        val uploaderUid: Long?,
        val policy: FileBusinessPolicy?,
    )

    data class Ref(val ownerType: String, val ownerId: String, val usage: String)

    sealed interface AttachDecision {
        /** 同一引用已存在，什么都不用做。 */
        data object AlreadyAttached : AttachDecision
        data object Attach : AttachDecision
        /** 复用 DELEGATED 文件：需要操作者能看到任一现有引用对象才放行。 */
        data object AttachIfActorCanViewExistingOwner : AttachDecision
        data class Reject(val code: MediaErrorCode, val message: String) : AttachDecision
    }

    fun decideAttach(
        file: FileSnapshot?,
        refs: List<Ref>,
        ownerType: String,
        ownerId: String,
        usage: String,
        actorUserId: Long?,
    ): AttachDecision {
        if (file == null) return reject(MediaErrorCode.FILE_NOT_FOUND, "file not found")
        // 已进入清理（行已标 deleted / status 置 0）的文件拒绝关联
        if (file.status != 1 || file.lifecycle == MediaLifecycle.DELETED) {
            return reject(MediaErrorCode.FILE_DELETED, "file is deleted")
        }
        val policy = file.policy
            ?: return reject(MediaErrorCode.UNKNOWN_BUSINESS_TYPE, "file business type is not registered")
        if (usage !in policy.effectiveUsages) {
            return reject(
                MediaErrorCode.USAGE_NOT_ALLOWED,
                "business type '${policy.businessType}' cannot be used for '$usage'",
            )
        }
        if (refs.any { it.ownerType == ownerType && it.ownerId == ownerId && it.usage == usage }) {
            return AttachDecision.AlreadyAttached
        }
        val isSystem = actorUserId == null
        val isUploader = !isSystem && actorUserId == file.uploaderUid

        if (file.lifecycle == MediaLifecycle.TEMPORARY) {
            // 临时文件只能由上传者本人关联
            return if (isSystem || isUploader) AttachDecision.Attach
            else reject(MediaErrorCode.NOT_FILE_OWNER, "temporary file can only be attached by its uploader")
        }

        // 已关联 / 孤儿文件：被别的对象再引用，或由非上传者拿来用，都算复用
        val referencedByOtherOwner = refs.any { it.ownerType != ownerType || it.ownerId != ownerId }
        val reuse = referencedByOtherOwner || (!isSystem && !isUploader)
        if (!reuse) return AttachDecision.Attach
        if (!policy.reusable) {
            return reject(MediaErrorCode.NOT_REUSABLE, "business type '${policy.businessType}' is not reusable")
        }
        // 复用还要求操作者对原文件有读取权限
        if (isSystem || isUploader) return AttachDecision.Attach
        return when (policy.access) {
            MediaAccess.PUBLIC -> AttachDecision.Attach
            MediaAccess.OWNER -> reject(MediaErrorCode.NOT_FILE_OWNER, "file is readable by its uploader only")
            MediaAccess.DELEGATED -> AttachDecision.AttachIfActorCanViewExistingOwner
        }
    }

    /** attach 成功后的生命周期：temporary / orphaned 回到 attached。 */
    fun lifecycleAfterAttach(current: MediaLifecycle): MediaLifecycle =
        if (current == MediaLifecycle.TEMPORARY || current == MediaLifecycle.ORPHANED) MediaLifecycle.ATTACHED
        else current

    /** detach 后的生命周期：只有托管类型、且引用数归零的 attached 文件进入 orphaned。 */
    fun lifecycleAfterDetach(
        policy: FileBusinessPolicy?,
        current: MediaLifecycle,
        remainingRefs: Long,
    ): MediaLifecycle =
        if (policy?.managedLifecycle == true && remainingRefs == 0L && current == MediaLifecycle.ATTACHED) {
            MediaLifecycle.ORPHANED
        } else {
            current
        }

    /**
     * 清理判断（必须在行锁内、用锁内重新读到的状态调用）：托管类型、仍是 active、
     * 没有任何引用、并且已过对应宽限期。
     */
    fun isExpired(
        policy: FileBusinessPolicy?,
        status: Int,
        lifecycle: MediaLifecycle,
        lifecycleAt: Long?,
        refCount: Long,
        nowMillis: Long,
    ): Boolean {
        if (policy == null || !policy.managedLifecycle) return false
        if (status != 1 || refCount > 0 || lifecycleAt == null) return false
        val grace = when (lifecycle) {
            MediaLifecycle.TEMPORARY -> policy.temporaryHours.toLong() * HOUR_MS
            MediaLifecycle.ORPHANED -> policy.orphanedDays.toLong() * DAY_MS
            else -> return false
        }
        return lifecycleAt + grace < nowMillis
    }

    /**
     * 不依赖引用关系就能确定的读取结论；返回 null 表示需要按引用委托判断（DELEGATED 的非上传者）。
     */
    fun readWithoutReferences(
        policy: FileBusinessPolicy?,
        status: Int,
        lifecycle: MediaLifecycle,
        uploaderUid: Long?,
        viewerUserId: Long?,
    ): Boolean? {
        if (status != 1 || lifecycle == MediaLifecycle.DELETED || policy == null) return false
        val isUploader = viewerUserId != null && viewerUserId == uploaderUid
        return when (policy.access) {
            MediaAccess.PUBLIC -> true
            MediaAccess.OWNER -> isUploader
            MediaAccess.DELEGATED -> when {
                isUploader -> true
                // 没有引用的临时文件只有上传者可读
                lifecycle == MediaLifecycle.TEMPORARY -> false
                else -> null
            }
        }
    }

    private fun reject(code: MediaErrorCode, message: String) = AttachDecision.Reject(code, message)

    const val HOUR_MS: Long = 60L * 60L * 1000L
    const val DAY_MS: Long = 24L * HOUR_MS
}
