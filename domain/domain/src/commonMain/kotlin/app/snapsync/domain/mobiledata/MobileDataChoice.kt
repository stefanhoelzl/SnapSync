package app.snapsync.domain.mobiledata

/**
 * **Whether photos may use mobile data on this device** (decision record
 * `changes/archive/2026-10-07-mobile-data-per-device`, D1): one choice for the device, which governs the photo
 * transfers of whichever event it is in, sharing and receiving alike — and nothing else.
 */
enum class MobileDataChoice {
    /** Photos may use any network — the default: a member who never touches the choice shares as without it. */
    ALLOWED,

    /** Photos wait for an unrestricted Wi-Fi. */
    WIFI_ONLY,
    ;

    /** The networks a photo transfer created now may use. */
    fun photoNetwork(): PhotoNetwork = when (this) {
        ALLOWED -> PhotoNetwork.ANY
        WIFI_ONLY -> PhotoNetwork.UNRESTRICTED_ONLY
    }

    companion object {
        /**
         * The choice [stored] holds: nothing stored, or `on`, is [ALLOWED]; `off`, a value that is neither, or one that
         * cannot be read is [WIFI_ONLY] — a wrong hold only delays a photo, a wrong send spends the member's data.
         */
        fun of(stored: StoredChoice): MobileDataChoice = when (stored) {
            StoredChoice.ABSENT, StoredChoice.ON -> ALLOWED
            StoredChoice.OFF, StoredChoice.OTHER, StoredChoice.UNREADABLE -> WIFI_ONLY
        }
    }
}

/** What the device's store holds for the choice, as read. */
enum class StoredChoice { ABSENT, ON, OFF, OTHER, UNREADABLE }

/** The networks a photo transfer may use. */
enum class PhotoNetwork {
    ANY,

    /** Neither costly nor data-restricted: no mobile data, hotspot, Low Data Mode or Data Saver. */
    UNRESTRICTED_ONLY,
}

/** What a change of the choice came to. */
sealed interface MobileDataOutcome {
    /** The choice in effect for the transfers that start from now on. */
    val inEffect: MobileDataChoice

    data class Saved(override val inEffect: MobileDataChoice) : MobileDataOutcome

    /** It could not be saved: [inEffect] is what still governs the transfers. */
    data class NotSaved(override val inEffect: MobileDataChoice) : MobileDataOutcome
}
