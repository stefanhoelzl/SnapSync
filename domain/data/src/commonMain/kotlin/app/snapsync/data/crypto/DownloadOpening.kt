package app.snapsync.data.crypto

import app.snapsync.data.membership.MembershipRepository
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.event.OpenFailures
import app.snapsync.domain.event.OpenVerdict
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.FileArea
import app.snapsync.ports.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * **How a downloaded resource is opened** (the encrypted file format, `docs/architecture.md`): not at all in a plain
 * event; in an encrypted one, opened with the joined event's key, expecting it bound to the resource the union named —
 * the event, the device that shared it, its asset and its role. A file that does not open (damaged, cut short, or moved
 * from elsewhere) is never staged: it is discarded and downloaded again, like any download that failed, for as long as
 * the event lasts — and its [OpenFailures.REPORT_AFTER]th failure in a row is reported once, at `Error`. Counted per
 * process; a later process may report the same resource again, once.
 */
class DownloadOpening(
    private val membership: MembershipRepository,
    private val keys: EventKeyStore,
    private val cipher: FileCipher,
    private val files: Files,
) {
    /** Each resource's failures in a row, in this process — read from the delegate queue and the core lane alike. */
    private val failures = MutableStateFlow<Map<String, OpenFailures>>(emptyMap())

    /** Where a download of [path] lands: at its path in a plain event, beside it until opened in an encrypted one. */
    fun landing(path: String): Landing = when (held(membership.state.value)?.event?.encryption) {
        is Encryption.Encrypted -> Landing.Sealed(SealedLanding.sealedOf(path), path)
        is Encryption.Plain, null -> Landing.Plain(path)
    }

    /**
     * Open [landing] when it is sealed — [source]'s [asset] as its [role], fetched for [fetchedFor] (`null`: a transfer
     * from before transfers named their event) — into its path; the sealed file is removed either way. A plain one is
     * open already.
     */
    suspend fun open(
        landing: Landing,
        source: DeviceId,
        asset: AssetId,
        role: ResourceRole,
        fetchedFor: EventId?,
    ): OpenAnswer = when (landing) {
        is Landing.Plain -> OpenAnswer.Opened
        is Landing.Sealed -> sealed(landing, source, asset, role, fetchedFor)
    }

    private suspend fun sealed(
        landed: Landing.Sealed,
        source: DeviceId,
        asset: AssetId,
        role: ResourceRole,
        fetchedFor: EventId?,
    ): OpenAnswer {
        val held = held(membership.state.value)
        val verdict = OpenVerdict.of(held, fetchedFor, KeyPresence.of(held, keys.state.value))
        val resource = Resource(landed, source, asset, role)
        return opened(resource, verdict).also { files.delete(FileArea.SHARED, landed.sealedPath) }
    }

    private suspend fun opened(resource: Resource, verdict: OpenVerdict): OpenAnswer = when (verdict) {
        is OpenVerdict.OpenWith -> answered(
            resource,
            cipher.open(
                verdict.key,
                resource.bindingIn(verdict.event),
                FileArea.SHARED,
                resource.landed.sealedPath,
                resource.landed.path,
            ),
        )
        is OpenVerdict.NotJoined -> failed(resource, "not joined")
        is OpenVerdict.OtherEvent -> failed(resource, "fetched for another event")
        is OpenVerdict.KeyUnreadable -> failed(resource, "the event key cannot be read now")
        is OpenVerdict.NoKey -> keyless()
    }

    private suspend fun answered(resource: Resource, outcome: OpenOutcome): OpenAnswer = when (outcome) {
        is OpenOutcome.Opened -> OpenAnswer.Opened.also { failures.update { it - resource.id } }
        is OpenOutcome.OtherKey -> failed(resource, "the file names another event key")
        is OpenOutcome.Damaged -> failed(resource, outcome.reason)
        is OpenOutcome.Unreadable -> failed(resource, "unreadable: ${outcome.result}")
    }

    private suspend fun failed(resource: Resource, why: String): OpenAnswer {
        var counted = OpenFailures.NONE
        failures.update { held ->
            counted = (held[resource.id] ?: OpenFailures.NONE).next()
            held + (resource.id to counted)
        }
        reported(counted, resource.source, why)
        return OpenAnswer.NotOpened
    }
}

/** Where a download lands, and so whether it must be opened before it is staged. */
sealed interface Landing {
    /** Where the platform's bytes are taken over to. */
    val stagedAt: String

    /** A plain event's: staged where it lands. */
    class Plain(val path: String) : Landing {
        override val stagedAt: String get() = path
    }

    /** An encrypted event's: taken over at [sealedPath], beside [path], and opened into it. */
    class Sealed(val sealedPath: String, val path: String) : Landing {
        override val stagedAt: String get() = sealedPath
    }
}

/** Whether a download opened; one that did not is downloaded again. */
sealed interface OpenAnswer {
    data object Opened : OpenAnswer

    data object NotOpened : OpenAnswer
}

/** The files an opening passes through — the sealed bytes and the plaintext being written. */
object SealedLanding {
    private const val SEALED_SUFFIX = ".sealed"

    /** Where a download landing at [path] waits while it is sealed. */
    internal fun sealedOf(path: String): String = "$path$SEALED_SUFFIX"

    /** The files an opening of [path] passes through: a staging sweep must not take them for unclaimed meanwhile. */
    fun filesOf(path: String): List<String> = listOf(sealedOf(path), Part.of(path))
}

private class Resource(val landed: Landing.Sealed, val source: DeviceId, val asset: AssetId, val role: ResourceRole) {
    val id = "$source/$asset/$role"

    fun bindingIn(event: EventId) = FileBinding(event, source, asset, role)
}

private val log = Log("DownloadOpening")

/** A lost key is the membership's state, not this file's fault: the joined screen says so, and nothing is counted. */
private suspend fun keyless(): OpenAnswer = OpenAnswer.NotOpened.also {
    log.w("an encrypted download was not opened: no event key is kept")
}

private suspend fun reported(failures: OpenFailures, source: DeviceId, why: String) = when (failures.report) {
    OpenFailures.Report.ONCE -> log.e(
        "an encrypted download failed to open, again",
        "failures" of failures.count,
        "source" of source,
        "why" of why,
    )
    OpenFailures.Report.QUIETLY -> log.w(
        "an encrypted download did not open; it is downloaded again",
        "attempt" of failures.count,
        "why" of why,
    )
}
