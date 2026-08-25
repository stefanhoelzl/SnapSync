package app.snapsync.model

/**
 * How visible the app is when the shell asks whether to compose a scene (capability `ios-app-shell`).
 *
 * Named for the need rather than for the platform enum it is read from, so the rule below stays testable
 * off-device and `model/` keeps no UIKit knowledge. The three values are the only distinctions the rule
 * makes; the platform's finer states collapse into them at the shell.
 *
 * - [ACTIVE] — foreground and active: the user can see and touch the UI.
 * - [INACTIVE] — foreground but not active (app switcher, an incoming-call banner, Control Centre).
 * - [BACKGROUND] — backgrounded, **including a process launched or woken straight into the background**
 *   by a silent push, a `BGTask`, or a background `URLSession` event.
 */
enum class AppVisibility {
    ACTIVE,
    INACTIVE,
    BACKGROUND,
}

/**
 * `UIApplicationState`'s raw values, mapped here rather than in the shell (capability `ios-app-shell`).
 *
 * The mapping is a **decision**, so it belongs in tested code — the same move the PhotoKit processing
 * result made when its `when` left the shell for `model/` with its raw values pinned in `commonTest`. The
 * shell then reads one platform property and passes its raw value through, branching on nothing.
 *
 * The values are Apple's, fixed by ABI: `UIApplicationStateActive` = 0, `UIApplicationStateInactive` = 1,
 * `UIApplicationStateBackground` = 2. They are pinned in `commonTest` against the same constants, so a
 * platform change breaks a test rather than silently re-classifying every launch as active.
 *
 * An unrecognized value maps to [AppVisibility.BACKGROUND] — the **safe** direction: it defers composing,
 * which costs a scene built one transition later, where the unsafe direction composes a renderer in a
 * process that cannot draw.
 */
fun appVisibilityFrom(rawApplicationState: Long): AppVisibility = when (rawApplicationState) {
    0L -> AppVisibility.ACTIVE
    1L -> AppVisibility.INACTIVE
    else -> AppVisibility.BACKGROUND
}

/**
 * Whether the shell composes a Compose scene (capability `ios-app-shell`): a **sealed** type so the shell
 * switches once on it and the compiler fails closed if a third mode is ever added.
 *
 * - [Deferred] — compose nothing. The shell returns a bare placeholder view controller: no
 *   `ComposeUIViewController`, no Compose runtime, no renderer.
 * - [Live] — compose the real scene hosting the shared `StatusScreen`.
 */
sealed interface SceneMode {

    /**
     * How the device log names the resolved mode (capability `diagnostic-logging`). The shell transcribes
     * this one resolved fact rather than branching a second time to describe itself
     * (`module-architecture`, "Shells are wiring only") — and it is the **verification** of the deferral:
     * a background-woken process must record `deferred` and never `live` until it has been active.
     */
    val diagnosticName: String

    data object Deferred : SceneMode {
        override val diagnosticName: String get() = "deferred"
    }

    data object Live : SceneMode {
        override val diagnosticName: String get() = "live"
    }
}

/**
 * The pure scene-mode resolver (capability `ios-app-shell`).
 *
 * **Why this exists.** iOS connects UI scenes in `UISceneActivationState.background`, so a process launched
 * or woken by a silent push or a `BGTask` gets a connected scene and — with an unconditional shell — stands
 * up a full Compose runtime and Metal renderer in a process that cannot draw. Apple's contract is that a
 * backgrounded app must not submit GPU work and that its GPU resources are reclaimed; a renderer is expected
 * to free and rebuild them across that transition. Compose Multiplatform 1.11.1 does not, and the observed
 * consequence is a scene composed while invisible, held for hours, then presented drawing its texture-backed
 * content — glyph atlas, cached `ImageBitmap`s, cached vector layers — blank or corrupted while plain
 * geometry still draws. This resolver removes the precondition rather than recovering from it.
 *
 * **This is a mitigation for an upstream defect, not an architectural preference.** Expiry trigger:
 * CMP-5978 fixed in a Compose Multiplatform release this project adopts, at which point this rule should be
 * re-evaluated and removed if the renderer honours the contract.
 *
 * The rule has exactly two halves, and the second is as load-bearing as the first:
 *
 * 1. **Compose only when [AppVisibility.ACTIVE].** [AppVisibility.INACTIVE] deliberately does **not**
 *    compose: it is reached while backgrounding as well as while foregrounding, so composing there would
 *    re-admit the case this rule exists to exclude. The cost is that a scene is composed a moment later than
 *    it strictly could be, on a transition the user is already watching animate.
 * 2. **Once composed, stay composed** ([everActive]). Tearing a scene down when the app leaves the
 *    foreground would discard screen-local Compose state — an open reconfigure surface, a half-typed bug
 *    report, a scroll position — on every ordinary app switch. Rebuilding after a *long* background is a
 *    separate, unshipped option; this rule does not attempt it.
 *
 * [everActive] is the shell's own record of whether this **process** has ever been active, not a platform
 * read: the platform reports the current state only, and "has been active at least once" is precisely what
 * distinguishes a background-woken process from an ordinary backgrounded one.
 */
fun resolveScene(visibility: AppVisibility, everActive: Boolean): SceneMode = when {
    visibility == AppVisibility.ACTIVE -> SceneMode.Live
    everActive -> SceneMode.Live
    else -> SceneMode.Deferred
}
