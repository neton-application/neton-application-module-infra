package table

import model.ApiAccessLog
import model.ApiAccessLogTableImpl
import neton.database.api.Table

object ApiAccessLogTable : Table<ApiAccessLog, Long> by ApiAccessLogTableImpl
