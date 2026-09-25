package app.snapsync.services.downloads

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.model.SuppressionReadiness
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.services.databases.DatabaseUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The extension's read-only suppression view over each answer a read-only open can give (capability
 * `receiving-photos`). The rows it reads over a real database are measured beside the adapters.
 */
class SuppressionServiceTest {

    /** Answers [answer] and records every open it was asked for. */
    private class Scripted(var answer: DbOpen) : Databases {
        val opens = mutableListOf<Pair<String, Boolean>>()
        override fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): DbOpen {
            opens += name to readOnly
            return answer
        }
    }

    @Test
    fun `it opens the download store read-only and only when asked`() = runTest {
        val databases = Scripted(DbOpen.Missing)
        val service = SuppressionService(databases)
        assertTrue(databases.opens.isEmpty(), "constructing the service opens nothing")
        service.readiness()
        assertEquals(listOf(DOWNLOADS_DB_NAME to true), databases.opens)
    }

    @Test
    fun `no store yet is ready and suppresses nothing`() = runTest {
        val service = SuppressionService(Scripted(DbOpen.Missing))
        assertEquals(SuppressionReadiness.Ready, service.readiness())
        assertEquals(emptySet(), service.suppressedLocalIds(), "no store means nothing was ever downloaded here")
    }

    @Test
    fun `an old store pauses and is never read as empty`() = runTest {
        val service = SuppressionService(Scripted(DbOpen.OldSchema))
        assertEquals(SuppressionReadiness.OldSchema, service.readiness())
        assertFailsWith<DatabaseUnavailable>("an empty answer would upload every downloaded photo back") {
            service.suppressedLocalIds()
        }
    }

    @Test
    fun `an unopenable store is unavailable and is never read as empty`() = runTest {
        val service = SuppressionService(Scripted(DbOpen.Failed("locked")))
        assertEquals(SuppressionReadiness.Unavailable("locked"), service.readiness())
        assertFailsWith<DatabaseUnavailable> { service.suppressedLocalIds() }
    }

    @Test
    fun `nothing but an open store is remembered`() = runTest {
        val databases = Scripted(DbOpen.Failed("locked"))
        val service = SuppressionService(databases)
        service.readiness()
        databases.answer = DbOpen.OldSchema
        assertEquals(SuppressionReadiness.OldSchema, service.readiness(), "a failure is retried on the next use")
        databases.answer = DbOpen.Missing
        assertEquals(SuppressionReadiness.Ready, service.readiness(), "and so is an old schema: the app migrates it")
        assertEquals(3, databases.opens.size)
    }
}
