package com.ferbotz.billanta.data.db

import app.cash.sqldelight.db.SqlDriver

/** Platform hook: Android needs a Context, iOS just a file name — so this stays a factory. */
fun interface DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

fun createBillantaDb(factory: DatabaseDriverFactory): BillantaDb = BillantaDb(factory.createDriver())
