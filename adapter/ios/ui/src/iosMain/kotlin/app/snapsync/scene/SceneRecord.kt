package app.snapsync.scene

/**
 * What this **process** knows about its scenes (capability `sync-status`) — shared by the iOS `Lifecycle` and `Ui`
 * adapters, and touched only on the main thread (UIKit's notifications and SwiftUI's calls both arrive there).
 *
 * [everActive] is the input `UIApplication` cannot supply: it reports the current state only, and "has been active
 * at least once" is precisely what separates a background-woken process from an ordinary backgrounded one. Written
 * when the app becomes active — by the `Lifecycle` adapter before its handler runs, and by SwiftUI's own
 * `didBecomeActive` call — and never reset, because a scene once composed is kept.
 *
 * [generation] is the value SwiftUI binds to `.id(…)`: it counts **placeholders retired**, never activations, and
 * never decreases — the pure [sceneGenerationAfter] advances it each time a scene is handed out, and [resolve] is the
 * only writer. It is independent of the mode handed out, so a process whose first pull is already live — Kotlin's
 * `didBecomeActive` observer ran before SwiftUI first evaluated its body — stays at `0` with a live scene, which is
 * correct: nothing needs rebuilding. An earlier revision that answered from the mode instead left a rebuild armed
 * but unfired, which the next ordinary foreground fired, blanking the screen (Bugsink SNAPSYNC-15, SNAPSYNC-24).
 * `SceneRecordCompletenessTest` pins [resolve]'s single caller: every scene handed out passes through it, which is
 * what makes the count complete.
 */
class SceneRecord {
    var everActive: Boolean = false
        private set

    var generation: Int = SCENE_GENERATION_INITIAL
        private set

    /** The app became active. */
    fun markActive() {
        everActive = true
    }

    /**
     * Whether a scene is composed right now, from the platform's current application state (its raw value) and
     * [everActive] — resolved by the pure [resolveScene], and recorded: the generation advances as the scene is
     * handed out.
     */
    fun resolve(rawApplicationState: Long): SceneMode {
        val mode = resolveScene(appVisibilityFrom(rawApplicationState), everActive)
        generation = sceneGenerationAfter(generation, mode)
        return mode
    }
}
