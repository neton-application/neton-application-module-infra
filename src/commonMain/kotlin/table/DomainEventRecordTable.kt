package table

import model.DomainEventRecord
import model.DomainEventRecordTableImpl
import neton.database.api.Table

object DomainEventRecordTable : Table<DomainEventRecord, Long> by DomainEventRecordTableImpl
