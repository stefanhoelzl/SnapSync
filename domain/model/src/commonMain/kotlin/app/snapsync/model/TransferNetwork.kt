package app.snapsync.model

/**
 * Which networks a photo transfer may use (capability `mobile-data`), fixed when the transfer is created and carried
 * with it to the platform — so a later change of the member's choice governs only the transfers that start after it.
 *
 * [UNRESTRICTED_ONLY] asks the platform to hold the transfer off every network it treats as restricted: mobile data,
 * a personal hotspot or any other costly (metered) network, and a network under Low Data Mode (iOS) or Data Saver
 * (Android). Who honours it differs per path — the request flags on iOS, `DownloadManager` and the upload adapter's
 * own wait on Android; `openspec/changes/archive/2026-10-04-mobile-data-for-photos/design.md` records what was measured.
 */
enum class TransferNetwork {
    /** Any network the phone offers — the behaviour without the choice. */
    ANY,

    /** Only a network that is neither costly nor data-restricted. */
    UNRESTRICTED_ONLY,
}

/** The rule for a membership whose photos may ([mobileData] = true) or may not use mobile data. */
fun transferNetworkOf(mobileData: Boolean): TransferNetwork =
    if (mobileData) TransferNetwork.ANY else TransferNetwork.UNRESTRICTED_ONLY

/**
 * The rule a transfer created now carries, from the membership as this process last read it. A membership it cannot
 * read gets the STRICTER rule: holding a transfer for Wi-Fi only delays it, while sending it over mobile data the
 * member refused cannot be taken back.
 */
fun transferNetworkOf(membership: EventConfig?): TransferNetwork =
    membership?.transferNetwork ?: TransferNetwork.UNRESTRICTED_ONLY
