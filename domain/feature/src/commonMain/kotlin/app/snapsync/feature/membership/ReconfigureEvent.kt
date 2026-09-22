package app.snapsync.feature.membership

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.clampToCeiling
import app.snapsync.model.clampToFloor
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import co.touchlab.kermit.Logger

/**
 * The in-place **reconfigure** use-case (capability `reconfigure-membership`): a joined member changes
 * the three participation settings they picked at join — the capture-date cutoff, the direction, and the
 * album opt-in — **without leaving**. It is the fourth writer of the one-writer membership config
 * (join/provision saves it, leave clears it, [MembershipRefresh] reconciles it against fresh details,
 * this rewrites the participation fields), and it mirrors that reconcile's discipline: read the current
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
 * - **Upload**: [armUpload] runs the upload arm's reconfigure transition **whatever the new direction** — a
 *   kick of the app's uploader that never touches the extension's registration and cancels nothing. The
 *   cycle's own selection policy decides what uploads: turned **off**, it admits nothing, so new work stops
 *   while an in-flight upload **drains** (the byte URL is device-partitioned and event-independent, so
 *   cancelling one would only re-upload identical bytes). No direction check here — the policy is the one
 *   (decision record `changes/both-uploaders-active`).
 * - **Download**: [startDownloads] runs a reconcile when download is included; otherwise [cancelDownloads]
 *   **cancels in-flight downloads**, so foreign photos stop arriving once the member turns receive off.
 *
 * After the album is ensured, [gatherAlbum] starts the event album's **gather** (capability `event-album`):
 * placing what the device already holds for the event. On every Save, not only one that turns the album on,
 * because a lowered cutoff or a changed direction changes that set too. The composition backs it with a
 * **detached** launch — the reconfigure command is awaited by Save, and a gather's cost grows with the photos
 * held — and the gather carries its own opt-in/access gate, so the call is unconditional here.
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
    private val gatherAlbum: suspend (EventConfig) -> Unit,
    private val startDownloads: suspend (eventId: String) -> Unit,
    private val cancelDownloads: suspend () -> Unit,
    /**
     * Advance the ledger's manifest version (capability `reconfigure-membership`, "The reconfigure save advances
     * the manifest version after it lands"). This save is the one writer of the policy bounds the device
     * manifest is projected through, and those bounds live outside the ledger, so no trigger sees them change.
     */
    private val bumpManifestVersion: suspend () -> Unit,
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
        chosenCutoff: CaptureCutoff,
        chosenUpper: CaptureCeiling,
        saveToAlbum: Boolean,
    ) {
        val current = configSource.config.value
        if (current == null || current.eventId != eventId) return
        // The upper bound mirrors the cutoff: re-clamp the chosen ceiling to the event's immutable `endsAt`
        // (`min(chosen, endsAt)`). A membership always carries a concrete ceiling now (capability
        // `join-event`), so there is no unbounded case to express — only a legacy config whose `endsAt` has
        // not yet been backfilled has nothing to clamp against, and the member's own choice stands until it
        // does. The clamp can only ever narrow.
        val newMax = current.endsAt?.let { clampToCeiling(chosenUpper, it) } ?: chosenUpper
        val newCfg = current.copy(
            direction = direction,
            minPhotoDate = clampToFloor(chosenCutoff, current.startsAt),
            maxPhotoDate = newMax,
            saveToAlbum = saveToAlbum,
        )
        // Persist the WHOLE config with only the three participation fields changed (one-writer, in place) —
        // THEN advance the manifest version, in the same step so a failed save advances nothing. The order is
        // the correctness argument (decision record `changes/manifest-versions`, D4): the config and the
        // counter live in two stores and cannot share a transaction. Bumped first, a cycle could read the new
        // version and then the OLD config, and publish the old policy under a version nothing later exceeds.
        // Bumped after, a cycle that read the older version is overtaken by the newer one.
        step("save config") {
            store.save(newCfg)
            bumpManifestVersion()
        }
        // A LOWERED cutoff widens scope, and needs nothing from this use-case to take effect: every upload
        // walk is a full enumeration narrowed by the membership's CURRENT policy, so the next cycle's walk
        // already covers the newly-in-scope older photos and back-shares them — tier-agnostically
        // (capability `reconfigure-membership`). There used to be a forward-only discovery cursor here to
        // invalidate; there is no cursor any more.
        // Raising the cutoff brings nothing new into scope. It DOES stop the
        // now-out-of-scope photos, on BOTH sides: the next cycle re-projects the manifest against the new
        // policy AND admits its work source against it, so rows a wider cutoff recorded stop being
        // uploaded rather than draining behind the member's back (capability `photo-selection-policy`).
        // Their ledger rows are untouched, so lowering the cutoff again re-lists them and re-enqueues
        // them without re-uploading a byte.
        // Re-enumerate the own total + re-read completeness so the status reflects a changed cutoff/direction.
        step("refresh status") { refreshStatus() }
        // Upload arm: a kick in either direction; the cycle's policy decides, and nothing is cancelled (class doc).
        step("arm upload") { armUpload() }
        // Event album: an unconditional call carrying the new config; the granted/opt-in gate is the
        // coordinator's own leading guard (capability `event-album`).
        step("ensure album") { ensureAlbum(newCfg) }
        // Then gather what the device already holds into it — after the ensure, which the gather never does
        // itself. Detached by the composition, so Save does not wait on it (capability `event-album`).
        step("gather album") { gatherAlbum(newCfg) }
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
