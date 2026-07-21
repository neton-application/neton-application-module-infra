package table

import model.FileConfig
import model.FileConfigTableImpl
import neton.database.api.Table

object FileConfigTable : Table<FileConfig, Long> by FileConfigTableImpl
