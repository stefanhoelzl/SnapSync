package app.snapsync.services.settings

import app.snapsync.model.PrefRead
import app.snapsync.model.TransferNetwork
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.Preferences
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The preferences key holding the device's mobile-data choice — runtime identity. */
internal const val MOBILE_DATA_KEY = "app.snapsync.settings.mobileData"

/**
 * **Whether photos may use mobile data on this device** (decision record
 * `changes/archive/2026-10-07-mobile-data-per-device`, D1): one choice for the device, in the shared [Preferences],
 * so the app and the upload extension read the same value.
 *
 * - [transferNetwork] — read on every call, because the extension is another process an app-side change does not
 *   reach otherwise. Nothing stored is "on" (the default); a value that cannot be read, or that is neither `on` nor
 *   `off`, holds photos to an unrestricted network: a wrong hold only delays a photo, a wrong send spends the member's
 *   data.
 * - [allowed] — the app's view of the choice for the menu and the waiting line, seeded from one read (an unreadable
 *   one shows the default) and moved only by a [set] that landed.
 * - [clear] — a device reset returns the choice to its default.
 */
class MobileDataSetting(
    private val preferences: Preferences,
    private val log: Logger = Logger.withTag("MobileData"),
) {
    private val state = MutableStateFlow(read() != Stored.OFF)

    val allowed: StateFlow<Boolean> = state.asStateFlow()

    /** The rule a photo transfer created now follows. */
    fun transferNetwork(): TransferNetwork =
        if (read() == Stored.ON) TransferNetwork.ANY else TransferNetwork.UNRESTRICTED_ONLY

    /** Store [on]; answers whether it landed, and only then does [allowed] follow. */
    fun set(on: Boolean): Boolean {
        val written = preferences.set(MOBILE_DATA_KEY, if (on) ON else OFF)
        if (written !is WriteOutcome.Ok) return false.also { log.w { "mobile data $on not saved ($written)" } }
        state.value = on
        return true
    }

    /** Forget the choice: the device is back at the default. */
    fun clear() {
        val removed = preferences.remove(MOBILE_DATA_KEY)
        if (removed is WriteOutcome.Ok) state.value = true else log.w { "mobile data not cleared ($removed)" }
    }

    /** What is stored, for the dump: `on`, `off` or `unreadable`. */
    fun describe(): String = when (read()) {
        Stored.ON -> ON
        Stored.OFF -> OFF
        Stored.UNREADABLE -> "unreadable"
    }

    private enum class Stored { ON, OFF, UNREADABLE }

    private fun read(): Stored = when (val read = preferences.get(MOBILE_DATA_KEY)) {
        PrefRead.Absent -> Stored.ON
        is PrefRead.Value -> when (read.value) {
            ON -> Stored.ON
            OFF -> Stored.OFF
            else -> Stored.UNREADABLE.also { log.w { "mobile data holds '${read.value}' — Wi-Fi only" } }
        }
        is PrefRead.Unavailable -> Stored.UNREADABLE.also { log.w { "mobile data unreadable (${read.detail}) — Wi-Fi only" } }
    }

    private companion object {
        const val ON = "on"
        const val OFF = "off"
    }
}
