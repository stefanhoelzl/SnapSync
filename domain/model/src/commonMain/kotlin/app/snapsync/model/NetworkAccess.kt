package app.snapsync.model

/**
 * Whether this app can reach the network right now, as the operating system tells it (the `NetworkMonitor` port).
 *
 * [BLOCKED] and [OFFLINE] are told apart because their remedies differ: a block is a setting of THIS app's the user can
 * change (iOS's per-app Cellular / WLAN switch, Android's per-app network restrictions), an offline device needs a
 * network. A server that does not answer is neither — the operating system cannot see it, and neither does this.
 */
enum class NetworkAccess {
    /** A path to the network exists for this app. */
    ONLINE,

    /** The device has no usable network: airplane mode, no signal, no Wi-Fi joined. */
    OFFLINE,

    /** A network exists, but the operating system withholds it from this app. */
    BLOCKED,
}
