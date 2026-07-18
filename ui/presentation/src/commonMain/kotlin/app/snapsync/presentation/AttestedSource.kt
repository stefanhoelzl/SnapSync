package app.snapsync.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether this device holds a usable attestation token (capability `device-attestation`).
 *
 * `false` means both halves of a real problem: there is no valid token, **and** obtaining one failed. It
 * does NOT mean "the token is stale" — a stale token that renews on the next wake is a non-event, and
 * raising it to the UI would be noise.
 *
 * The distinction matters because *opening the app renews*. The status screen therefore cannot normally
 * display an unattested state at all: looking at it heals it. What survives long enough to be seen is the
 * case where renewal itself keeps failing — the device is offline, or the backend is refusing us — which
 * is a persistent problem that no amount of waiting fixes, and which would otherwise hide behind a screen
 * that cheerfully reported "Syncing" while every upload `401`ed.
 */
interface AttestedSource {
    val attested: StateFlow<Boolean>
}

/** The default: attestation is somebody else's problem (non-iOS hosts, the harness, every existing test). */
object AlwaysAttested : AttestedSource {
    override val attested: StateFlow<Boolean> = MutableStateFlow(true)
}

/** The iOS-side source: the composition root flips it from `DeviceAttestation.ensureFresh()`. */
class MutableAttestedSource(initial: Boolean = true) : AttestedSource {
    private val state = MutableStateFlow(initial)
    override val attested: StateFlow<Boolean> = state
    fun set(value: Boolean) {
        state.value = value
    }
}
