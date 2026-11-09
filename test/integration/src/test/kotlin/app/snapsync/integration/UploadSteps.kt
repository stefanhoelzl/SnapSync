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

/** Only the app uploads: the extension is switched off — a behaviour about the app's own wakes drives them alone. */
suspend fun Given.appUploadsOnly() =
    step("only the app uploads", act = { devControls.pin(app = true, extension = false) }, until = {})

/**
 * The phone runs iOS 26.1 or later, which carries iOS's own background uploader: a fact the app reads at launch, so the
 * process is started again over it, in front, as a member opening the app finds it.
 */
suspend fun Given.iosUploaderCarried() = step(
    "iOS's own uploader carried",
    act = {
        extensionRegistry.carried(true)
        host.relaunch(scene = true)
    },
    until = { settled { true } },
)

/**
 * Own photos [ids], already in the library, found and declared by the extension alone while the operating system takes
 * no job. The app's process never walked them: once it uploads ([theAppAloneUploads]), its top-up — the first step of
 * its pass — looks each one up again before its transfer.
 */
suspend fun Given.declaredWithNoJob(vararg ids: String) {
    step("the extension alone uploads, the queue taking no job", act = {
        devControls.pin(app = false, extension = true)
        uploadQueue.jobLimit(0)
    }, until = {})
    step("${ids.joinToString()} found and declared, no job made", act = { cycle() }, until = {
        ids.forEach { id -> event<WorldEvent.ManifestPublished> { id in it.added } }
        settled { true }
    })
}

/** Only the app uploads from now on: the switch its next pass reads at its gate, and that pass runs at once. */
suspend fun Acts.theAppAloneUploads() = devControls.pin(app = true, extension = false)
