package app.snapsync.data.push

import app.snapsync.data.preferences.PreferenceKeys
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.push.LastRegistration
import app.snapsync.domain.push.PushRegistrationKey
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileFailure
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.ports.PrefRead
import app.snapsync.ports.Preferences
import app.snapsync.ports.WriteOutcome

/** Where the record lived before it moved into [Preferences] — runtime identity: an updated device still holds it. */
private const val LEGACY_FILE = "push-registration/last-registered.txt"

/**
 * The last push registration the backend accepted — what a delivered token is compared against, so a registration is
 * published only when it changed. A device-scoped value, independent of any event and never committed with anything,
 * so it lives in the shared [Preferences] (`docs/architecture.md`, "Storage"): readable on a locked device once it has
 * been unlocked since boot, and gone with the install — a reinstall starts with no record and publishes at its first
 * entry, where an item that outlived the install could suppress that publish against a backend that has since lost the
 * registration.
 *
 * Only the app reads and writes it. An updated device's record is copied from its legacy file at the first read that
 * finds no key ([files]; a shim, `PENDING_CLEANUPS.md`).
 */
class PushRegistrationRecord(private val preferences: Preferences, private val files: Files) {

    /** The registration last recorded, none, or a record that could not be read — the last two both publish. */
    fun lastAccepted(): LastRegistration = when (val read = preferences.get(PreferenceKeys.LAST_REGISTERED)) {
        is PrefRead.Value -> LastRegistration.Recorded(PushRegistrationKey(read.value))
        PrefRead.Absent -> legacy()
        is PrefRead.Unavailable -> LastRegistration.Unavailable
    }

    /** Record [key] as the registration the backend accepted. A refused write leaves the next entry to publish. */
    fun save(key: PushRegistrationKey): StoreWrite = when (
        val written = preferences.set(PreferenceKeys.LAST_REGISTERED, key.text)
    ) {
        WriteOutcome.Ok -> StoreWrite.Written
        is WriteOutcome.Failed -> StoreWrite.Failed(written.detail)
    }

    /** The legacy file's record, moved into the key — and kept in the file while the key cannot be written. */
    private fun legacy(): LastRegistration = when (val read = files.read(FileArea.SHARED, LEGACY_FILE)) {
        is FileResult.Ok -> moved(read.value.decodeToString())
        is FileFailure -> LastRegistration.None
    }

    private fun moved(value: String): LastRegistration = LastRegistration.Recorded(PushRegistrationKey(value)).also {
        when (preferences.set(PreferenceKeys.LAST_REGISTERED, value)) {
            WriteOutcome.Ok -> files.delete(FileArea.SHARED, LEGACY_FILE)
            is WriteOutcome.Failed -> Unit
        }
    }
}
