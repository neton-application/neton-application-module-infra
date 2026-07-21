package logic

import dto.PageResponse
import model.Config
import table.ConfigTable
import neton.database.dsl.*

import neton.logging.Logger
import neton.redis.RedisClient
import kotlin.time.Duration.Companion.minutes

@neton.core.annotations.Logic(logger = "logic.config")
class ConfigLogic(
    private val log: Logger,
    private val redis: RedisClient? = null
) {

    companion object {
        private const val CACHE_PREFIX = "infra:config:key:"
        private val CACHE_TTL = 30.minutes
    }

    suspend fun create(config: Config): Long {
        val inserted = ConfigTable.insert(config)
        log.info("Created config with id: ${inserted.id}, key: ${config.configKey}")
        return inserted.id
    }

    suspend fun update(config: Config) {
        ConfigTable.update(config)
        // Invalidate cache
        redis?.delete("$CACHE_PREFIX${config.configKey}")
        log.info("Updated config with id: ${config.id}")
    }

    suspend fun delete(id: Long) {
        val config = ConfigTable.get(id)
        ConfigTable.destroy(id)
        // Invalidate cache
        if (config != null) {
            redis?.delete("$CACHE_PREFIX${config.configKey}")
        }
        log.info("Deleted config with id: $id")
    }

    suspend fun get(id: Long): Config? {
        return ConfigTable.get(id)
    }

    suspend fun getByKey(configKey: String): Config? {
        // Check Redis cache first
        val cached = redis?.getValue("$CACHE_PREFIX$configKey")
        if (cached != null) {
            log.info("Config cache hit for key: $configKey")
        }

        // Query from DB (cache stores the value for quick retrieval,
        // but we still return the full Config object from DB)
        val config = ConfigTable.oneWhere {
            Config::configKey eq configKey
        }

        // Store value in cache for next time
        if (config != null && cached == null) {
            redis?.set("$CACHE_PREFIX$configKey", config.value, ttl = CACHE_TTL)
        }

        return config
    }

    suspend fun page(
        page: Int,
        size: Int,
        category: String? = null,
        name: String? = null,
        configKey: String? = null,
        type: Int? = null
    ): PageResponse<Config> {
        val result = ConfigTable.query {
            where {
                and(
                    whenNotBlank(category) { Config::category eq it },
                    whenNotBlank(name) { Config::name like "%$it%" },
                    whenNotBlank(configKey) { Config::configKey like "%$it%" },
                    whenPresent(type) { Config::type eq it }
                )
            }
            orderBy(Config::id.desc())
        }.page(page, size)
        return PageResponse(
            list = result.items,
            total = result.total,
            page = page,
            size = size,
            totalPages = if (size > 0) ((result.total + size - 1) / size).toInt() else 0
        )
    }
}
