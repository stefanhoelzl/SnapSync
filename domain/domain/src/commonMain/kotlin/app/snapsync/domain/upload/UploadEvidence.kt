package app.snapsync.domain.upload

import kotlin.jvm.JvmInline

// The upload's evidence (`docs/patterns.md`, "Evidence types"): a ledger transition that records what the platform did
// requires the platform's word for it, minted where that word is translated — once, for both transfer tiers. Never
// stored, never serialized, never a data class.

/** Only the transfer tiers' translation of a create's answer mints a [CreatedJob]. */
@RequiresOptIn("Only the platform's answer to a create says a job exists.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsCreatedJob

/** Only the transfer tiers' translation of an ended job mints a [ReportedFailure]. */
@RequiresOptIn("Only the platform's report of an ended job says an upload failed.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsReportedFailure

/** The platform accepted a job uploading [key]: its row may be recorded as requested (write-after-act). */
@JvmInline
value class CreatedJob @MintsCreatedJob constructor(val key: UploadKey)

/** The platform reported [key]'s job ended without its bytes landing: its row may return to needing a job. */
@JvmInline
value class ReportedFailure @MintsReportedFailure constructor(val key: UploadKey)
