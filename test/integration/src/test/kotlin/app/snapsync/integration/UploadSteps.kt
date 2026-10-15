package app.snapsync.integration

import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.levers.WorldEvent

// The shared steps that move a member's own photos to the event through the OS-driven extension (`Steps.kt` holds the
// rest).

/** Everything the cycle picks up uploaded: a cycle, every job the OS holds landing, and a cycle to settle and publish. */
suspend fun Acts.uploadAll() {
    cycle()
    uploadQueue.completeJob()
    cycle()
}

/** The own photos [ids] uploaded through the extension ([uploadAll]), their bytes stored, and whatever followed done. */
suspend fun Given.uploaded(vararg ids: String) = step("${ids.joinToString()} uploaded", act = { uploadAll() }, until = {
    ids.forEach { id -> event<WorldEvent.BytesStored> { it.asset == id } }
    settled { true }
})
