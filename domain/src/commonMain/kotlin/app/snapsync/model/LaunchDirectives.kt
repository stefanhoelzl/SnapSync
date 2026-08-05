package app.snapsync.model

/**
 * The parsed developer/test **launch-environment** triggers (capability `ios-app-shell`), lifted out
 * of the shell into a pure value so their parsing is tested on JVM **and** the iOS simulator, and so
 * the shell reads each variable exactly once through one typed surface instead of five scattered
 * `NSProcessInfo.environment[...]` reads.
 *
 * Every field is inert in production by construction: a process-environment variable is only
 * injectable via a developer launch (`pymobiledevice3 developer dvt launch --env …` / `simctl launch
 * --env`), so a SpringBoard or TestFlight launch yields [NONE]. There is deliberately **no**
 * compile-time flag distinguishing the build (spec `ios-app-shell`).
 *
 * - [eventLink] — `SNAPSYNC_EVENT_LINK`: a raw `https://…/join#…` URL forwarded through the same
 *   `onOpenUrl` path a scanned QR takes.
 * - [createEvent] — `SNAPSYNC_CREATE_EVENT`: a `base64url(JSON)` [CreateEventPayload] describing an
 *   event to mint headlessly (mint-only, or mint+autoJoin). Decoded by `decodeCreateDirective`.
 * - [leave] — `SNAPSYNC_LEAVE`: presence (any value) triggers leaving the current membership.
 * - [resetState] — `SNAPSYNC_RESET_STATE`: presence (any value) voids this device's durable sync
 *   state, so a build pointed at a DIFFERENT backend starts from nothing. Without it, crossing
 *   backends fails silently in both directions: the ledger key is event-independent and a leave
 *   deliberately keeps it, so `COMPLETED` rows suppress every upload against a backend that does not
 *   hold those bytes — and the discovery cursor suppresses re-enumeration even after a ledger wipe.
 * - [seedPhotos] — `SNAPSYNC_SEED_PHOTOS`: how many tiny 2001-dated assets to seed (walk-cost test).
 * - [seedPolicy] — `SNAPSYNC_SEED_POLICY`: how many hour-ahead assets straddling the 3 MP floor to
 *   seed (selection-policy probe).
 * - [policyProbe] — `SNAPSYNC_POLICY_PROBE`: a cutoff string against which to run the real own-device
 *   status refresh with no membership.
 * - [forgeState] — `SNAPSYNC_FORGE_STATE`: the name of a forge state to render for a marketing
 *   screenshot (recognition is presentation's, applied by [resolveComposition]'s `isForgeState`).
 * - [forceUrlSessionUpload] — `SNAPSYNC_FORCE_URLSESSION_UPLOAD`: force the app-driven URLSession
 *   upload tier even on iOS ≥26.1 (device-testing lever; selects the TIER and nothing else).
 * - [exportLogs] — `SNAPSYNC_EXPORT_LOGS`: presence (any value) copies the **extension's** log out of
 *   the shared App Group into the app's own `Documents/`, where `pymobiledevice3 apps pull` can reach
 *   it (capability `diagnostic-logging`). The extension can never see a launch env var — the OS
 *   launches it — so the app is the only process that can do the copying. Mutates no membership, so
 *   it takes no part in the `reset → leave → create → event-link` ordering, and it applies on a forge
 *   launch too: copying a file reaches no live-stack seam.
 *
 * A non-positive or non-integer seed count parses to `null` (the shell already warned on the raw
 * value); an absent variable parses to `null` / `false`.
 */
data class LaunchDirectives(
    val eventLink: String?,
    val createEvent: String?,
    val leave: Boolean,
    val resetState: Boolean,
    val seedPhotos: Int?,
    val seedPolicy: Int?,
    val policyProbe: String?,
    val forgeState: String?,
    val forceUrlSessionUpload: Boolean,
    val exportLogs: Boolean,
) {
    companion object {
        val NONE = LaunchDirectives(
            eventLink = null,
            createEvent = null,
            leave = false,
            resetState = false,
            seedPhotos = null,
            seedPolicy = null,
            policyProbe = null,
            forgeState = null,
            forceUrlSessionUpload = false,
            exportLogs = false,
        )

        /**
         * Parse the directives from a process-environment reader ([env] returns the value for a name,
         * or `null` when absent). Pure — the shell supplies `NSProcessInfo.processInfo.environment[…]`
         * as `env`; tests supply a map. A blank string is treated as present-but-empty per the OS
         * (only presence is meaningful for [forceUrlSessionUpload]; the others need a usable value).
         */
        fun from(env: (String) -> String?): LaunchDirectives = LaunchDirectives(
            eventLink = env("SNAPSYNC_EVENT_LINK"),
            createEvent = env("SNAPSYNC_CREATE_EVENT"),
            // Presence — not a value — is the trigger (as with [forceUrlSessionUpload]).
            leave = env("SNAPSYNC_LEAVE") != null,
            // Presence — not a value — is the trigger, like [leave].
            resetState = env("SNAPSYNC_RESET_STATE") != null,
            seedPhotos = positiveInt(env("SNAPSYNC_SEED_PHOTOS")),
            seedPolicy = positiveInt(env("SNAPSYNC_SEED_POLICY")),
            policyProbe = env("SNAPSYNC_POLICY_PROBE"),
            forgeState = env("SNAPSYNC_FORGE_STATE"),
            // Presence — not a value — is the trigger, exactly as the shell's `!= null` read was.
            forceUrlSessionUpload = env("SNAPSYNC_FORCE_URLSESSION_UPLOAD") != null,
            // Presence — not a value — is the trigger, like [leave] and [resetState].
            exportLogs = env("SNAPSYNC_EXPORT_LOGS") != null,
        )

        /**
     * Absence: null means "no usable positive count" — unset, non-numeric, and non-positive collapse
     * on purpose, because a dev/test launch trigger treats all three as "not requested". Reading an
     * already-materialised env map cannot fail, so no fourth cause is hiding here.
     */
    private fun positiveInt(raw: String?): Int? = raw?.toIntOrNull()?.takeIf { it > 0 }
    }
}
