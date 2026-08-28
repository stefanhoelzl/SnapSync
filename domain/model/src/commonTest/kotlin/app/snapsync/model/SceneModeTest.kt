package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure scene-mode resolver (capability `ios-app-shell`).
 *
 * The load-bearing case is the **background-woken process**: iOS connects UI scenes in the background, so
 * an unconditional shell composes a Compose runtime and Metal renderer in a process that cannot draw, keeps
 * it across the window in which iOS reclaims GPU resources, and then presents it — the shape both
 * production reports of a blank/corrupted status screen exhibited. Pinned here so the shell can never
 * regress into composing before the app has been active.
 *
 * The second half is pinned just as hard: once composed, a scene is **kept**. Tearing it down on every
 * background would discard screen-local Compose state (an open reconfigure surface, a half-typed bug
 * report) on every ordinary app switch.
 */
class SceneModeTest {

    @Test
    fun `a process woken into the background composes nothing`() {
        assertEquals(
            SceneMode.Deferred,
            resolveScene(AppVisibility.BACKGROUND, everActive = false),
        )
    }

    @Test
    fun `foreground-inactive does not compose either - it is also the backgrounding transition`() {
        assertEquals(
            SceneMode.Deferred,
            resolveScene(AppVisibility.INACTIVE, everActive = false),
        )
    }

    @Test
    fun `the first activation composes`() {
        assertEquals(
            SceneMode.Live,
            resolveScene(AppVisibility.ACTIVE, everActive = false),
        )
    }

    @Test
    fun `a scene already composed survives backgrounding`() {
        assertEquals(
            SceneMode.Live,
            resolveScene(AppVisibility.BACKGROUND, everActive = true),
        )
    }

    @Test
    fun `a scene already composed survives the inactive transition`() {
        assertEquals(
            SceneMode.Live,
            resolveScene(AppVisibility.INACTIVE, everActive = true),
        )
    }

    @Test
    fun `an active app composes whether or not it has been active before`() {
        assertEquals(SceneMode.Live, resolveScene(AppVisibility.ACTIVE, everActive = true))
        assertEquals(SceneMode.Live, resolveScene(AppVisibility.ACTIVE, everActive = false))
    }

    @Test
    fun `each mode names itself for the device log - the deferral's verification`() {
        assertEquals("deferred", SceneMode.Deferred.diagnosticName)
        assertEquals("live", SceneMode.Live.diagnosticName)
    }

    // `UIApplicationState`'s raw values are Apple's, fixed by ABI. Pinned here — as the PhotoKit
    // processing result's are — so a platform change breaks a test rather than silently re-classifying
    // every launch as active.
    @Test
    fun `UIApplicationState raw values map to visibility`() {
        assertEquals(AppVisibility.ACTIVE, appVisibilityFrom(0L))
        assertEquals(AppVisibility.INACTIVE, appVisibilityFrom(1L))
        assertEquals(AppVisibility.BACKGROUND, appVisibilityFrom(2L))
    }

    @Test
    fun `an unrecognized application state defers - the safe direction`() {
        assertEquals(AppVisibility.BACKGROUND, appVisibilityFrom(99L))
        assertEquals(SceneMode.Deferred, resolveScene(appVisibilityFrom(99L), everActive = false))
    }

    // ── The rebuild signal (capability `ios-app-shell`) ────────────────────────────────────────────

    @Test
    fun `handing out a placeholder advances the signal`() {
        assertEquals(1, sceneGenerationAfter(SCENE_GENERATION_INITIAL, SceneMode.Deferred))
    }

    // The case the white-screen reports turned on. No placeholder was ever installed, so nothing needs
    // retiring; asking the platform to rebuild would discard screen-local state at best and — measured on
    // device (SE2, iOS 26.6) before the scene stopped being reused — detach the scene entirely.
    @Test
    fun `handing out a live scene carries the signal forward unchanged`() {
        assertEquals(SCENE_GENERATION_INITIAL, sceneGenerationAfter(SCENE_GENERATION_INITIAL, SceneMode.Live))
        assertEquals(1, sceneGenerationAfter(1, SceneMode.Live))
    }

    /**
     * **The regression this rule was rewritten for.** A first revision answered from the mode most
     * recently handed out, so once the placeholder was replaced the record said `Live` and the answer fell
     * `1 → 0`. `.id(…)` reacts to CHANGE, so a fall rebuilds exactly as a rise does: measured on a
     * simulator (iOS 26, 2026-08-26) as a third `MainViewController` call, on the first warm foreground,
     * discarding screen-local Compose state.
     *
     * The signal must therefore be MONOTONIC across the whole life of a process.
     */
    @Test
    fun `the signal never falls back once a placeholder has been retired`() {
        var g = SCENE_GENERATION_INITIAL
        g = sceneGenerationAfter(g, SceneMode.Deferred)   // placeholder installed
        assertEquals(1, g)
        g = sceneGenerationAfter(g, SceneMode.Live)       // the rebuild that retires it
        assertEquals(1, g, "retiring the placeholder must not drop the signal — that IS a rebuild")
        repeat(5) { g = sceneGenerationAfter(g, SceneMode.Live) }
        assertEquals(1, g, "later activations must leave it alone")
    }

    /**
     * The whole rule, composed the way the shell composes it, against BOTH launch orderings — the property
     * that actually matters, since the platform decides the order and this app cannot.
     */
    @Test
    fun `both launch orderings settle on a correct rebuild signal`() {
        // Body first: the notification has not been observed, so the app still reads as background and a
        // placeholder is handed out. The activation that follows retires it — exactly one rebuild — and
        // the live scene that replaces it leaves the signal where it is.
        var bodyFirst = SCENE_GENERATION_INITIAL
        val deferred = resolveScene(AppVisibility.BACKGROUND, everActive = false)
        assertEquals(SceneMode.Deferred, deferred)
        bodyFirst = sceneGenerationAfter(bodyFirst, deferred)
        assertEquals(1, bodyFirst)
        bodyFirst = sceneGenerationAfter(bodyFirst, resolveScene(AppVisibility.ACTIVE, everActive = true))
        assertEquals(1, bodyFirst)

        // Notification first: Kotlin's observer has already set everActive, so the FIRST resolution is
        // live and no placeholder ever exists. SwiftUI's subscription missed that notification, so this is
        // the process that used to sit armed. It must ask for no rebuild, then or ever.
        var notificationFirst = SCENE_GENERATION_INITIAL
        val live = resolveScene(AppVisibility.ACTIVE, everActive = true)
        assertEquals(SceneMode.Live, live)
        notificationFirst = sceneGenerationAfter(notificationFirst, live)
        assertEquals(SCENE_GENERATION_INITIAL, notificationFirst)
        repeat(3) { notificationFirst = sceneGenerationAfter(notificationFirst, live) }
        assertEquals(SCENE_GENERATION_INITIAL, notificationFirst)
    }
}
