package app.snapsync.domain.sharing

import app.snapsync.domain.asset.PixelArea

/**
 * The tuning of the origin exclusions: the two resolution floors and the album denylist — what the policy compares a
 * photo against to decide it was *received* rather than *taken*.
 *
 * **Product policy, not a platform fact.** Measured on iOS, but what it catches — messaging apps' recompression and
 * the albums those apps file into — is what the policy means on any platform, so there is ONE value ([STANDARD]) and
 * no composition supplies its own: an uploader running a lower floor than the status total would send photos the
 * total never counts.
 */
class SelectionCalibration private constructor(
    /**
     * Images below this are excluded. WhatsApp caps received images at a 1600 long edge (~1.9 MP, at worst 2.6 MP
     * square), Telegram ~1.2 MP, an Instagram save ~1.5 MP — while the weakest camera on the oldest supported device
     * (the SE2 front camera) is 7.2 MP. Fixed, not derived from the device's camera: a device-derived floor is tighter
     * on a better camera, and tighter means more false drops.
     */
    val imageFloor: PixelArea.Known,
    /**
     * Videos below this are excluded — separate and lower, and load-bearing: 1080p is 2.07 MP, below the image floor,
     * and 1080p is the iOS capture default.
     */
    val videoFloor: PixelArea.Known,
    val denylist: Set<String>,
) {
    /**
     * Whether the album [title] holds what a member received rather than took. Exact and case-insensitive after
     * trimming surrounding spaces: `whatsapp` and ` WhatsApp ` match, `WhatsApp Backup` and a user's "Signal Hill
     * Hike" do not.
     *
     * The list's recall is known to be poor and that is accepted: most messaging apps create no album at all, the
     * titles are app-chosen strings with no registry, and the rule is a cheap, strictly additive subtraction. The
     * resolution floors are the primary defence. On Android an album is a DCIM folder, so the list also names the
     * folders the phone files screenshots and screen recordings into — Android marks neither on the asset.
     */
    fun denies(title: String): Boolean = denylist.any { it.equals(title.trim(), ignoreCase = true) }

    companion object {
        /** The one calibration every composition runs. */
        val STANDARD: SelectionCalibration = SelectionCalibration(
            imageFloor = PixelArea.Known(3_000_000),
            videoFloor = PixelArea.Known(1280L * 720L),
            denylist = setOf(
                // Messengers
                "WhatsApp",
                "WhatsApp Business",
                "Telegram",
                "Signal",
                "Threema",
                "Viber",
                "WeChat",
                "LINE",
                "Discord",
                "Messenger",
                // Social
                "Instagram",
                "Facebook",
                "Snapchat",
                "TikTok",
                "Twitter",
                "X",
                "Pinterest",
                "Reddit",
                // The phone's own screenshot and screen-recording folders (Android: a DCIM subfolder is an album)
                "Screenshots",
                "Screen recordings",
                "ScreenRecorder",
            ),
        )
    }
}
