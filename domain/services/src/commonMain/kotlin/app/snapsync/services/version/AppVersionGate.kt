package app.snapsync.services.version

import app.snapsync.model.Reply
import app.snapsync.model.VersionRefusal
import app.snapsync.model.minAppVersionFromRefusal
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the backend is refusing this build as too old, and the version it named (capability
 * `app-update-required`).
 *
 * A **read-model**, in the shape the presentation already consumes: one cell, observed, never commanded. It is
 * written by the one place every backend answer passes through — [observe], called by the authenticated backend for
 * every route and by the attestation service for the `/attest/…` issuers — so no service has to remember to report
 * a refusal, and a route added later is covered for free.
 *
 * **It is a coordination primitive, not authority.** Nothing durable is written and nothing is recovered across a
 * launch, deliberately: the answer is a property of the backend's current opinion of this build, and the very next
 * request re-establishes it. Persisting it could only produce a device stuck on an update screen after the backend's
 * minimum came back down.
 *
 * `null` means *not currently refused* — which covers both "served normally" and "has not called yet". Those are the
 * same thing to every consumer: there is no update screen to show. A refusal carrying no version is
 * [VersionRefusal] with a null [VersionRefusal.minimumVersion], which is NOT the same as no refusal at all and must
 * not be flattened into it — the screen still has to appear, just without a version to name.
 */
class AppVersionGate(
    private val log: Logger = Logger.withTag("AppVersionGate"),
) {
    private val state = MutableStateFlow<VersionRefusal?>(null)

    /** The current refusal, or `null` while this build is being served. */
    val refusal: StateFlow<VersionRefusal?> = state.asStateFlow()

    /**
     * Read one backend answer: a `426` is a refusal, naming the minimum its body carries; any success clears one.
     * Every other answer says nothing about this build and changes nothing.
     */
    fun observe(reply: Reply<*>) {
        when {
            reply is Reply.Refused && reply.status == UPGRADE_REQUIRED -> refused(minAppVersionFromRefusal(reply.body))
            reply is Reply.Ok -> served()
        }
    }

    /**
     * The backend refused this build (`426`), naming [minimumVersion] when it carried one.
     *
     * Reported at **`Error`**, so it reaches crash reporting (capability `privacy-security`). That is not a
     * judgement about severity in the abstract — it is that this device now does nothing at all: every metadata call
     * is refused, no photo is shared and none arrives, and the member sees only a screen telling them to update. An
     * operator who cannot see that from the reports cannot tell a bad release from a quiet week. Logged on the
     * TRANSITION only, so a device left on this screen does not file one report per request.
     */
    fun refused(minimumVersion: String?) {
        val refusal = VersionRefusal(minimumVersion)
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

    private companion object {
        const val UPGRADE_REQUIRED = 426
    }
}
