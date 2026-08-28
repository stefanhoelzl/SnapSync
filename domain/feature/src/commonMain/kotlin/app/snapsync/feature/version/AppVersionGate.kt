package app.snapsync.feature.version

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the backend is refusing this build as too old, and the version it named (capability
 * `min-app-version`).
 *
 * A **read-model**, in the shape the presentation already consumes: one cell, observed, never
 * commanded. It is written by the shared HTTP client's interceptor — the one place every metadata seam
 * passes through — so no seam has to remember to report a refusal, and a seam added later is covered for
 * free. Presentation observes it directly, which is a READ and therefore does not cross `flow/`
 * (`module-architecture`, "Commands cross one door").
 *
 * **It is a coordination primitive, not authority.** Nothing durable is written and nothing is
 * recovered across a launch, deliberately: the answer is a property of the backend's current opinion of
 * this build, and the very next request re-establishes it. Persisting it could only produce a device
 * stuck on an update screen after the backend's minimum came back down.
 *
 * `null` means *not currently refused* — which covers both "served normally" and "has not called yet".
 * Those are the same thing to every consumer: there is no update screen to show. A refusal carrying no
 * version is [Refusal] with a null [Refusal.minimumVersion], which is NOT the same as no refusal at all
 * and must not be flattened into it — the screen still has to appear, just without a version to name.
 */
class AppVersionGate(
    private val log: Logger = Logger.withTag("AppVersionGate"),
) {
    /** A backend refusal of this build. */
    data class Refusal(val minimumVersion: String?)

    private val state = MutableStateFlow<Refusal?>(null)

    /** The current refusal, or `null` while this build is being served. */
    val refusal: StateFlow<Refusal?> = state.asStateFlow()

    /**
     * The backend refused this build (`426`), naming [minimumVersion] when it carried one.
     *
     * Reported at **`Error`**, so it reaches crash reporting (capability `crash-reporting`). That is not
     * a judgement about severity in the abstract — it is that this device now does nothing at all: every
     * metadata call is refused, no photo is shared and none arrives, and the member sees only a screen
     * telling them to update. An operator who cannot see that from the reports cannot tell a bad release
     * from a quiet week. Logged on the TRANSITION only, so a device left on this screen does not file
     * one report per request.
     */
    fun refused(minimumVersion: String?) {
        val refusal = Refusal(minimumVersion)
        if (state.value == refusal) return
        state.value = refusal
        log.e { "backend refuses this build; minimum version = ${minimumVersion ?: "unstated"}" }
    }

    /** A request was served, so this build is not refused. Idempotent and silent. */
    fun served() {
        if (state.value == null) return
        state.value = null
        log.i { "backend serves this build again" }
    }
}
