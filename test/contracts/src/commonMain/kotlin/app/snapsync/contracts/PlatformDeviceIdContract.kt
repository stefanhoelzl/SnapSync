package app.snapsync.contracts

import app.snapsync.ports.PlatformDeviceId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the platform offers, as far as a clause cares. */
enum class PlatformDeviceIdState {
    /** The platform offers a stable id this app may use (Android). */
    ANSWERING,

    /** It offers none (iOS, the JVM). */
    SILENT,
}

/**
 * What `PlatformDeviceId` promises (the port's KDoc carries why): an id the platform offers is **the same on every
 * ask** and has the **device-id shape** — the identity service persists it as the device id, and the backend accepts
 * only a UUID — and a platform that offers none says so with `null`, never an empty or made-up value.
 *
 * "The same after a reinstall" is the property the id is chosen for, and no clause can reach it: a test process cannot
 * outlive its own uninstall. It is checked by hand on the emulator (`snapsync-android` skill).
 */
object PlatformDeviceIdContract : Contract<PlatformDeviceIdState, PlatformDeviceId>("PlatformDeviceId") {

    /** The canonical upper-case form every device id has (`NSUUID().UUIDString`, the identity service's fallback). */
    private val CANONICAL = Regex("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$")

    override val clauses = clauses {

        clause("ANSWERING_IS_STABLE", PlatformDeviceIdState.ANSWERING) { platform ->
            val first = assertNotNull(platform.stableId(), "a platform that offers an id answers it")
            assertEquals(first, platform.stableId(), "a changed id is a new device to every event it is in")
        }

        clause("ANSWERING_IS_A_CANONICAL_UUID", PlatformDeviceIdState.ANSWERING) { platform ->
            val id = assertNotNull(platform.stableId())
            assertTrue(
                CANONICAL.matches(id),
                "'$id' is not an upper-case canonical UUID — the backend refuses any other device id",
            )
        }

        clause("SILENT_IS_NULL", PlatformDeviceIdState.SILENT) { platform ->
            assertNull(platform.stableId(), "no id is null, so the identity service mints its own")
        }
    }
}
