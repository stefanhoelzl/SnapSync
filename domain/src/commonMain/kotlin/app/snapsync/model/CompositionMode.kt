package app.snapsync.model

/**
 * The upload tier a live composition runs (capability `upload-lifecycle`, "Exactly one producer per
 * process"). Selection is a **fact about the OS and the launch**, not a scattered runtime guard:
 * exactly one tier is chosen once per process, and the non-selected tier's mechanism is never
 * constructed.
 *
 * - [PHOTOKIT] — the OS-driven `PHBackgroundResourceUploadExtension` registration (iOS ≥26.1).
 * - [URL_SESSION] — the in-app background `URLSession` pump (iOS 18–26.0, or the dev force flag).
 */
enum class UploadTier { PHOTOKIT, URL_SESSION }

/**
 * OS facts the composition resolver reads (spec `module-architecture`, "One shared composition":
 * "a pure, unit-tested function from parsed launch directives and OS facts to a sealed composition
 * mode"). Kept a value so the resolver is testable off-device.
 *
 * - [backgroundUploadSupported] — the iOS 26.1 background-upload API is present.
 * - [isSimulator] — this process is a simulator (the only place the URLSession transport is
 *   downgraded off a background session; a device — including a force-flagged one — is not).
 */
data class OsFacts(
    val backgroundUploadSupported: Boolean,
    val isSimulator: Boolean,
)

/**
 * The resolved composition mode (spec `module-architecture`, "One shared composition"): a **sealed**
 * type so `composeRoot` switches once on it and the compiler fails closed when a new mode is added —
 * a data-class field would not. There are exactly two:
 *
 * - [Forge] — render the shared `StatusScreen` over forged sources for a marketing screenshot; the
 *   live stack is **not** assembled (no ledger, App Attest, PhotoKit, or backend). Carries the
 *   recognized state name.
 * - [Live] — the real stack, on the resolved [UploadTier], with the app-driven URLSession transport
 *   choice ([useBackgroundSession]) folded in.
 */
sealed interface CompositionMode {
    data class Forge(val state: String) : CompositionMode

    data class Live(
        val tier: UploadTier,
        /**
         * Whether the app-driven tier runs over a real background `URLSession` (true on any device,
         * including a force-flagged one; false only on a simulator, which cannot). Meaningful only
         * when [tier] is [UploadTier.URL_SESSION]; carried on [Live] so the shell reads one resolved
         * fact rather than re-deriving `!isSimulator`.
         */
        val useBackgroundSession: Boolean,
    ) : CompositionMode
}

/**
 * The pure composition-mode resolver (spec `module-architecture`, "One shared composition"; decision
 * record `establish-target-architecture` D5). Its precedence is unit-tested (the shipped forge×link
 * interaction bug becomes a resolver test): **forge excludes the live-stack boot**.
 *
 * The forge decision comes first and wins **unconditionally over an event link** — a screenshot run
 * that also carries `SNAPSYNC_EVENT_LINK` must render the forged frame and provision **nothing**, or
 * a process rendering a forged frame would boot the live stack on an unsigned simulator with no
 * App-Group container, no App Attest, and no photo grant (incoherent before it is a crash). [forgeState]
 * is only honoured when [isForgeState] recognizes it (an unrecognized name falls through to [Live],
 * exactly as `SNAPSYNC_FORGE_STATE=nonsense` renders the live stack today).
 *
 * [isForgeState] is passed in — not imported — because recognition (mapping a name to forged sources)
 * is presentation's, unreachable from `model/`; the shell passes `presentation::isForgeState`, and the
 * precedence test passes a stub. Tier selection folds in here per the one-composition law: the
 * app-driven tier is chosen when the OS lacks the ≥26.1 API **or** the force flag is set.
 */
fun resolveComposition(
    directives: LaunchDirectives,
    osFacts: OsFacts,
    isForgeState: (String) -> Boolean,
): CompositionMode {
    // Forge excludes the live-stack boot — and it excludes an event link too (the forge×link bug).
    if (directives.forgeState != null && isForgeState(directives.forgeState)) {
        return CompositionMode.Forge(directives.forgeState)
    }
    val tier = if (!osFacts.backgroundUploadSupported || directives.forceUrlSessionUpload) {
        UploadTier.URL_SESSION
    } else {
        UploadTier.PHOTOKIT
    }
    return CompositionMode.Live(tier = tier, useBackgroundSession = !osFacts.isSimulator)
}
