package app.snapsync.domain.report

import app.snapsync.domain.process.MemoryFootprint
import app.snapsync.domain.sharing.PhotoGrant

/**
 * The build, OS and device a bug report's state section names — every one a constant of the running build, read from
 * its `BuildInfo` once by the composition.
 *
 * [uploaders] names which uploaders this OS carries — a constant of the build, never which one runs: both may.
 * [uploadBase] is the same baked host the boot banner names, and [reporterEnvironment] tells a deliberately
 * DSN-injected development build from a production one — without it, a report from the on-device verification path
 * reads as a real member's.
 */
class ReportedEnvironment(
    /** The build's version line and number, as the app menu names it. */
    val build: BuildLabel,
    val osVersion: String,
    val deviceModel: String,
    val uploaders: String,
    val uploadBase: String,
    val reporterEnvironment: String,
) {
    /** The section's fields, under the keys every report has carried them by. */
    fun fields(): Map<String, String> = mapOf(
        "app_version" to build.version,
        "build" to build.buildNumber,
        "os" to osVersion,
        "device" to deviceModel,
        "uploaders_carried" to uploaders,
        "upload_base" to uploadBase,
        "reporter_environment" to reporterEnvironment,
    )
}

/** The bug report's readings of facts only the app holds — what each says, decided here, not where it is read. */
object ReportFacts {
    /**
     * How many photos a partial grant's selection holds — **photos**, not resources: a Live Photo has several. Only a
     * partial grant has a selection; under any other the fact is unsupported and the report leaves it out. A selection
     * not read yet is failed, never zero: an empty selection and an unread one are different reports.
     */
    fun selectionPhotos(grant: PhotoGrant, selected: Int?): Fact<Int> = when {
        grant != PhotoGrant.LIMITED -> Fact.Unsupported
        selected == null -> Fact.Failed("the selection has not been read yet")
        else -> Fact.Known(selected)
    }

    /** A process's footprint in whole megabytes; unsupported where the platform's is not read. */
    fun megabytes(footprint: MemoryFootprint?): Fact<Long> = when (footprint) {
        null -> Fact.Unsupported
        else -> Fact.Known(footprint.footprintBytes / BYTES_PER_MEGABYTE)
    }

    private const val BYTES_PER_MEGABYTE = 1_048_576L
}
