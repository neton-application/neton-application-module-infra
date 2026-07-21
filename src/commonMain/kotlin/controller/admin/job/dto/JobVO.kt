package controller.admin.job.dto

import kotlinx.serialization.Serializable
import neton.validation.annotations.Max
import neton.validation.annotations.Min
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Size

@Serializable
data class JobVO(
    val id: Long = 0,
    val name: String? = null,
    val handlerName: String? = null,
    val handlerParam: String? = null,
    val cronExpression: String? = null,
    val status: Int? = null
)

@Serializable
data class CreateJobRequest(
    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val name: String,

    @property:NotBlank
    @property:Size(min = 1, max = 128)
    val handlerName: String,

    @property:Size(min = 0, max = 255)
    val handlerParam: String? = null,

    @property:Size(min = 0, max = 100)
    val cronExpression: String? = null,

    @property:Min(0)
    @property:Max(1)
    val status: Int = 0
)

@Serializable
data class UpdateJobRequest(
    @property:Min(1)
    val id: Long,

    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val name: String,

    @property:NotBlank
    @property:Size(min = 1, max = 128)
    val handlerName: String,

    @property:Size(min = 0, max = 255)
    val handlerParam: String? = null,

    @property:Size(min = 0, max = 100)
    val cronExpression: String? = null,

    @property:Min(0)
    @property:Max(1)
    val status: Int = 0
)
