package app.snapsync.feature.membership

import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.clampToFloor
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import co.touchlab.kermit.Logger

/**
 * The in-place **reconfigure** use-case (capability `reconfigure-membership`): a joined member changes
 * the three participation settings they picked at join — the capture-date cutoff, the direction, and the
 * album opt-in — **without leaving**. It is the fourth writer of the one-writer membership config
 * (join/provision saves it, leave clears it, [EventName] refreshes the name, this rewrites the
 * participation fields), and it mirrors [EventName.storeEventNameIfChanged]'s discipline: read the current
 * config, guard the `eventId` still matches, and save the **whole** object with only the intended fields
 * replaced (`copy(direction, minPhotoDate, saveToAlbum)`). It never enters `JoinEvent`, so the
 * `AlreadyJoined` short-circuit and the enrollment path are untouched, and the ledger / enrollment /
 * device identity are all preserved. `direction` is a device-local gate, so a reconfigure reaches nothing
 * on the backend.
 *
 * The **cutoff** is re-clamped to the immutable `startsAt` floor (`max(chosen, startsAt)`) exactly as at
 * join, so a reconfigure can never lower a membership below the event's start.
 *
 * On a successful save it re-drives the same provision-side effects a join performs, so a change takes
 * effect immediately rather than waiting for the OS's next scheduled cycle — but with a deliberate
 * **asymmetry** between the two arms when a direction is turned **off** (`reconfigure-membership`):
 *
 * - **Upload**: [armUpload] is called **only when the new direction includes upload** — starting (or
 *   re-selecting) the producer. When upload is turned **off**, the producer is deliberately left running:
 *   the per-cycle gate (`Contribution.None`, capability `photo-selection-policy`) stops new work while an
 *   in-flight upload **drains**, and the byte URL is device-partitioned and event-independent, so
 *   cancelling one would only re-upload identical bytes.
 * - **Download**: [startDownloads] runs a reconcile when download is included; otherwise [cancelDownloads]
 *   **cancels in-flight downloads**, so foreign photos stop arriving once the member turns receive off.
 *
 * All side effects are injected as `model`-typed lambdas built in `compose/` (the arm/album/download seams
 * live in their own features; this use-case stays pure `commonMain` and constructs no platform type), and
 * each runs best-effort under [step]: a failing effect is logged and the rest still run.
 */
class ReconfigureEvent(
    private val configSource: ConfigSource,
    private val store: ConfigStore,
    private val refreshStatus: suspend () -> Unit,
    private val armUpload: suspend () -> Unit,
    private val ensureAlbum: suspend (EventConfig) -> Unit,
    private val startDownloads: suspend (eventId: String) -> Unit,
    private val cancelDownloads: suspend () -> Unit,
    /**
     * Invalidate the persisted discovery cursor (capability `reconfigure-membership`). Called **only when
     * the cutoff is lowered**, so the next cycle re-enumerates the whole library at the new (earlier)
     * cutoff and shares the newly-in-scope older photos — on **both** upload tiers. Without it the
     * forward-only change cursor never re-visits those unchanged older assets (the iOS 18–26.0 silent
     * no-backfill this fixes). The ledger's `COMPLETED` rows still suppress re-upload of already-shared
     * photos, so a re-enumeration costs a walk, not a re-upload. Built in `compose/` over the shared
     * App-Group `DiscoveryStore`; a no-op default keeps every other composition unaffected.
     */
    private val clearDiscoveryCursor: () -> Unit = {},
) {
    private val log = Logger.withTag("ReconfigureEvent")

    /**
     * Apply a reconfigure to the currently-joined membership. [eventId] is the event the surface was
     * opened for: if the current config is absent or names a **different** event (a switch landed while
     * the surface was open), this is a **no-op** — the surface's stale values must not overwrite a
     * different membership.
     */
    suspend fun reconfigure(
        eventId: String,
        direction: Direction,
        chosenCutoff: String,
        saveToAlbum: Boolean,
    ) {
        val current = configSource.config.value
        if (current == null || current.eventId != eventId) return
        val newCfg = current.copy(
            direction = direction,
            minPhotoDate = clampToFloor(chosenCutoff, current.startsAt),
            saveToAlbum = saveToAlbum,
        )
        // Persist the WHOLE config with only the three participation fields changed (one-writer, in place).
        step("save config") { store.save(newCfg) }
        // A LOWERED cutoff widens scope: invalidate the forward-only discovery cursor BEFORE the arm kicks
        // the next cycle, so it re-enumerates at the new cutoff and back-shares the newly-in-scope older
        // photos — tier-agnostically. Cutoffs are canonical `…Z`, so a lexicographic `<` is chronological.
        // Raising the cutoff needs no re-enumeration (nothing new comes into scope) and un-shares nothing.
        if (newCfg.minPhotoDate < current.minPhotoDate) {
            step("clear discovery cursor") { clearDiscoveryCursor() }
        }
        // Re-enumerate the own total + re-read completeness so the status reflects a changed cutoff/direction.
        step("refresh status") { refreshStatus() }
        // Upload arm: START on enable; on disable leave the producer to drain (see class doc).
        if (direction.includesUpload) step("arm upload") { armUpload() }
        // Event album: an unconditional call carrying the new config; the granted/opt-in gate is the
        // coordinator's own leading guard (capability `event-album`).
        step("ensure album") { ensureAlbum(newCfg) }
        // Download arm: reconcile on enable; cancel in-flight downloads on disable.
        if (direction.includesDownload) {
            step("start downloads") { startDownloads(newCfg.eventId) }
        } else {
            step("cancel downloads") { cancelDownloads() }
        }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            // Best-effort: a failed effect never aborts the reconfigure (the config save already landed).
            log.e(e) { "reconfigure step failed: $name" }
        }
    }
}
