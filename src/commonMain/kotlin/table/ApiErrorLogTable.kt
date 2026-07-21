package table

import model.ApiErrorLog
import model.ApiErrorLogTableImpl
import neton.database.api.Table

object ApiErrorLogTable : Table<ApiErrorLog, Long> by ApiErrorLogTableImpl
