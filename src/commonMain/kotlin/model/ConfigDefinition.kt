package model

import kotlinx.serialization.Serializable

/**
 * 全局配置项定义（key + 默认值 + 描述）。各业务模块把自己的配置枚举转成定义注册进
 * [ConfigDefinitionRegistry]，用于：后台新建配置时自动补全（选 key 即带出 name/默认值）、
 * 启动/手动同步缺失项入库。
 */
@Serializable
data class ConfigDefinition(
    val category: String,
    val key: String,
    val default: String,
    val name: String,
    val description: String = "",
    val type: Int = 0,
)

/**
 * 配置定义注册中心。application 装配层聚合各模块定义后 bind；infra 默认 bind 空表
 * （bindIfAbsent，保证未注册的下游也能编译运行）。
 */
class ConfigDefinitionRegistry(val definitions: List<ConfigDefinition> = emptyList())
