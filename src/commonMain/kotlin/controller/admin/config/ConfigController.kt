package controller.admin.config

import controller.admin.config.dto.ConfigVO
import controller.admin.config.dto.CreateConfigRequest
import controller.admin.config.dto.UpdateConfigRequest
import logic.ConfigLogic
import model.Config
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Post
import neton.core.annotations.Put
import neton.core.annotations.Delete
import neton.core.annotations.Permission
import neton.core.annotations.Query
import neton.core.annotations.PathVariable
import neton.core.annotations.Body
import neton.core.http.NotFoundException
import neton.logging.Logger

@Controller("/infra/config")
class ConfigController(
    private val log: Logger,
    private val configLogic: ConfigLogic = ConfigLogic(log)
) {

    @Post("/create")
    @Permission("infra:config:create")
    suspend fun create(@Body request: CreateConfigRequest): Long {
        val config = Config(
            category = request.category,
            configKey = request.configKey,
            value = request.value,
            type = request.type,
            name = request.name,
            remark = request.remark
        )
        return configLogic.create(config)
    }

    @Put("/update")
    @Permission("infra:config:update")
    suspend fun update(@Body request: UpdateConfigRequest) {
        val existing = configLogic.get(request.id) ?: throw NotFoundException("Config not found")
        val config = existing.copy(
            category = request.category,
            configKey = request.configKey,
            value = request.value,
            type = request.type,
            name = request.name,
            remark = request.remark
        )
        configLogic.update(config)
    }

    @Delete("/delete/{id}")
    @Permission("infra:config:delete")
    suspend fun delete(@PathVariable id: Long) {
        configLogic.delete(id)
    }

    @Delete("/delete-list")
    @Permission("infra:config:delete")
    suspend fun deleteList(@Query ids: String) {
        ids.split(",").mapNotNull { it.trim().toLongOrNull() }.forEach { configLogic.delete(it) }
    }

    @Get("/get/{id}")
    @Permission("infra:config:query")
    suspend fun get(@PathVariable id: Long): Config? {
        return configLogic.get(id)
    }

    @Get("/page")
    @Permission("infra:config:query")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 10,
        @Query category: String? = null,
        @Query name: String? = null,
        @Query configKey: String? = null,
        @Query type: Int? = null
    ) = configLogic.page(pageNo, pageSize, category, name, configKey, type)

    @Get("/get-value-by-key")
    @Permission("infra:config:query")
    suspend fun getValueByKey(@Query key: String): String? {
        val config = configLogic.getByKey(key)
        return config?.value
    }
}
