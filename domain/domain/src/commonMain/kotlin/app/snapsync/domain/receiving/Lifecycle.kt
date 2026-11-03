package app.snapsync.domain.receiving

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole

// A received photo's life on this device, as types (`docs/patterns.md`, "Evidence types"): it is planned, its resources
// are staged, it is imported, and its staged bytes are released. Each step takes the type the step before it answers,
// and only the download store mints them — so an unstaged photo cannot be imported and an unimported one cannot be
// released. Never stored, never serialized, never a data class.

/**
 * A foreign photo's stored state. Two are terminal — [IMPORTED] and [UNIMPORTABLE] — and "settled" means either: the
 * photo is done with, whichever way it ended.
 *
 * [UNIMPORTABLE] is a failure that is real: the library takes a resource's file at ingest, so a rejection of its
 * CONTENT leaves no bytes to retry from. A row in that state carries no created asset, so it is no suppression handle.
 */
enum class DownloadState {
    PENDING,
    IMPORTED,
    UNIMPORTABLE,
}

/** Only the download store mints a [StagedDownload]. */
@RequiresOptIn("Only the download store says every resource of a photo is staged.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsStagedDownload

/** Only the download store mints an [ImportedAsset]. */
@RequiresOptIn("Only the download store says a photo was imported.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsImportedAsset

/** One staged resource of a photo: where its bytes are, and what the library needs to know of them. */
class StagedResource(
    val resourceKey: String,
    val role: String,
    val contentType: String,
    val originalFilename: String,
    val stagedPath: StagedPath,
)

/**
 * Every resource of [ref] is staged here, and this process has claimed its import: what the import takes, and the only
 * way to it. [captured] is the photo's capture date, which the imported asset is dated at.
 */
class StagedDownload @MintsStagedDownload constructor(
    val ref: AssetRef,
    val captured: CaptureDate,
    val resources: List<StagedResource>,
)

/** [ref] is imported as the library asset [created], its staged bytes perhaps still on disk: what a release takes. */
class ImportedAsset @MintsImportedAsset constructor(val ref: AssetRef, val created: AssetId)

/**
 * A row whose import was never confirmed: an asset WAS created for [ref] — [createdLocalId] is its marker — but the
 * confirmation never arrived, because the process that opened the library's transaction died. Adjudicated, never
 * imported again.
 */
class UnconfirmedImport(val ref: AssetRef, val createdLocalId: AssetId)

/**
 * What a library import reported. [Failed.consumedResources] is a platform fact the adapter observes: the library took
 * the files at ingest, so nothing is left to retry from. [Failed.placeholder] is the marker the change block reported
 * before the change failed, `null` when it reported none.
 */
sealed interface ImportReport {
    class Imported(val created: AssetId) : ImportReport

    class Failed(val message: String, val consumedResources: Boolean, val placeholder: AssetId?) : ImportReport
}

/** What a staged resource is to the library: its live motion a paired video, its primary a photo or a video by type. */
enum class ReceivedMedia {
    PHOTO,
    VIDEO,
    PAIRED_VIDEO,

    /** Nothing the library takes — an unknown type, or a role no build names. */
    NONE,
    ;

    companion object {
        fun of(role: ResourceRole, contentType: String): ReceivedMedia = when (role) {
            ResourceRole.LIVE -> PAIRED_VIDEO
            ResourceRole.PRIMARY -> primary(contentType)
        }

        private fun primary(contentType: String): ReceivedMedia = when {
            contentType.startsWith("image/") -> PHOTO
            contentType.startsWith("video/") -> VIDEO
            else -> NONE
        }
    }
}

/** What an import's report means for its row. */
sealed interface ImportVerdict {
    /** The asset was created: the row is imported, and [created] is its suppression handle. */
    class Imported(val created: AssetId) : ImportVerdict

    /** The library took the bytes and created nothing: the photo never arrives, and nothing can retry it. */
    class Unimportable(val reason: String) : ImportVerdict

    /** Nothing was taken: a later pass imports it again from its staged bytes. */
    class Deferred(val reason: String) : ImportVerdict

    companion object {
        fun of(report: ImportReport): ImportVerdict = when (report) {
            is ImportReport.Imported -> Imported(report.created)
            is ImportReport.Failed -> failed(report)
        }

        private fun failed(report: ImportReport.Failed): ImportVerdict = when (report.consumedResources) {
            true -> Unimportable(report.message)
            false -> Deferred(report.message)
        }
    }
}

/**
 * Whether an asset this device created is still in the library, as the library can see it under the current grant.
 * Only a view of the whole library may say [ABSENT]: a miss anywhere else means "not visible from here".
 */
enum class AssetPresence {
    PRESENT,
    ABSENT,
    UNKNOWN,
    ;

    companion object {
        /** What a read of the WHOLE library says of an asset it did, or did not, [find]. */
        fun inLibrary(find: Boolean): AssetPresence = when (find) {
            true -> PRESENT
            false -> ABSENT
        }

        /** What a partial grant's selection says of an asset it did, or did not, [find]: a miss is never absence. */
        fun inSelection(find: Boolean): AssetPresence = when (find) {
            true -> PRESENT
            false -> UNKNOWN
        }
    }
}

/** What the staged bytes of an unconfirmed row say — the second oracle, the one the library cannot be. */
sealed interface StagedEvidence {
    /** The row records no staged resource: nothing to reason from. */
    data object NoneRecorded : StagedEvidence

    /** A recorded file is gone: the library took it, which it does only while creating an asset. */
    data object Consumed : StagedEvidence

    /** Every recorded file is still there: no creation took them. */
    data object AllPresent : StagedEvidence

    companion object {
        /**
         * What [recorded] staged files say when [missing] of them are gone. Any one missing is as much evidence as all:
         * the library ingests an asset's resources one by one.
         */
        fun of(recorded: Int, missing: Int): StagedEvidence = when {
            recorded == 0 -> NoneRecorded
            missing > 0 -> Consumed
            else -> AllPresent
        }
    }
}

/**
 * What adjudication does with an unconfirmed row ([UnconfirmedImport]), given what the library answered, whether this
 * process is importing the row right now, and what its staged bytes say (decision records
 * `changes/archive/2026-08-09-gate-absence-on-unreported-imports`, `2026-08-29-settle-imports-on-consumed-bytes`).
 */
sealed interface InterruptedImportVerdict {
    /** A creation was submitted: settle the row against the marker it already holds. */
    data object Settle : InterruptedImportVerdict

    /** Nothing was created: clear the marker, so the row is imported again. */
    data object Clear : InterruptedImportVerdict

    /** No evidence either way: change nothing, and ask again in the next process. */
    data object Leave : InterruptedImportVerdict

    companion object {
        fun of(presence: AssetPresence, importing: Boolean, staged: StagedEvidence): InterruptedImportVerdict =
            when (presence) {
                AssetPresence.PRESENT -> Settle
                AssetPresence.ABSENT -> absent(importing, staged)
                AssetPresence.UNKNOWN -> Leave
            }

        /**
         * "Absent" is honest and WRONG to act on while the row's import runs here: the library answers about committed
         * state, so it cannot see an asset whose change block has not committed (Bugsink SNAPSYNC-9).
         */
        private fun absent(importing: Boolean, staged: StagedEvidence): InterruptedImportVerdict = when (importing) {
            true -> Leave
            false -> fromBytes(staged)
        }

        private fun fromBytes(staged: StagedEvidence): InterruptedImportVerdict = when (staged) {
            StagedEvidence.NoneRecorded -> Leave
            StagedEvidence.Consumed -> Settle
            StagedEvidence.AllPresent -> Clear
        }
    }
}
