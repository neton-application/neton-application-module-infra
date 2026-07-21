package table

import model.Job
import model.JobTableImpl
import neton.database.api.Table

object JobTable : Table<Job, Long> by JobTableImpl
