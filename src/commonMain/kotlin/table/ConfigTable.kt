package table

import model.Config
import model.ConfigTableImpl
import neton.database.api.Table

object ConfigTable : Table<Config, Long> by ConfigTableImpl
