package app.snapsync.services.crypto

import app.snapsync.model.AssetRef
import app.snapsync.model.EncryptedFileFormat
import app.snapsync.model.FileArea
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.model.roleFromUploadKey
import app.snapsync.ports.Files
import app.snapsync.services.config.ConfigService
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * **How a downloaded resource is opened** (the encrypted file format, `docs/architecture.md`): not at all in a plain
 * event; in an encrypted one, decrypted with the joined event's key, expecting it bound to the resource the union named
 * — the event, the device that shared it, its asset and its role. A file that does not authenticate (damaged, cut
 * short, or moved from elsewhere) is never imported: it is discarded and downloaded again, like any download that
 * failed, for as long as the event lasts.
 *
 * Retrying forever must not be silent: the [REPORT_AFTER]th consecutive failure of one resource is reported ONCE, at
 * `Error`, which reaches the crash channel — and nothing after it, so a file that never opens costs one report, not one
 * per retry. Counted per process; a later process may report the same resource again, once.
 */
/** Where an encrypted event's download waits beside its landing path until it is opened into it. */
const val SEALED_SUFFIX: String = ".sealed"

/** Where its plaintext is written until every segment authenticated ([FileCipher] builds `<to>.part`). */
private const val PART_SUFFIX: String = ".part"

/**
 * The files a download landing at [landing] passes through while it is opened — the sealed bytes and the plaintext
 * being written — which a staging sweep must not take for unclaimed while that runs.
 */
fun openingFilesOf(landing: String): List<String> = listOf("$landing$SEALED_SUFFIX", "$landing$PART_SUFFIX")

class DownloadOpening(
    private val keys: EventKeys,
    private val cipher: FileCipher,
    private val config: ConfigService,
    private val files: Files,
    private val log: Logger = Logger.withTag("DownloadOpening"),
) {
    /** Consecutive failures per resource; called from the platform's delegate queue and the core lane alike. */
    private val failures = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Whether the joined event's resources are sealed — so each must be opened before it is staged. */
    fun sealed(): Boolean = config.joinedOrRead()?.keyId != null

    /**
     * Open [from] into [to] in the shared area, for [ref]'s [resourceKey]. `true` only when every byte authenticated
     * and the plaintext is in place; [from] is removed either way.
     */
    fun open(ref: AssetRef, resourceKey: String, eventId: String, from: String, to: String): Boolean {
        val outcome = attempt(ref, resourceKey, eventId, from, to)
        files.delete(FileArea.SHARED, from)
        val id = "${ref.sourceDeviceId}/${ref.sourceAssetId.value}/$resourceKey"
        if (outcome == null) {
            failures.update { it - id }
            return true
        }
        // A lost key is the membership's state, not this file's fault: the joined screen
        // says so and no new download starts, so it counts toward no report.
        if (outcome == NO_KEY) {
            log.w { "an encrypted download could not be opened: $outcome" }
            return false
        }
        val count = failures.updateAndGet { it + (id to (it[id] ?: 0) + 1) }.getValue(id)
        if (count == REPORT_AFTER) {
            log.e { "an encrypted download failed to open $count times in a row (${ref.sourceDeviceId}): $outcome" }
        } else {
            log.w { "an encrypted download did not open (attempt $count, will re-download): $outcome" }
        }
        return false
    }

    /**
     * `null` when the plaintext is in place, else why not. [eventId] is the event the transfer fetched for (`""` for a
     * transfer started before transfers named their event): only the joined event's key can open it, so another
     * event's — a transfer a switch left behind — is not tried.
     */
    private fun attempt(ref: AssetRef, resourceKey: String, eventId: String, from: String, to: String): String? {
        val joined = config.joinedOrRead() ?: return "not joined"
        if (eventId.isNotEmpty() && eventId != joined.eventId) return "fetched for another event"
        val key = try {
            keys.current()
        } catch (locked: SecureStoreUnavailable) {
            return "the event key cannot be read now: ${locked.detail}"
        } ?: return NO_KEY
        val ad = EncryptedFileFormat.associatedData(
            joined.eventId,
            ref.sourceDeviceId,
            ref.sourceAssetId.value,
            roleFromUploadKey(resourceKey).wire,
        )
        return when (val opened = cipher.decrypt(key, ad, FileArea.SHARED, from, to)) {
            Opened.Ok -> null
            else -> opened.toString()
        }
    }

    private companion object {
        /** Failures in a row before one is reported: past a transfer glitch, and still within the first hour. */
        const val REPORT_AFTER = 4

        /** Why a resource did not open when no key is kept for the joined event. */
        const val NO_KEY = "no event key is kept"
    }
}
