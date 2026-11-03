package app.snapsync.domain.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether this app can reach the network right now, as the operating system tells it.
 *
 * [Blocked] and [Offline] are told apart because their remedies differ: a block is a setting of THIS app's the user can
 * change (iOS's per-app Cellular / WLAN switch, Android's per-app network restrictions), an offline device needs a
 * network. A server that does not answer is neither — the operating system cannot see it, and neither does this.
 */
sealed interface NetworkAccess {
    /**
     * A path to the network exists for this app. [restricted] says the path is one the member may want photos kept off:
     * mobile data, a personal hotspot or any other network the platform treats as costly, or a network under Low Data
     * Mode (iOS) / Data Saver (Android). Anything that only asks "is there a network" matches [Online] and ignores the
     * flag.
     */
    data class Online(val restricted: Boolean) : NetworkAccess

    /** The device has no usable network: airplane mode, no signal, no Wi-Fi joined. */
    data object Offline : NetworkAccess

    /** A network exists, but the operating system withholds it from this app. */
    data object Blocked : NetworkAccess
}

/**
 * What the member is told about the network, [shown], and the rule that moves it — **slow to warn, instant to clear**
 * (decision record `changes/tell-when-offline`, D2, D4). A missing network is told only once it has held for [GRACE]: a
 * lift, a Wi-Fi handover or a tunnel would otherwise flash a notice and take Create away for a second. Its return is
 * told at once, and so is a change of cause while a notice already shows — the cause changed, not the presence.
 */
data class NetworkNotice(val shown: NetworkAccess) {

    /** How long [reading] must hold before it is told: [GRACE] for a network going missing, nothing otherwise. */
    fun waitBefore(reading: NetworkAccess): Duration = when (shown) {
        is NetworkAccess.Online -> missing(reading)
        NetworkAccess.Offline, NetworkAccess.Blocked -> Duration.ZERO
    }

    /** Whether telling [reading] is the network's return — what resumes the app's work. */
    fun returns(reading: NetworkAccess): NetworkReturn = when (shown) {
        is NetworkAccess.Online -> NetworkReturn.UNCHANGED
        NetworkAccess.Offline, NetworkAccess.Blocked -> online(reading)
    }

    private fun missing(reading: NetworkAccess): Duration = when (reading) {
        is NetworkAccess.Online -> Duration.ZERO
        NetworkAccess.Offline, NetworkAccess.Blocked -> GRACE
    }

    private fun online(reading: NetworkAccess): NetworkReturn = when (reading) {
        is NetworkAccess.Online -> NetworkReturn.RETURNED
        NetworkAccess.Offline, NetworkAccess.Blocked -> NetworkReturn.UNCHANGED
    }

    companion object {
        /** Nothing told: what a stopped watch shows, so returning to the front never shows a stale notice. */
        val HIDDEN: NetworkNotice = NetworkNotice(NetworkAccess.Online(restricted = false))

        /** How long a missing network must last before the member is told. Chosen for human perception, not derived. */
        val GRACE: Duration = 5.seconds
    }
}

/** Whether a reading told is the network's return from missing. */
enum class NetworkReturn {
    RETURNED,
    UNCHANGED,
}
