package table

import model.JobLog
import model.JobLogTableImpl
import neton.database.api.Table

object JobLogTable : Table<JobLog, Long> by JobLogTableImpl
