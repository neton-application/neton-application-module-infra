package table

import model.FileInfo
import model.FileInfoTableImpl
import neton.database.api.Table

object FileInfoTable : Table<FileInfo, Long> by FileInfoTableImpl
