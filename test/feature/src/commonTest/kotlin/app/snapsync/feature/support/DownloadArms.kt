package app.snapsync.feature.support

import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.download.DownloadArm
import app.snapsync.format.TimestampText
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.EventConfig
import app.snapsync.testids.testEventId

/** A download arm over [enabled], for a member of a plain event — or of none: no key is needed. */
fun noKeyArm(enabled: () -> Boolean? = { true }): DownloadArm =
    DownloadArm(
        enabled,
        EventKeyStore(inMemorySecureStore(), EventKeyIds(fakeCrypto())),
        MembershipRepository(testShared()),
    )

/** A member of an encrypted event whose key this device lost; [giveBack] keeps it again, as its reopened invite does. */
class LostKey {
    private val ids = EventKeyIds(fakeCrypto())
    private val key = ids.mint()
    private val keys = EventKeyStore(inMemorySecureStore(), ids)
    private val store = SharedStore().apply { persist(encryptedConfig(key.id.value)) }

    val arm = DownloadArm({ true }, keys, MembershipRepository(store.db))

    fun giveBack() {
        keys.keep(KeyToKeep.Key(key), testEventId("E"))
    }
}

/** A joined encrypted event, its key's id [keyId]. */
private fun encryptedConfig(keyId: String) = EventConfig(
    eventId = testEventId("E"),
    name = "Secret",
    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-01-31T00:00:00Z")),
    keyId = keyId,
    endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
    deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
)
