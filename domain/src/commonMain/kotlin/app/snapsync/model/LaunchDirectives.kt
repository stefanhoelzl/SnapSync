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
 * - [seedPhotos] — `SNAPSYNC_SEED_PHOTOS`: how many tiny 2001-dated assets to seed (walk-cost test).
 * - [seedPolicy] — `SNAPSYNC_SEED_POLICY`: how many hour-ahead assets straddling the 3 MP floor to
 *   seed (selection-policy probe).
 * - [policyProbe] — `SNAPSYNC_POLICY_PROBE`: a cutoff string against which to run the real own-device
 *   status refresh with no membership.
 * - [forgeState] — `SNAPSYNC_FORGE_STATE`: the name of a forge state to render for a marketing
 *   screenshot (recognition is presentation's, applied by [resolveComposition]'s `isForgeState`).
 * - [forceUrlSessionUpload] — `SNAPSYNC_FORCE_URLSESSION_UPLOAD`: force the app-driven URLSession
 *   upload tier even on iOS ≥26.1 (device-testing lever; selects the TIER and nothing else).
 *
 * A non-positive or non-integer seed count parses to `null` (the shell already warned on the raw
 * value); an absent variable parses to `null` / `false`.
 */
data class LaunchDirectives(
    val eventLink: String?,
    val createEvent: String?,
    val leave: Boolean,
    val seedPhotos: Int?,
    val seedPolicy: Int?,
    val policyProbe: String?,
    val forgeState: String?,
    val forceUrlSessionUpload: Boolean,
) {
    companion object {
        val NONE = LaunchDirectives(
            eventLink = null,
            createEvent = null,
            leave = false,
            seedPhotos = null,
            seedPolicy = null,
            policyProbe = null,
            forgeState = null,
            forceUrlSessionUpload = false,
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
            seedPhotos = positiveInt(env("SNAPSYNC_SEED_PHOTOS")),
            seedPolicy = positiveInt(env("SNAPSYNC_SEED_POLICY")),
            policyProbe = env("SNAPSYNC_POLICY_PROBE"),
            forgeState = env("SNAPSYNC_FORGE_STATE"),
            // Presence — not a value — is the trigger, exactly as the shell's `!= null` read was.
            forceUrlSessionUpload = env("SNAPSYNC_FORCE_URLSESSION_UPLOAD") != null,
        )

        private fun positiveInt(raw: String?): Int? = raw?.toIntOrNull()?.takeIf { it > 0 }
    }
}
