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
    /** 上传者 uid；下载/引用时校验归属。 */
    val ownerUid: Long? = null,
    /** member_avatar / group_avatar / feedback_attachment / kyc_document / generic_image。 */
    val businessType: String? = null,
    val configId: Long? = null,
    val name: String,
    val path: String,
    val url: String? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    /** 内容 SHA-256，hex；与目录布局规则 `{businessType}/{ownerUid}/{sha256}.{ext}` 一致。 */
    val sha256: String? = null,
    /** 1=active, 0=orphaned/deleted。 */
    val status: Int = 1,
    @CreatedAt
    val createdAt: String? = null
)
