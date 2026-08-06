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
}
