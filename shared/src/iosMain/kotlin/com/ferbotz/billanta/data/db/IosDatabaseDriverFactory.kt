package com.ferbotz.billanta.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

class IosDatabaseDriverFactory(
    private val name: String = "billanta.db",
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver = NativeSqliteDriver(BillantaDb.Schema, name)
}
