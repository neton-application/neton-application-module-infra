package controller.admin.redis.dto

import kotlinx.serialization.Serializable

@Serializable
data class RedisMonitorVO(
    val info: Map<String, String> = emptyMap(),
    val dbSize: Long = 0,
    val commandStats: List<CommandStat> = emptyList()
)

@Serializable
data class CommandStat(
    val command: String,
    val calls: Long,
    val usec: Long
)
