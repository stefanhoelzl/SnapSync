@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.data.store

import app.snapsync.data.membership.sharedOver
import app.snapsync.data.receiving.DOWNLOADS_DB_NAME
import app.snapsync.data.receiving.DownloadDatabaseFile
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.mock.DatabasesMock
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What another process committed reaches the store it committed to, by the name the `Databases` port gives it. */
@OptIn(AppProcessOnly::class)
class ChangedElsewhereTest {

    private val databases = DatabasesMock()
    private val shared = sharedOver(databases)
    private val downloads = DownloadDatabaseFile(databases.port())
    private val elsewhere = ChangedElsewhere(shared, downloads)

    /** Whether [changes] is told a change when [name] is: past its first look, one more. */
    private fun TestScope.toldOf(changes: StoreChanges, name: String): Boolean {
        var told = false
        backgroundScope.launch { changes.looks.drop(1).first().also { told = true } }
        runCurrent()
        elsewhere.changed(name)
        runCurrent()
        return told
    }

    @Test
    fun `a commit to the shared database is announced on its signal alone`() = runTest {
        assertEquals(true, toldOf(shared.changes, LEDGER_DB_NAME))
        assertEquals(false, toldOf(downloads.changes, LEDGER_DB_NAME))
    }

    @Test
    fun `a commit to the download store is announced on its signal alone`() = runTest {
        assertEquals(true, toldOf(downloads.changes, DOWNLOADS_DB_NAME))
        assertEquals(false, toldOf(shared.changes, DOWNLOADS_DB_NAME))
    }

    @Test
    fun `a database no store opens is announced nowhere`() = runTest {
        assertEquals(false, toldOf(shared.changes, "unknown.db"))
        assertEquals(false, toldOf(downloads.changes, "unknown.db"))
    }
}
