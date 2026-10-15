package app.snapsync.integration

import app.snapsync.control.behaviour.Given
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer

// The shared named steps of an event's life — time passing, photos moving, its photos deleted — beside `Steps.kt`'s
// (`docs/testing.md`, "How a test waits"): each one acts and awaits its own outcome.

/**
 * The app goes to the background — the phone in the pocket — and the mocked clock moves to [at], an ISO instant, the app
 * settled there. In the background, so what the app polls only while in front does not tick through the days.
 */
suspend fun Given.laterInThePocket(at: String) = step(
    "in the pocket until $at",
    act = {
        os.background()
        advanceTo(at)
    },
    until = { settled { true } },
)

/**
 * A fellow member [device]'s photo [asset], received: in the joined event, downloaded, and imported into this device's
 * library. [final] declares that member's share settled.
 */
suspend fun Given.received(device: String, asset: String, final: Boolean = false) {
    step(
        "$device's photo $asset on its way",
        act = {
            publicBackend.foreignDevice(device, listOf(asset), final = final)
            os.foreground()
        },
        until = {
            event<WorldEvent.DownloadStarted>()
            settled { true }
        },
    )
    step(
        "$device's photo $asset received",
        act = { downloadSession.finish() },
        until = {
            event<WorldEvent.LibraryAssetCreated> { it.asset == "imported-$device-$asset" }
            settled { true }
        },
    )
}

/**
 * An own photo [id], taken and shared — in what the event offers its members — by the extension's cycles. Needs
 * [extensionUploadsOnly].
 */
suspend fun Given.shared(id: String, date: String = Rig.PHOTO_DATE) {
    step("photo $id taken", act = { library.add(id, date = date) }, until = { settled { true } })
    step(
        "photo $id shared",
        act = {
            cycle()
            uploadQueue.completeJob(null)
            cycle()
        },
        until = {
            event<WorldEvent.UnionChanged> { it.asset == id && it.gained }
            settled { true }
        },
    )
}

/** The sweep deleted [event]'s photos, and the joined screen shows it as over. */
suspend fun Given.deletedOnBackend(event: String) = step(
    "its photos deleted",
    act = {
        backend.complete(event)
        os.foreground()
    },
    until = { screen<Layer.Joined> { it.deleted } },
)
