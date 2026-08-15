package controller.admin.event

import controller.admin.event.dto.DiscardEventRequest
import logic.DomainEventOpsLogic
import model.DomainEventRecord
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.annotations.Query

/** outbox 运维接口：查看积压、定位终态失败、手工重投或丢弃。 */
@Controller("/infra/domain-event")
class DomainEventController(
    private val ops: DomainEventOpsLogic,
) {

    @Get("/page")
    @Permission("infra:domain-event:query")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query status: Int? = null,
        @Query listenerId: String? = null,
        @Query eventType: String? = null,
    ) = ops.page(pageNo, pageSize, status, listenerId, eventType)

    @Get("/get/{id}")
    @Permission("infra:domain-event:query")
    suspend fun get(@PathVariable id: Long): DomainEventRecord = ops.get(id)

    @Get("/stats")
    @Permission("infra:domain-event:query")
    suspend fun stats() = ops.stats()

    @Post("/requeue/{id}")
    @Permission("infra:domain-event:manage")
    suspend fun requeue(@PathVariable id: Long): DomainEventRecord = ops.requeue(id)

    @Post("/discard/{id}")
    @Permission("infra:domain-event:manage")
    suspend fun discard(@PathVariable id: Long, @Body request: DiscardEventRequest): DomainEventRecord =
        ops.discard(id, request.reason)
}
