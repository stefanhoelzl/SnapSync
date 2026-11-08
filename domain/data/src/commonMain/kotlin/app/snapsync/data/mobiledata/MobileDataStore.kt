package app.snapsync.data.mobiledata

import app.snapsync.data.preferences.PreferenceKeys
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.PhotoNetwork
import app.snapsync.domain.mobiledata.StoredChoice
import app.snapsync.format.MobileDataText
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.PrefRead
import app.snapsync.ports.Preferences
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.WriteOutcome

private val log = Log("MobileData")

/**
 * The device's mobile-data choice ([MobileDataChoice]) in the shared [Preferences], so the app and the upload extension
 * read the same value. Every read goes to the store: the extension is another process an app-side change does not
 * reach otherwise.
 */
class MobileDataStore(private val preferences: Preferences) {

    /** The choice in effect now. */
    fun read(): MobileDataChoice = MobileDataChoice.of(stored())

    /** The networks a photo transfer created now may use. */
    fun photoNetwork(): PhotoNetwork = read().photoNetwork()

    /** [photoNetwork], as the transfer ports take it. */
    fun transferNetwork(): TransferNetwork = when (photoNetwork()) {
        PhotoNetwork.ANY -> TransferNetwork.ANY
        PhotoNetwork.UNRESTRICTED_ONLY -> TransferNetwork.UNRESTRICTED_ONLY
    }

    /** What is stored, for the diagnostic dump: the store's word, an unreadable one included. */
    fun stored(): StoredChoice = when (val read = preferences.get(PreferenceKeys.MOBILE_DATA)) {
        PrefRead.Absent -> StoredChoice.ABSENT
        is PrefRead.Value -> MobileDataText.parse(read.value)
        is PrefRead.Unavailable -> StoredChoice.UNREADABLE.also {
            log.unlabelled.w("mobile data unreadable — Wi-Fi only", "detail" of read.detail)
        }
    }

    /** Store [choice]. */
    fun write(choice: MobileDataChoice): StoreWrite =
        written(preferences.set(PreferenceKeys.MOBILE_DATA, MobileDataText.spell(choice)))

    private fun written(outcome: WriteOutcome): StoreWrite = when (outcome) {
        WriteOutcome.Ok -> StoreWrite.Written
        is WriteOutcome.Failed -> StoreWrite.Failed(outcome.detail)
    }
}
