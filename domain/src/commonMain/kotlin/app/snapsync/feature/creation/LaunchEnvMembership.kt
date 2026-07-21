package app.snapsync.feature.creation

import app.snapsync.model.CreateDecodeResult
import app.snapsync.model.decodeCreateDirective
import co.touchlab.kermit.Logger

/**
 * Applies the **membership-mutating launch-env triggers** in the fixed order `leave → create →
 * event-link` (capability `ios-app-shell`). It exists so the ordering, the `SNAPSYNC_CREATE_EVENT`
 * decode, and its error-mapping — all *decisions* the shell may not hold (`architecture-guards`, "The
 * shell gates": branching, ordering, and error-mapping belong in tested `:domain`) — live here, under
 * `commonTest` on both JVM and `iosSimulatorArm64`. The untested `:app:ios` shell only forwards the raw
 * directive strings and its own `onOpenUrl` join entry; this class owns every branch.
 *
 * The steps are **sequential** — each awaited before the next — so a later step observes the state the
 * earlier one produced (a leave clears the membership before create/join read it). Every port/platform
 * touch arrives as an injected effect: [leave] (the leave command), [ensureAttested] (best-effort token
 * refresh so the attest-gated create is not lost to a cold-launch 401), and [openUrl] (the join gate's
 * entry) supplied to [run] because it is the shell's own surface. Create is delegated to [headlessCreate]
 * (which owns the mint + the outcome branch), reusing the existing tested join path for its `autoJoin`
 * half via [openUrl]; a malformed `SNAPSYNC_CREATE_EVENT` is logged and skipped.
 *
 * It is not a `flow/` because the flow grammar admits no "apply N optional triggers in order" shape; it
 * mirrors [HeadlessCreate]'s own posture — a tested feature that sequences over injected effects.
 */
class LaunchEnvMembership(
    private val headlessCreate: HeadlessCreate,
    private val log: Logger,
    private val leave: suspend () -> Unit,
    private val ensureAttested: suspend () -> Unit,
) {
    /**
     * Apply the triggers present this launch, in order. A `false`/`null`/blank argument contributes
     * nothing. [createEvent] is the raw `base64url(JSON)` `SNAPSYNC_CREATE_EVENT` value (decoded here);
     * [eventLink] is the raw `SNAPSYNC_EVENT_LINK` URL; [openUrl] is the shell's join-gate entry (used
     * for the `autoJoin` create's synthesized link and for the raw event link).
     */
    suspend fun run(
        leaveRequested: Boolean,
        createEvent: String?,
        eventLink: String?,
        openUrl: (String) -> Unit,
    ) {
        if (leaveRequested) leave()
        if (createEvent != null) {
            when (val decoded = decodeCreateDirective(createEvent)) {
                is CreateDecodeResult.Success -> {
                    ensureAttested()
                    headlessCreate.run(decoded.payload, openUrl)
                }
                is CreateDecodeResult.Failure ->
                    log.w { "SNAPSYNC_CREATE_EVENT rejected: ${decoded.reason}" }
            }
        }
        if (eventLink != null) openUrl(eventLink)
    }
}
