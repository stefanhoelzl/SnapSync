package app.snapsync.integration

import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.levers.WorldEvent

// The shared steps that bring a fellow member's photo towards this device (`Steps.kt` holds the rest).

/**
 * [device]'s photo [asset] shared to the joined event, and its download started in the operating system's session — the
 * app in front, its reconcile done. Answers that settle: what the library and the storage held before the bytes arrive.
 */
suspend fun Given.downloadStarted(device: String = DEV_F, asset: String = "FQ"): WorldEvent.Settled {
    lateinit var settle: Seen<WorldEvent.Settled>
    step(
        "$device's $asset on its way",
        act = {
            publicBackend.foreignDevice(device, listOf(asset))
            os.foreground()
        },
        until = {
            event<WorldEvent.DownloadStarted>()
            settle = settled { true }
        },
    )
    return settle.value
}

/** The photo [asset] of [device], as the library holds it once imported. */
fun imported(asset: String = "FQ", device: String = DEV_F) = "imported-$device-$asset"
