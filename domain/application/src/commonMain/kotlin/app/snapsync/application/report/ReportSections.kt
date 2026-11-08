package app.snapsync.application.report

import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.logs.DeviceLogTails
import app.snapsync.data.logs.LoggingProcess
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.data.trust.PresentedCertificate
import app.snapsync.data.trust.PresentedChain
import app.snapsync.data.trust.RefusalFacts
import app.snapsync.data.trust.RefusalRead
import app.snapsync.data.upload.OsUploader
import app.snapsync.data.upload.ProgressRead
import app.snapsync.data.upload.UploadProgress
import app.snapsync.data.upload.UploaderAdmission
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.Fact
import app.snapsync.domain.report.ReportContext
import app.snapsync.domain.report.ReportedEnvironment
import app.snapsync.domain.upload.AssetProgress
import app.snapsync.format.PhotoGrantText
import app.snapsync.format.TimestampText

/** The membership held, `null` while none is or it cannot be read now. */
private fun MembershipRepository.joined(): Membership? = (state.value as? MembershipRead.Joined)?.membership

/**
 * The facts a log tail may not hold — the boot lines that carry most of them roll off first. What the user was looking
 * at leads: the surfaces that are screen-local by design (the reconfigure sheet, a pending switch, which join phase)
 * reach neither the ledger nor a log line, so this is the only place they appear.
 */
class ReportState(
    private val environment: ReportedEnvironment,
    private val membership: MembershipRepository,
    private val grant: GrantRead,
    /** The upload facts: whether the extension is registrable, and the app uploader's admission (both may run). */
    private val osUploader: OsUploader,
    private val appAdmission: UploaderAdmission,
    /** The latest refused attestation, carried only by a report the app offered for a refused phone. */
    private val refusal: RefusalRead,
) {
    fun section(context: ReportContext): Map<String, String> = buildMap {
        put("screen", context.screen)
        putAll(context.shown)
        putAll(environment.fields())
        put("extension_registrable", osUploader.admission().name)
        put("app_admission", appAdmission.admission().name)
        putAll(verification(context.verification))
        put("photo_permission", PhotoGrantText.report(grant.grant()))
        putAll(membershipFacts(membership.joined()))
    }

    /** Which event, how it was configured, and what window it shares — its id rides in the `snapsync` section. */
    private fun membershipFacts(joined: Membership?): Map<String, String> = when (joined) {
        null -> mapOf("joined" to "false")
        else -> mapOf(
            "joined" to "true",
            "event_name" to joined.event.name.text,
            "direction" to joined.share.direction.name,
            "shares_from" to TimestampText.format(joined.share.range.from.at),
            "shares_until" to TimestampText.format(joined.share.range.until.at),
            "save_to_album" to (joined.share.album == AlbumChoice.SaveToAlbum).toString(),
        )
    }

    /** A report offered for a refused phone carries its verification; any other carries none. */
    private fun verification(offered: Boolean): Map<String, String> = when (offered) {
        true -> refused(refusal.refusalFacts())
        false -> emptyMap()
    }

    /**
     * The service's answer as it named it, and the certificates the phone presented above its own key. A phone whose
     * latest attestation was not refused says so rather than leaving the reader to guess.
     */
    private fun refused(facts: RefusalFacts?): Map<String, String> = when (facts) {
        null -> mapOf("attest_failure" to "none recorded")
        else -> mapOf("attest_failure" to facts.named()) + chain(facts.chain)
    }

    /** The service's reason as it named it, with its diagnostic detail where it gave one. */
    private fun RefusalFacts.named(): String = when (detail) {
        null -> reason.wireName
        else -> "${reason.wireName} ($detail)"
    }

    /** Names, validity and key type of each certificate, and the root key's fingerprint — never a certificate. */
    private fun chain(chain: PresentedChain?): Map<String, String> = when (chain) {
        null -> emptyMap()
        else -> mapOf(
            "attest_chain" to chain.certificates.withIndex().joinToString("\n") { (i, c) -> c.line(i + 1) },
            "attest_root_key_sha256" to chain.rootKeySha256,
        )
    }

    private fun PresentedCertificate.line(position: Int): String =
        "[$position] subject=$subject issuer=$issuer valid=$notBefore..$notAfter key=$key"
}

