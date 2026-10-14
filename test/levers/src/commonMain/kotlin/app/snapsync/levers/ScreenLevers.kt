package app.snapsync.levers

import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi

/**
 * **The screen, as a person meets it** — what they do on it, and what it shows. Every host serves it: a tap reaches the
 * app as the platform's own UI hands it over, whether the screen is the mock's or the platform's.
 *
 * There is one verb for everything a finger does: a [UiIntent]. A form a person fills in several taps is those taps, in
 * order — the client names the sequences worth a name (`:test:control`'s `Taps`), never the host.
 */
@Rpc
interface ScreenLevers {
    /** A person did [intent] on the screen. */
    suspend fun tap(intent: UiIntent)

    /** The last state the app showed, or `null` before any. */
    suspend fun shown(): UiState?

    /**
     * A live screen is about to be built: it renders the state the app answers, before its first frame. Only a host
     * whose screen is the mock's plays this; the platform's own screen is built by the platform.
     */
    suspend fun live()

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<ScreenLevers>(
        ScreenLevers::class,
        "screen",
        setOf(LeverNeed.HOST),
        { serviceDescriptorOf() },
        reads = setOf("shown"),
    )
}
