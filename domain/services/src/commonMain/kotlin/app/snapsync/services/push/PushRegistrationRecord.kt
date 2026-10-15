package app.snapsync.services.push

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.PrefRead
import app.snapsync.ports.Files
import app.snapsync.ports.Preferences
import app.snapsync.ports.WriteOutcome
import app.snapsync.services.preferences.PreferenceKeys
import co.touchlab.kermit.Logger

/** The registration record's Preferences key — runtime identity. */
private const val LAST_REGISTERED_KEY = PreferenceKeys.LAST_REGISTERED

/** Where the record lived before it moved into [Preferences] — runtime identity: an updated device still holds it. */
private const val LEGACY_FILE = "push-registration/last-registered.txt"

/**
 * The last push registration the backend accepted — what a delivered token is compared
 * against, so a registration is published only when it changed. A device-scoped value, independent of any event and
 * never committed with anything, so it lives in the shared [Preferences] (`docs/architecture.md`, "Storage"): readable on
 * a locked device once it has been unlocked since boot, and gone with the install — a reinstall starts with no record
 * and publishes at its first entry, where an item that outlived the install could suppress that publish against a
 * backend that has since lost the registration.
 *
 * Only the app reads and writes it. An updated device's record is copied from its legacy file at the first read that
 * finds no key ([files]; a shim, `PENDING_CLEANUPS.md`).
 *
 * **Never raises.** An unreadable record answers `null` and a refused write is logged and dropped: a lost record costs
 * one idempotent publish, and raising would end the registration's collector over a cache.
 */
class PushRegistrationRecord(
    private val preferences: Preferences,
    private val files: Files,
    private val log: Logger = Logger.withTag("pushRegistrationRecord"),
) {

    /** The registration last recorded, or `null` for "none recorded" and "could not read it" alike — both publish. */
    fun loadLastRegistered(): String? = when (val read = preferences.get(LAST_REGISTERED_KEY)) {
        is PrefRead.Value -> read.value
        PrefRead.Absent -> fromLegacyFile()
        is PrefRead.Unavailable -> null
    }

    /** Record [value] as the registration the backend accepted; a write that fails leaves the next entry to publish. */
    fun saveLastRegistered(value: String) {
        val written = preferences.set(LAST_REGISTERED_KEY, value)
        if (written != WriteOutcome.Ok) {
            log.w { "the registration record was not written ($written) — the next entry publishes again" }
        }
    }

    /** The legacy file's record, moved into the key; `null` when there is none or it cannot be moved now. */
    private fun fromLegacyFile(): String? {
        val read = files.read(FileArea.SHARED, LEGACY_FILE) as? FileResult.Ok ?: return null
        val value = read.value.decodeToString()
        if (preferences.set(LAST_REGISTERED_KEY, value) == WriteOutcome.Ok) files.delete(FileArea.SHARED, LEGACY_FILE)
        return value
    }
}
