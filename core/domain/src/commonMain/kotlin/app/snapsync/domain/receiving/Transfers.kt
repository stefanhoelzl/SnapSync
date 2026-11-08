package app.snapsync.domain.receiving

import kotlin.jvm.JvmInline

/**
 * A staged file's path, RELATIVE to the shared area — never an absolute platform path: a restored device's container
 * may move, and a row that kept the old one would point at nothing, its bytes present and read as consumed.
 */
@JvmInline
value class StagedPath(val value: String) {
    companion object {
        /** The directory every staged file lives under — runtime identity: devices hold files under it. */
        const val ROOT: String = "download-staging"

        /**
         * Where a resource of [ref] fetched for [folder] lands: under its event, so one event's staged bytes are one
         * folder. `/` is not legal in a segment: a device id never holds one, and a key or an event may.
         */
        fun of(folder: StagingFolder, ref: AssetRef, resourceKey: String): StagedPath {
            val leaf = "${ref.sourceDeviceId.value}/${resourceKey.replace('/', '_')}"
            return StagedPath(
                when (folder) {
                    is StagingFolder.Event -> "$ROOT/${folder.name.replace('/', '_')}/$leaf"
                    StagingFolder.Legacy -> "$ROOT/$leaf"
                },
            )
        }
    }
}

/**
 * The event a transfer fetches bytes for, and so the folder they are staged under. [Legacy] is a transfer — or a
 * resource planned — before transfers named their event (change `event-scoped-local-state`): its bytes stage where
 * they always did.
 */
sealed interface StagingFolder {
    class Event(val name: String) : StagingFolder

    data object Legacy : StagingFolder

    companion object {
        /** The folder a stored or tagged event text names; the empty text is the one no event was named in. */
        fun of(event: String): StagingFolder = when (event.isEmpty()) {
            true -> Legacy
            false -> Event(event)
        }
    }
}

/**
 * Whether a finished transfer's body may be staged.
 *
 * A background `URLSession` reports an HTTP error as a *successful transfer of an error body*, so this is the only
 * thing that sees a non-2xx; a staged error body would be imported, fail, and never be downloaded again. It rejects
 * only on POSITIVE evidence — a known non-2xx status, or a known length the body falls short of — since a rejected
 * transfer is retried, and a retry only helps if the condition can change: an unknown length, an over-length and an
 * unknown status all pass.
 */
/** What the platform said of a finished transfer: its HTTP status (`null`: none), the bytes it promised and sent. */
class TransferFacts(val status: Int?, val expectedBytes: Long, val receivedBytes: Long)

sealed interface TransferVerdict {
    data object Stage : TransferVerdict

    data object Reject : TransferVerdict

    companion object {
        fun of(facts: TransferFacts): TransferVerdict = when {
            facts.status != null && facts.status !in OK_STATUSES -> Reject
            else -> length(facts.expectedBytes, facts.receivedBytes)
        }

        private fun length(expected: Long, received: Long): TransferVerdict = when {
            expected >= 0 && received < expected -> Reject
            else -> Stage
        }

        private val OK_STATUSES = 200..299
    }
}

/**
 * Whether a download address is one a background session can fetch. `NSURL` parses a hostless or non-HTTP string, and
 * handing one to a background session raises an Objective-C exception nothing can catch — so it is judged here.
 */
sealed interface FetchVerdict {
    data object Fetchable : FetchVerdict

    data object Unfetchable : FetchVerdict

    companion object {
        fun of(url: String): FetchVerdict = when (url.substringBefore("://", missingDelimiterValue = "").lowercase()) {
            "http", "https" -> host(url)
            else -> Unfetchable
        }

        private fun host(url: String): FetchVerdict {
            val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            return when (authority.substringAfterLast('@').substringBefore(':').isEmpty()) {
                true -> Unfetchable
                false -> Fetchable
            }
        }
    }
}

/** How many transfers run at once (Apple: keep background tasks in the low hundreds; this stays well under). */
object TransferSlots {
    const val MAX: Int = 24
}
