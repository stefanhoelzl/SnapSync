package app.snapsync.databases

import app.cash.sqldelight.db.SqlDriver
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import java.io.File
import java.nio.file.Files

/** A [Databases] that hands out [driver], already prepared by the test (a hand-built old schema, a trigger). */
fun opened(driver: SqlDriver): Databases = Databases { _, _, _ -> DbOpen.Opened(driver) }

/** The real JVM adapter over a fresh directory of its own, removed when the JVM exits. */
fun freshJdbcDatabases(): JdbcDatabases =
    JdbcDatabases(Files.createTempDirectory("databases").toFile().also(File::deleteOnExit))
