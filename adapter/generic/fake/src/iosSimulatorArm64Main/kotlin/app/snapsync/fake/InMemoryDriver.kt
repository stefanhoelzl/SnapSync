package app.snapsync.fake

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.NO_VERSION_CHECK
import co.touchlab.sqliter.createDatabaseManager
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
private val serial = AtomicInt(0)

// SQLiter names an in-memory database and shares it between that name's connections, so every database needs a name
// no other in the process holds. ONE connection: the reader pool would otherwise open more connections on it.
@OptIn(ExperimentalAtomicApi::class)
internal actual fun newInMemoryDriver(): SqlDriver = NativeSqliteDriver(
    createDatabaseManager(
        DatabaseConfiguration(
            name = "in-memory-${serial.incrementAndFetch()}",
            version = NO_VERSION_CHECK,
            create = {},
            inMemory = true,
        ),
    ),
    maxReaderConnections = 1,
)
