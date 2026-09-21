package app.snapsync.feature.membership

import app.snapsync.ports.ConfigStore
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.LedgerStore
import co.touchlab.kermit.Logger

/**
 * Void this device's durable sync state (the control channel's `POST /device/reset`, capability
 * `ios-app-shell`), so a build pointed at a **different backend** starts from nothing.
 *
 * ## What it adds to a leave
 *
 * Very little, now. The leave command already prunes non-terminal download rows
 * (`DownloadController.onLeaveOrSwitch`) and, since `changes/join-loads-leave-clears`, clears the upload
 * ledger too: the ledger is the current membership's share set (`sync-ledger`), and the next join reloads
 * it from the backend's stored-file listing. The one remaining difference is that a reset **notifies no
 * backend** (below).
 *
 * It exists for the case a leave's backend notify gets wrong: a build pointed at a **different backend**.
 * Point it at another and a stale `COMPLETED` row would say the bytes are stored while they sit on the
 * backend you left behind — the device uploads **nothing**, with no error, no failed request, and no log
 * line. The leave-and-rejoin path would clear it too, but by `DELETE`-ing a membership on a backend this
 * build can no longer reach.
 *
 * ## Why all three, and why not the fourth
 *
 * Clearing the ledger is enough to make the next cycle upload everything in scope: every upload walk is a
 * full enumeration (there is no discovery cursor whose retained change token would make it enumerate
 * nothing), and a walk reads every asset the ledger holds no row for.
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
 * The staged bytes of the rows it does drop are freed by that same step: the prune returns the paths it
 * stranded, so there is no second read at a second instant for a concurrent marker write to slip between.
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
    /**
     * The download half of the reset — the non-terminal prune and the release of the bytes it strands,
     * together.
     *
     * Injected as ONE effect rather than performed here, because it must run under the download
     * controller's lock: a ref is claimed under that lock before its import's change block runs, and a
     * reset that merely reads a snapshot of what is claimed leaves a window for a claim in between —
     * whose row is then pruned, so the marker write lands on nothing and the created asset is uploaded
     * back into the event (capability `download-store`). This feature cannot take that lock without
     * reaching for its sibling, so the composition passes the critical section instead of the value.
     *
     * Required, with no default: a no-op default would make a reset that quietly prunes nothing look
     * exactly like one that worked.
     */
    private val resetDownloads: suspend () -> Unit,
) {
    private val log = Logger.withTag("ResetDeviceState")

    suspend fun reset() {
        // Read before pruning: this is the number the operator needs to see, and it is the count that
        // SURVIVES — the log line's job is to make "imported rows were kept" verifiable, not assumed.
        val keptImported = runCatching { downloads.counts().imported }.getOrNull()

        step("clear ledger") { ledger.clear() }
        step("prune non-terminal downloads (and free the bytes it strands)") { resetDownloads() }
        // Local only — no backend is notified. See the class doc.
        step("clear config") { config.clear() }

        log.i {
            "reset: ledger + config cleared, non-terminal downloads pruned " +
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
