package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test

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
    @Verifies(spec = "delivery", requirement = "A photo is never lost on the way")
    fun the_upload_ledger_survives_so_a_relaunched_app_uploads_nothing_twice() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            step("A uploaded", act = {
                cycle()
                uploadQueue.completeJob()
                cycle()
            }, until = {
                event<WorldEvent.ManifestPublished> { "A" in it.added }
                settled { true }
            })
        }

        // The ledger (an App-Group database) survives: A is not uploaded again.
        whenever {
            host.relaunch()
            cycle()
        }
        then { never<WorldEvent.UploadJobCreated>() }

        // The launch's foreground: its status read counts A as shared.
        whenever { os.foreground() }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    @Test
    fun the_version_gates_refusal_is_process_memory() = behaviour {
        given {
            joinedEvent()
            step("an outdated build", act = {
                backend.minAppVersion("0.4")
                build.declare("0.3")
            }, until = {})
            // Any backend call: the gate precedes every route.
            step("refused", act = { os.foreground() }, until = {
                screen<Layer.UpdateRequired>()
                settled { true }
            })
            // The gate is lifted, and nothing asks the backend again.
            step("the gate lifted", act = { backend.minAppVersion() }, until = {})
        }

        whenever { host.relaunch() }
        then {
            screen<Layer.Joined>()
            neverScreen<Layer.UpdateRequired>() // a new process starts with no refusal in memory
        }
    }
}
