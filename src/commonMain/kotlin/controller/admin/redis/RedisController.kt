package controller.admin.redis

import controller.admin.redis.dto.CommandStat
import controller.admin.redis.dto.RedisMonitorVO
import neton.core.annotations.*
import neton.logging.Logger
import neton.redis.RedisClient

@Controller("/infra/redis")
class RedisController(
    private val log: Logger,
    private val redis: RedisClient,
) {

    @Get("/get-monitor-info")
    @Permission("infra:redis:query")
    suspend fun getMonitorInfo(): RedisMonitorVO {
        return try {
            // 1. 获取 Redis INFO
            val infoRaw = redis.info() ?: ""
            val info = parseRedisInfo(infoRaw)

            // 2. 获取 DBSIZE
            val dbSize = redis.dbSize()

            // 3. 获取 commandstats
            val commandStatsRaw = redis.info("commandstats") ?: ""
            val commandStats = parseCommandStats(commandStatsRaw)

            RedisMonitorVO(
                info = info,
                dbSize = dbSize,
                commandStats = commandStats
            )
        } catch (e: Exception) {
            log.error("Failed to get Redis monitor info", mapOf("error" to (e.message ?: "Unknown")))
            RedisMonitorVO(
                info = defaultRedisInfo(),
                dbSize = 0,
                commandStats = emptyList()
            )
        }
    }

    /** 未配置 Redis 时返回前端期望的默认字段 */
    private fun defaultRedisInfo(): Map<String, String> = mapOf(
        "redis_version" to "N/A",
        "redis_mode" to "standalone",
        "tcp_port" to "0",
        "connected_clients" to "0",
        "uptime_in_days" to "0",
        "used_memory_human" to "0B",
        "used_cpu_user_children" to "0",
        "maxmemory_human" to "0B",
        "aof_enabled" to "0",
        "rdb_last_bgsave_status" to "N/A",
        "instantaneous_input_kbps" to "0",
        "instantaneous_output_kbps" to "0"
    )

    /**
     * 解析 Redis INFO 输出为 key-value Map
     * 格式: "key:value\r\n"，以 # 开头的行是 section 标题
     */
    private fun parseRedisInfo(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf(':')
            if (idx > 0) {
                result[trimmed.substring(0, idx)] = trimmed.substring(idx + 1)
            }
        }
        return result
    }

    /**
     * 解析 Redis INFO commandstats 输出为 CommandStat 列表
     * 格式: "cmdstat_get:calls=1024,usec=666,usec_per_call=0.65"
     */
    private fun parseCommandStats(raw: String): List<CommandStat> {
        val result = mutableListOf<CommandStat>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("cmdstat_")) continue
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx < 0) continue
            val command = trimmed.substring("cmdstat_".length, colonIdx)
            val values = trimmed.substring(colonIdx + 1)
            val calls = extractValue(values, "calls=")
            val usec = extractValue(values, "usec=")
            result.add(CommandStat(command = command, calls = calls, usec = usec))
        }
        return result
    }

    /** 从 "calls=1024,usec=666,..." 中提取指定 key 的数值 */
    private fun extractValue(values: String, key: String): Long {
        val start = values.indexOf(key)
        if (start < 0) return 0L
        val valueStart = start + key.length
        val end = values.indexOf(',', valueStart).let { if (it < 0) values.length else it }
        return values.substring(valueStart, end).toLongOrNull() ?: 0L
    }
}
