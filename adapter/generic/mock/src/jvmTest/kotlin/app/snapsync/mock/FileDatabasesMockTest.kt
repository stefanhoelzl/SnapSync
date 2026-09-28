package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DatabasesContract
import app.snapsync.contracts.DatabasesState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.verify
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The databases mock with a directory — the launch-time adapters' persisted databases (`docs/testing.md`, "Launch-time
 * adapters") — holds to the same contract as the in-memory one, and a second mock over the same directory (the next
 * launch, or the other process) opens what the first wrote.
 */
class FileDatabasesMockTest {

    private fun directory(): String = Files.createTempDirectory("mock-databases").toString()

    private val binding = object : Binding<DatabasesState, Databases> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val reaches = setOf(DatabasesState.ABSENT, DatabasesState.CURRENT, DatabasesState.OLD, DatabasesState.UNOPENABLE)
        override fun create(state: DatabasesState, clauseId: String): Entered<Databases> {
            val refusals = if (state == DatabasesState.UNOPENABLE) mapOf(DatabasesContract.NAME to DbOpen.Failed("unopenable")) else emptyMap()
            val databases = DatabasesMock(refusals, directory()).port()
            when (state) {
                DatabasesState.CURRENT -> DatabasesContract.enterCurrent(databases)
                DatabasesState.OLD -> DatabasesContract.enterOld(databases)
                DatabasesState.ABSENT, DatabasesState.UNOPENABLE -> Unit
            }
            return Entered.Ready(databases)
        }
    }

    @Test
    fun `the file-backed databases satisfy the Databases contract`() = verify(DatabasesContract, binding)

    @Test
    fun `the next launch opens what the last one wrote`() {
        val dir = directory()
        DatabasesContract.enterCurrent(DatabasesMock(directory = dir).port())
        val next = DatabasesMock(directory = dir).port()
        val opened = assertIs<DbOpen.Opened>(next.open(DatabasesContract.NAME, DatabasesContract.Current, readOnly = true))
        val kept = opened.driver.executeQuery(null, "SELECT v FROM probe WHERE id = 1", { c ->
            app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null)
        }, 0).value
        assertEquals(DatabasesContract.KEPT, kept)
    }
}
