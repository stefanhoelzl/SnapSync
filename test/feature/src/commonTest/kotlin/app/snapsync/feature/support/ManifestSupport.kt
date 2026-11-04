package app.snapsync.feature.support

import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.feature.membership.DeviceManifestProducer
import app.snapsync.feature.membership.ManifestPublish
import app.snapsync.model.DeviceManifest
import app.snapsync.services.backend.ManifestPublisher
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId

/**
 * **The manifest a pass publishes**, over the real [ManifestPublish] and producer and [ledger]'s rows: each publish
 * the backend was handed lands in [published]. [failing] makes the producer throw, as a store that cannot be written.
 */
class PublishedManifests(val ledger: TestLedger = TestLedger()) {
    val published = mutableListOf<DeviceManifest>()
    var failing = false

    private val publisher = object : ManifestPublisher {
        override suspend fun publish(eventId: EventId, deviceId: DeviceId, manifest: DeviceManifest): Boolean {
            check(!failing) { "the manifest store cannot be written" }
            published += manifest
            return true
        }
    }

    val publish = ManifestPublish(
        ledger.rows,
        lazy { DeviceManifestProducer(DeviceManifestService(ledger.shared), publisher, testDeviceId("dev")) },
        configService(),
    )

    /** What one publish of [event]'s rows under [policy] handed the backend — a fresh version each time. */
    suspend fun of(policy: SelectionPolicy, event: String = LEDGER_EVENT): DeviceManifest? {
        val before = published.size
        publish.publish(testEventId(event), policy, version = before + 1L)
        return published.drop(before).lastOrNull()
    }
}
