package controller.admin.file.dto

import kotlinx.serialization.Serializable
import logic.FileStorageSettings
import neton.validation.annotations.Max
import neton.validation.annotations.Min
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Size

@Serializable
data class FileConfigVO(
    val id: Long = 0,
    val name: String? = null,
    val storage: Int? = null,
    val master: Int? = null,
    val remark: String? = null,
    val config: FileStorageSettings? = null,
    val valid: Boolean = false,
    val testPassed: Boolean? = null,
    val status: Int = 1,
)

@Serializable
data class CreateFileConfigRequest(
    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val name: String,

    @property:Min(0)
    @property:Max(1)
    val storage: Int,

    @property:Min(0)
    @property:Max(1)
    val master: Int = 0,

    @property:Size(min = 0, max = 255)
    val remark: String? = null,
    val config: FileStorageSettings,
)

@Serializable
data class UpdateFileConfigRequest(
    @property:Min(1)
    val id: Long,

    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val name: String,

    @property:Min(0)
    @property:Max(1)
    val storage: Int,

    @property:Min(0)
    @property:Max(1)
    val master: Int = 0,

    @property:Size(min = 0, max = 255)
    val remark: String? = null,
    val config: FileStorageSettings,
)
