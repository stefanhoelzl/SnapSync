package app.snapsync.data.receiving

import app.snapsync.data.backend.BackendExchange
import app.snapsync.data.backend.EntryPointLabel
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.refOf
import app.snapsync.data.receiving.MarkerStatements.clear
import app.snapsync.data.receiving.RowStatements.pending
import app.snapsync.data.time.WallClock
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.FetchVerdict
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.domain.receiving.StagingFolder
import app.snapsync.domain.receiving.TransferSlots
import app.snapsync.format.ResourceRoleText
import app.snapsync.format.TimestampText
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.Download
import app.snapsync.ports.StartResult
import app.snapsync.wire.api.Routes

/**
 * What a transfer's opaque tag says — the one field the platform keeps across a relaunch, so a relaunched process that
 * never started a transfer still knows what it fetched: the photo, its resource, and the event whose folder its bytes
 * stage under. A tag a build before change `event-scoped-local-state` started has three fields and names no event.
 */
class TransferTag(val ref: AssetRef, val resourceKey: String, val folder: StagingFolder) {
    /** The role its resource plays in its photo, as its key names it. */
    val role: ResourceRole get() = ResourceRoleText.ofKey(resourceKey)

    /** Where this transfer's bytes are staged. */
    val stagedAt: StagedPath get() = StagedPath.of(folder, ref, resourceKey)

    /** The tag's text, as the platform keeps it. */
    val text: String
        get() = listOf(ref.sourceDeviceId.value, ref.sourceAssetId.value, resourceKey, folderText()).joinToString(SEP)

    private fun folderText(): String = when (folder) {
        is StagingFolder.Event -> folder.name
        StagingFolder.Legacy -> ""
    }

    companion object {
        /** A newline cannot occur in a device id, a sanitized key or an event id. */
        private const val SEP = "\n"
        private const val DEVICE = 0
        private const val ASSET = 1
        private const val KEY = 2
        private const val EVENT = 3

        /** A tag names its event as a fourth field; one a build before `event-scoped-local-state` started has three. */
        private val FIELDS = KEY + 1..EVENT + 1

        /** The tag [text] spells, or `null` for a text no build wrote. */
        fun parse(text: String): TransferTag? = text.split(SEP).takeIf { it.size in FIELDS }?.let(::fields)

        private fun fields(parts: List<String>): TransferTag? = refOf(parts[DEVICE], parts[ASSET]).map { ref ->
            TransferTag(ref, parts[KEY], StagingFolder.of(parts.getOrElse(EVENT) { "" }))
        }.firstOrNull()
    }
}

/** One not-yet-staged resource to download: what names it, and where to fetch it. */
class PendingResource(val tag: TransferTag, val url: String)

/**
 * The platform's background downloads, as receiving keeps them: a queue drained through a bounded window
 * ([TransferSlots]) into the [Download] port. Not thread-safe on its own — the download store calls it only under its
 * own lock ([DownloadStore]), so the queue moves with the rows that say what is pending.
 *
 * The queue is keyed by tag, like the window: every read hands over its WHOLE pending snapshot, so a key is queued at
 * most once and never both queued and running. An appended queue doubled a backlog on a second read (SE2, S2 bench,
 * 2026-09-24: 136 transfers for 100 resources), and the stale copies held the window.
 */
class DownloadTransfers(
    private val download: Download,
    /** The networks a download started NOW may use — read as each one starts. */
    private val network: MobileDataStore,
    /** The version every request declares — the download redirect's too (`downloadResource`). */
    private val exchange: BackendExchange,
    /** When a download is queued, for the delivery record. */
    private val clock: WallClock,
) {
    private val queued = LinkedHashMap<String, PendingResource>()
    private val queuedBy = Routes.DownloadResource.HEADER_X_SNAPSYNC_QUEUED_BY
    private val running = LinkedHashSet<String>()

    /** Queue [pending], keeping a queued one's place with its fresher address, and start what the window holds. */
    internal suspend fun enqueue(pending: List<PendingResource>) {
        pending.filterNot { it.tag.text in running }.forEach { queued[it.tag.text] = it }
        pump()
    }

    /** [tag]'s transfer ended — finished, failed or cancelled: its slot is free, and the next queued one starts. */
    internal suspend fun freed(tag: String) {
        running.remove(tag)
        pump()
    }

    /**
     * Drop the queue and cancel EVERY transfer the session holds — this process's and any a relaunched process
     * inherited — and return once each is cancelled. Each still reports its end, which frees a slot already gone.
     */
    internal suspend fun cancelAll() {
        queued.clear()
        running.clear()
        download.cancelAll()
    }

    /** Start what the window holds, in the order queued; one that does not start leaves its slot to the next. */
    private suspend fun pump() {
        queued.entries.toList().asSequence().takeWhile {
            running.size < TransferSlots.MAX
        }.forEach { startNext(it.key, it.value) }
    }

    private suspend fun startNext(tag: String, next: PendingResource) {
        queued.remove(tag)
        started(next, FetchVerdict.of(next.url))
    }

    /** An unfetchable address stays pending, not failed: a later read plans it again. */
    private suspend fun started(next: PendingResource, verdict: FetchVerdict) = when (verdict) {
        FetchVerdict.Fetchable -> started(next, start(next))
        FetchVerdict.Unfetchable ->
            log.w("an unfetchable download address is skipped", "resource" of next.tag.resourceKey)
    }

    private suspend fun start(next: PendingResource): StartResult =
        download.start(next.url, exchange.transferHeaders() + queueing(), next.tag.text, network.transferNetwork())

    /**
     * The delivery record's facts of a download's start (decision record `changes/delivery-record`, D2): when it is
     * handed to the transfer service and the entry point that did so — the session fixes the headers now.
     */
    private suspend fun queueing(): Map<String, String> =
        mapOf(Routes.DownloadResource.HEADER_X_SNAPSYNC_QUEUED to TimestampText.format(clock.now())) +
            EntryPointLabel.current()?.let { mapOf(queuedBy to it) }.orEmpty()

    private suspend fun started(next: PendingResource, result: StartResult) = when (result) {
        StartResult.Started -> running.add(next.tag.text).let { }
        StartResult.NotStarted ->
            log.w("a download was not started; it stays pending", "resource" of next.tag.resourceKey)
    }
}

private val log = Log("DownloadTransfers")