/**
 * Counts only — no row lists. Completed rows are only readable by loading all of them, and the backlog is unbounded
 * exactly on the stuck device worth reporting from (4,000 outstanding rows is ~400 KB, over half the log budget) while
 * carrying neither state nor attempt. The log says *why*; the counts say *how many*.
 *
 * Each store is read in one round-trip, so a report states one moment of each. The units are labelled because the two
 * count different things: the ledger counts **photos** (one with any outstanding resource is pending), while the log
 * speaks of resource **rows**.
 */
class ReportLedger(
    private val progress: UploadProgress,
    private val downloads: ReceivingCounts,
    private val membership: MembershipRepository,
) {
    fun section(): Map<String, String> = buildMap {
        val photos = ownProgress()
        put("photos_pending", photos) { all -> all.count { it != AssetProgress.DONE }.toString() }
        put("photos_completed", photos) { all -> all.count { it == AssetProgress.DONE }.toString() }
        val counts = when (val read = downloads.census()) {
            is DownloadRead.Ok -> Fact.Known(read.value)
            is DownloadRead.Unavailable -> Fact.Failed("the download store cannot be read: ${read.detail}")
        }
        put("downloads_imported", counts) { it.imported.toString() }
        put("downloads_assets", counts) { it.stillArriving.toString() }
        put("downloads_in_flight", counts) { it.inFlight.toString() }
    }

    /** Per photo of the joined event, how far it has got — none while nothing is joined; failed when unreadable. */
    private fun ownProgress(): Fact<Collection<AssetProgress>> = when (val joined = membership.joined()) {
        null -> Fact.Known(emptyList())
        else -> when (val read = progress.progress(joined.event.id)) {
            is ProgressRead.Read -> Fact.Known(read.photos.values)
            is ProgressRead.Unavailable -> Fact.Failed("the upload ledger cannot be read: ${read.detail}")
        }
    }
}

/**
 * Both device logs' tails, sharing [budgetBytes] **greedily**: the extension's takes at most half, and the app's what
 * it leaves. The common device's app log is far larger than its extension's (which runs in short bursts, or on
 * iOS 18–26.0 not at all), so a fixed split would throw most of the budget away there.
 */
class ReportLogs(private val tails: DeviceLogTails, private val budgetBytes: Int = DiagnosticDump.LOG_BUDGET_BYTES) {
    /** The app's tail, then the extension's. */
    fun read(): Pair<String, String> {
        val extension = text(tails.tail(LoggingProcess.EXTENSION, budgetBytes / 2))
        val app = text(tails.tail(LoggingProcess.APP, budgetBytes - extension.encodeToByteArray().size))
        return app to extension
    }

    /** A log as the report carries it: its tail, nothing where there is none, or why it could not be read. */
    private fun text(tail: Fact<String>): String = when (tail) {
        is Fact.Known -> tail.value
        Fact.Unsupported -> ""
        is Fact.Failed -> tail.shown
    }
}

/**
 * The one rendering of a [Fact] into a section: a known value as its label, an unsupported one LEFT OUT (the platform
 * has no such thing, and the report's `os` says which it was), a failed one as `failed (<reason>)` — so a reading that
 * could not be taken is never mistaken for one that does not exist.
 */
internal fun <T> MutableMap<String, String>.put(key: String, fact: Fact<T>, label: (T) -> String) {
    when (fact) {
        is Fact.Known -> put(key, label(fact.value))
        Fact.Unsupported -> Unit
        is Fact.Failed -> put(key, fact.shown)
    }
}
