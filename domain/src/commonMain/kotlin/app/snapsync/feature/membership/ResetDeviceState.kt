package app.snapsync.feature.membership

import app.snapsync.ports.ConfigStore
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.StagedBytes
import co.touchlab.kermit.Logger

/**
 * Void this device's durable sync state (the `SNAPSYNC_RESET_STATE` dev/test launch trigger, capability
 * `ios-app-shell`), so a build pointed at a **different backend** starts from nothing.
 *
 * ## Why this is not just "leave harder"
 *
 * [LeaveEvent] **deliberately keeps** the ledger, and is right to: the ledger key is the bare filename
 * with no event scoping, leaving does not remove this device's bytes from its storage partition, so a
 * `COMPLETED` row stays *true* across a leave (`sync-ledger`, "Event-independent key"). Wiping it there
 * would force a re-upload of everything already stored.
 *
 * That reasoning holds for exactly one backend. Point the build at another and every premise inverts:
 * the bytes are on the backend you left behind, while the ledger still says `COMPLETED` — so the device
 * uploads **nothing**, with no error, no failed request, and no log line. It is indistinguishable from a
 * broken rig, and it bites in both directions (going to a local backend and coming back).
 *
 * ## Why all four, and why not the fifth
 *
 * Clearing the ledger alone is **not enough**, which is the non-obvious half. Discovery's cursor is a
 * persisted change token; with it retained the next cycle observes no changes and enumerates nothing, so
 * a ledger wipe on its own still uploads zero. [clearDiscoveryCursor] restores full re-enumeration — the
 * `DiscoveryStore` degradation ("a cold start with no stored token re-enumerates the whole library") is
 * precisely the behaviour wanted here. It arrives as the same injected effect `ReconfigureEvent` already
 * uses to invalidate the shared cursor, so both callers go through one surface.
 *
 * The config is cleared **locally only**: this issues no backend `DELETE`, because the event belongs to
 * the backend being left behind (now unreachable at the baked host) and the newly baked backend never
 * knew this device. That is the one behavioural difference from [LeaveEvent], and it is why pairing a
 * reset with `SNAPSYNC_LEAVE` is unnecessary rather than complementary — after a reset the device is
 * unjoined, so a leave in the same launch is a no-op instead of a `DELETE` aimed at the wrong backend.
 *
 * Download rows are dropped **non-terminally only** ([DownloadStore.pruneNonTerminal], the same verb
 * leave/switch use). Rows carrying a `createdLocalId` are **retained** — whether or not they reached a
 * terminal state. That marker is what the upload path reads to suppress re-uploading a downloaded asset,
 * so discarding a row that holds one makes the device re-upload the photo it imported: the echo the
 * download store exists to prevent. The invariant is *handle-carrying* rows are permanent, not *terminal*
 * rows — an import interrupted between its commit and its confirmation holds a marker while still
 * looking non-terminal, and it is exactly the row a state-based prune would destroy.
 *
 * The staged bytes of the rows it does drop are released first: after the prune their paths are gone with
 * the rows, and the files are stranded with nothing referencing them.
 *
 * The attestation credential is **untouched**. A token minted by another backend is rejected there with
 * a `401`, and `DeviceAttestation.rejected()` already drops it and re-attests — so crossing backends
 * heals it with no operator action, and clearing it here would only cost an extra round trip.
 *
 * Best-effort per step, like [LeaveEvent]: a failing step is logged and the rest still run, because a
 * partial reset is strictly better than an aborted one (whatever was cleared cannot mislead).
 */
class ResetDeviceState(
    private val config: ConfigStore,
    private val ledger: LedgerStore,
    private val downloads: DownloadStore,
    private val stagedBytes: StagedBytes = StagedBytes.None,
    private val clearDiscoveryCursor: () -> Unit,
) {
    private val log = Logger.withTag("ResetDeviceState")

    suspend fun reset() {
        // Read before pruning: this is the number the operator needs to see, and it is the count that
        // SURVIVES — the log line's job is to make "imported rows were kept" verifiable, not assumed.
        val keptImported = runCatching { downloads.importedCount() }.getOrNull()

        step("clear ledger") { ledger.clear() }
        // Without this the ledger clear achieves nothing: no change token means no enumeration.
        step("clear discovery cursor") { clearDiscoveryCursor() }
        step("release prunable staged bytes") { stagedBytes.release(downloads.stagedPathsOfPrunableAssets()) }
        step("prune non-terminal downloads") { downloads.pruneNonTerminal() }
        // Local only — no backend is notified. See the class doc.
        step("clear config") { config.clear() }

        log.i {
            "reset: ledger + discovery cursor + config cleared, non-terminal downloads pruned " +
                "(${keptImported ?: "?"} imported row(s) kept)"
        }
    }

    private suspend inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            log.e(e) { "reset step failed: $name" }
        }
    }
}
