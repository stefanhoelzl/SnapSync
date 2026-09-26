package app.snapsync.services.push

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.ports.Files
import co.touchlab.kermit.Logger

/**
 * The last push registration the backend accepted (capability `receiving-photos`) — what a delivered token is compared
 * against, so a registration is published only when it changed. One file in the shared area, beside the membership and
 * the manifest record: readable on a locked device once it has been unlocked since boot.
 *
 * A file rather than a secure-store item, deliberately: the shared area dies with the install, so a reinstall starts
 * with no record and publishes at its first entry — an item that outlived the install could suppress that publish
 * against a backend that has since lost the registration.
 *
 * **Never raises.** An unreadable record answers `null` and a refused write is logged and dropped: a lost record costs
 * one idempotent publish, and raising would end the registration's collector over a cache.
 */
class PushRegistrationRecord(
    private val files: Files,
    private val log: Logger = Logger.withTag("pushRegistrationRecord"),
) {

    /** The registration last recorded, or `null` for "none recorded" and "could not read it" alike — both publish. */
    fun loadLastRegistered(): String? = when (val read = files.read(FileArea.SHARED, LAST_REGISTERED)) {
        is FileResult.Ok -> read.value.decodeToString()
        else -> null
    }

    /** Record [value] as the registration the backend accepted; a write that fails leaves the next entry to publish. */
    fun saveLastRegistered(value: String) {
        val written = files.write(FileArea.SHARED, LAST_REGISTERED, value.encodeToByteArray())
        if (written !is FileResult.Ok) log.w { "the registration record was not written ($written) — the next entry publishes again" }
    }

    private companion object {
        /** Runtime identity: installed devices hold their record at this path. */
        const val LAST_REGISTERED = "push-registration/last-registered.txt"
    }
}
