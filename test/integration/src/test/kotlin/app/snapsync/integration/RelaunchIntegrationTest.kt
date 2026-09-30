package app.snapsync.integration

import app.snapsync.model.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/device/relaunch` is process death and a cold launch (`docs/testing.md`, "The JVM root"): what the device keeps
 * survives into the new app, and what was process memory does not. A cell classified wrongly would make every
 * relaunch test lie, in the direction of whichever way it was wrong — so each is read back through what it does.
 *
 * The membership, the library and the operating system's upload job are `JvmHostProtocolTest`'s; the operating
 * system's download session outliving the process is `ColdDownloadRelaunchIntegrationTest`'s.
 */
class RelaunchIntegrationTest {

    @Test
    fun the_upload_ledger_survives_so_a_relaunched_app_uploads_nothing_twice() = rigTest {
        extensionUploadsOnly()
        createAndJoin()
        addPhoto("A")
        uploadAll()
        val created = jobs().created

        device("relaunch")
        cycle()

        assertEquals(created, jobs().created, "the ledger (an App-Group database) survives: A is not uploaded again")
        foreground() // the launch's foreground: its status read counts A as shared
        awaitInSync()
    }

    @Test
    fun the_version_gates_refusal_is_process_memory() = rigTest {
        createAndJoin()
        device("backend/min-app-version", "minimum" to "0.4")
        device("app-version", "version" to "0.3")
        foreground() // any backend call: the gate precedes every route
        awaitState { it.ui.layer is Layer.UpdateRequired }

        device("backend/min-app-version") // the gate is lifted, and nothing asks the backend again
        device("relaunch")

        assertTrue(state().ui.layer is Layer.Joined, "a new process starts with no refusal in memory")
    }
}
