package controller.admin.config.dto

import kotlinx.serialization.Serializable
import neton.validation.annotations.Max
import neton.validation.annotations.Min
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Size

@Serializable
data class ConfigVO(
    val id: Long = 0,
    val category: String? = null,
    val configKey: String? = null,
    val value: String? = null,
    val type: Int? = null,
    val name: String? = null,
    val remark: String? = null
)

@Serializable
data class CreateConfigRequest(
    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val category: String,

    @property:NotBlank
    @property:Size(min = 1, max = 100)
    val configKey: String,

    @property:NotBlank
    @property:Size(min = 1, max = 4000)
    val value: String,

    @property:Min(0)
    @property:Max(1)
    val type: Int = 0,

    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val name: String,

    @property:Size(min = 0, max = 255)
    val remark: String? = null
)

@Serializable
data class UpdateConfigRequest(
    @property:Min(1)
    val id: Long,

    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val category: String,

    @property:NotBlank
    @property:Size(min = 1, max = 100)
    val configKey: String,

    @property:NotBlank
    @property:Size(min = 1, max = 4000)
    val value: String,

    @property:Min(0)
    @property:Max(1)
    val type: Int = 0,

    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val name: String,

    @property:Size(min = 0, max = 255)
    val remark: String? = null
)
