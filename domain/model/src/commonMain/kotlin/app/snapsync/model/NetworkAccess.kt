package app.snapsync.model

/**
 * Whether this app can reach the network right now, as the operating system tells it (the `NetworkMonitor` port).
 *
 * [Blocked] and [Offline] are told apart because their remedies differ: a block is a setting of THIS app's the user can
 * change (iOS's per-app Cellular / WLAN switch, Android's per-app network restrictions), an offline device needs a
 * network. A server that does not answer is neither — the operating system cannot see it, and neither does this.
 */
sealed interface NetworkAccess {
    /**
     * A path to the network exists for this app. [restricted] says the path is one the member may want photos kept
     * off (capability `mobile-data`): mobile data, a personal hotspot or any other network the platform treats as
     * costly, or a network under Low Data Mode (iOS) / Data Saver (Android). Anything that only asks "is there a
     * network" matches [Online] and ignores the flag.
     */
    data class Online(val restricted: Boolean) : NetworkAccess

    /** The device has no usable network: airplane mode, no signal, no Wi-Fi joined. */
    data object Offline : NetworkAccess

    /** A network exists, but the operating system withholds it from this app. */
    data object Blocked : NetworkAccess
}
