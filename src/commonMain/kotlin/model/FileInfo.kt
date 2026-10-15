package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.CreatedAt

/**
 * 文件元数据（spec MODULE_MEMBER_PROFILE_SPEC §4.2）。
 *
 * 表 `infra_files` 同时承担两类数据：
 * 1. **admin 元数据登记**（旧 `/admin/infra/file/upload` 路径）—— `fileId` /
 *    `ownerUid` / `businessType` / `sha256` / `status` 这五列允许为空 / 默认值
 * 2. **应用唯一用户态上传**（`/app/infra/file/upload`，spec §4.2）—— 五列必填
 *
 * 用 `fileId` 区分：客户端只看 `fileId`（公开 ID），不暴露自增 `id`。
 */
@Serializable
@Table("infra_files")
data class FileInfo(
    @Id
    val id: Long = 0,
    /** 对外公开 ID，格式 `file_<random>`；用户态上传必须有，admin 元数据登记可空。 */
    val fileId: String? = null,
    /** 上传者 uid（应用账号）；下载/引用时校验归属。后台上传的行为空，见 [adminUploaderId]。 */
    val ownerUid: Long? = null,
    /**
     * 后台上传者（system_users.id）。后台账号与应用账号是两套 id 空间，分列存放：
     * 有值时 [ownerUid] 为空，应用侧任何账号都不被当作这个文件的上传者。
     */
    val adminUploaderId: Long? = null,
    /** member_avatar / group_avatar / feedback_attachment / kyc_document / generic_image。 */
    val businessType: String? = null,
    val configId: Long? = null,
    val name: String,
    val path: String,
    val url: String? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    /** 内容 SHA-256，hex；与目录布局规则 `{businessType}/{sha256[0..2)}/{sha256[2..4)}/{sha256}.{ext}` 一致，不带 ownerUid。 */
    val sha256: String? = null,
    /** 1=active, 0=orphaned/deleted。 */
    val status: Int = 1,
    /**
     * 媒体生命周期（infra-media §6）：temporary / attached / orphaned / deleted，见 `port.MediaLifecycle`。
     * 只有 `managedLifecycle` 的业务类型会离开 attached；内置类型与历史行恒为 attached。
     * 进入 deleted 时 [status] 同步置 0。
     */
    val lifecycle: String = "attached",
    /** [lifecycle] 最近一次变化的时刻（epoch ms）；宽限期从这里算。 */
    val lifecycleAt: Long? = null,
    /** 物理对象已删除（或确认仍被同路径存活记录共用而无需删）的时刻；null 表示待清理。 */
    val purgedAt: Long? = null,
    @CreatedAt
    val createdAt: Long? = null
)
