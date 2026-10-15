package app.snapsync.rig

import app.snapsync.contracts.EntryDriver
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.model.WakeId

/** The heartbeat's `BGTask` identifier, as the operating system hands it to the app. */
const val UPLOAD_HEARTBEAT_TASK: String = "app.snapsync.upload.heartbeat"

/** The idle heartbeat's `BGTask` identifier (an app refresh), as the operating system hands it to the app. */
const val IDLE_HEARTBEAT_TASK: String = "app.snapsync.heartbeat.idle"

/** The app uploader's background `URLSession` identifier, as the operating system hands it back. */
const val UPLOAD_TRANSFER_CHANNEL: String = "app.snapsync.upload.session"

/**
 * The operating system, driven through the mocks' operator faces (`docs/testing.md`, "The control channel"), as the
 * iOS adapters deliver. The heartbeat's tasks — busy and idle — wake the app through the wake mock; any other task identifier is answered
 * at once, unknown. The app uploader's session hands its events back through the upload session, every other identifier
 * through the download session — the iOS adapter's routing.
 */
class MockEntryDriver(private val device: MockDevice, private val os: PlayedOs) : EntryDriver {
    // A force-quit app is opened by the member — in front, or through a link — and launched for nothing else
    // (`played-os.forceQuit`).
    override fun foreground() {
        os.opened()
        device.lifecycle.foreground()
    }

    override fun background() {
        os.launching("move to the background")
        device.lifecycle.background()
    }

    override fun pushToken(hex: String) {
        os.launching("push token")
        device.pushService.deliverToken(hex)
    }

    override fun pushTokenFailure(description: String) {
        os.launching("push-token failure")
        device.pushService.deliverTokenFailure(description)
    }

    /**
     * The push the backend sent for [eventId], as APNs delivers it: the event, and the union position the backend's last
     * wake for it announced (decision record `changes/incremental-union`, D6) — none when it sent none, as for an event
     * whose union never grew, or when the backend behind the port is not the mock.
     */
    override fun silentPush(eventId: String?, done: () -> Unit) {
        os.launching("silent push")
        val seq = device.backend.pushesHandedOver().lastOrNull { it.eventId == eventId }?.seq
        val payload = buildMap<Any?, Any?> {
            put("eventId", eventId)
            seq?.let { put("seq", it) }
        }
        os.deliver(done) { device.pushService.deliverMessage(payload, it) }
    }

    override fun continueLink(url: String) {
        os.opened()
        device.links.open(url)
    }

    override fun backgroundTask(identifier: String, done: () -> Unit) {
        os.launching("background task")
        val heartbeat = identifier == UPLOAD_HEARTBEAT_TASK || identifier == IDLE_HEARTBEAT_TASK
        if (heartbeat) os.grant(done) { device.wakes.fire(WakeId.Heartbeat, it) } else done()
    }

    override fun backgroundTransfers(identifier: String, done: () -> Unit) {
        os.launching("background-session events")
        if (identifier == UPLOAD_TRANSFER_CHANNEL) {
            os.deliver(done) { device.uploadSession.handBack(it) }
        } else {
            os.deliver(done) { device.downloads.handBack(it) }
        }
    }
}

/**
 * The `os` service's deliveries on a launch with mocked systems (`docs/testing.md`, "Launch-time adapters"): each through the system
 * that delivers it — its mock's operator face where [mocked] says the adapter choice mocks it, the platform's own adapter
 * ([real]) where the system is real. A delivery a mocked system would make never reaches the real adapter, whose
 * composition is not listening; and the reverse.
 */
class ChosenEntryDriver(
    private val mocked: (MockedSystem) -> Boolean,
    private val mock: EntryDriver,
    private val real: EntryDriver,
) : EntryDriver {
    private fun by(system: MockedSystem): EntryDriver = if (mocked(system)) mock else real

    override fun foreground() = by(MockedSystem.LIFECYCLE).foreground()

    override fun background() = by(MockedSystem.LIFECYCLE).background()

    override fun pushToken(hex: String) = by(MockedSystem.PUSH).pushToken(hex)

    override fun pushTokenFailure(description: String) = by(MockedSystem.PUSH).pushTokenFailure(description)

    override fun silentPush(eventId: String?, done: () -> Unit) = by(MockedSystem.PUSH).silentPush(eventId, done)

    override fun continueLink(url: String) = by(MockedSystem.LINKS).continueLink(url)

    override fun backgroundTask(identifier: String, done: () -> Unit) = by(
        MockedSystem.WAKE,
    ).backgroundTask(identifier, done)

    override fun backgroundTransfers(identifier: String, done: () -> Unit) =
        by(if (identifier == UPLOAD_TRANSFER_CHANNEL) MockedSystem.UPLOAD_SESSION else MockedSystem.DOWNLOADS)
            .backgroundTransfers(identifier, done)
}
