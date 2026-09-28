package app.snapsync.rig

import app.snapsync.contracts.EntryDriver
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.model.WakeId

/** The heartbeat's `BGTask` identifier, as the operating system hands it to the app. */
const val UPLOAD_HEARTBEAT_TASK: String = "app.snapsync.upload.heartbeat"

/** The app uploader's background `URLSession` identifier, as the operating system hands it back. */
const val UPLOAD_TRANSFER_CHANNEL: String = "app.snapsync.upload.session"

/**
 * The operating system, driven through the mocks' operator faces (`docs/testing.md`, "The control channel"), as the
 * iOS adapters deliver. The heartbeat's task wakes the app through the wake mock; any other task identifier is answered
 * at once, unknown. The app uploader's session hands its events back through the upload session, every other identifier
 * through the download session — the iOS adapter's routing.
 */
class MockEntryDriver(private val device: MockDevice, private val os: PlayedOs) : EntryDriver {
    override fun foreground() = device.lifecycle.operator.foreground()

    override fun background() = device.lifecycle.operator.background()

    override fun pushToken(hex: String) = device.pushService.operator.deliverToken(hex)

    override fun pushTokenFailure(description: String) = device.pushService.operator.deliverTokenFailure(description)

    override fun silentPush(eventId: String?, done: () -> Unit) =
        device.pushService.operator.deliverMessage(mapOf("eventId" to eventId), os.completion(done))

    override fun continueLink(url: String) = device.links.operator.open(url)

    override fun backgroundTask(identifier: String, done: () -> Unit) {
        if (identifier == UPLOAD_HEARTBEAT_TASK) device.wakes.operator.fire(WakeId.Heartbeat, os.completion(done)) else done()
    }

    override fun backgroundTransfers(identifier: String, done: () -> Unit) {
        if (identifier == UPLOAD_TRANSFER_CHANNEL) {
            device.uploadSession.operator.handBack(os.completion(done))
        } else {
            device.downloads.operator.handBack(os.completion(done))
        }
    }
}

/**
 * The `/os` deliveries of a launch with mocked systems (`docs/testing.md`, "Launch-time adapters"): each through the system
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

    override fun backgroundTask(identifier: String, done: () -> Unit) = by(MockedSystem.WAKE).backgroundTask(identifier, done)

    override fun backgroundTransfers(identifier: String, done: () -> Unit) =
        by(if (identifier == UPLOAD_TRANSFER_CHANNEL) MockedSystem.UPLOAD_SESSION else MockedSystem.DOWNLOADS)
            .backgroundTransfers(identifier, done)
}
