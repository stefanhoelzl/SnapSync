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

/**
 * [device] shares exactly [assets] to the joined event, and each arrives in the library: downloaded, staged and
 * imported. The member's whole share — an asset it shared before and [assets] leaves out is withdrawn.
 */
suspend fun Given.receivedFrom(device: String, vararg assets: String) {
    step("$device shares ${assets.joinToString()}", act = {
        publicBackend.foreignDevice(device, assets.toList())
        os.foreground()
    }, until = {
        event<WorldEvent.DownloadStarted>()
        settled { true }
    })
    step("${assets.joinToString()} arrived", act = { downloadSession.finish() }, until = {
        assets.forEach { asset -> event<WorldEvent.LibraryAssetCreated> { it.asset == imported(asset, device) } }
        settled { true }
    })
}

/** The photo [asset] of [device], as the library holds it once imported. */
fun imported(asset: String = "FQ", device: String = DEV_F) = "imported-$device-$asset"
