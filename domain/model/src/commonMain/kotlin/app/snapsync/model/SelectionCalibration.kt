package app.snapsync.model

/**
 * The tuning of the origin exclusions (capability `photo-sharing`): the two resolution floors and the album
 * denylist — the three numbers and names the policy compares a photo against to decide it was *received*
 * rather than *taken*.
 *
 * **Product policy, not a platform fact.** They were measured on iOS, but what they catch — messaging apps'
 * recompression and the albums those apps file into — is what the policy means on any platform, so there is
 * ONE value ([SELECTION_CALIBRATION]) and no composition supplies its own. That is also what keeps the app and
 * the upload extension from ever disagreeing: an uploader running a lower floor than the status total would
 * send photos `N` never counts, and the screen would peg below 100% forever.
 *
 * A test that wants other numbers builds its own value, as it builds its own rule lists.
 */
data class SelectionCalibration(
    /**
     * Images below this pixel area are excluded. WhatsApp caps received images at a 1600 long edge (~1.9 MP,
     * at worst 2.6 MP square), Telegram ~1.2 MP, an Instagram save ~1.5 MP — while the *weakest* camera on the
     * oldest supported device (the SE2 front camera) is 3088×2320 = 7.2 MP. The standard floor (3 MP) sits >2×
     * below that.
     *
     * **Fixed, not derived from the device camera at runtime.** A device-derived floor is *tighter* on a
     * better camera, and tighter means more false drops — the opposite of admit-on-doubt. The device's real
     * camera resolution is information this policy deliberately declines to act on.
     */
    val imageFloor: Long,
    /** The video floor — separate and lower; see [SelectionRule.MinVideoArea] for why. */
    val videoFloor: Long,
    /**
     * The album denylist: albums whose contents are, by construction, things the member received rather than
     * took.
     *
     * **Its recall is known to be poor, and that is accepted.** On current iOS most messaging apps save
     * straight to the camera roll and create **no album at all**; only WhatsApp is confirmed to create one,
     * and only when its "Save to Camera Roll" setting is on (off by default in recent versions). This rule is
     * kept because it is cheap — cost is O(albums), not O(assets) — and strictly additive. It is **not** the
     * primary defence against received media; the resolution floors are.
     *
     * It is also a heuristic against a moving target: these titles are app-chosen strings with no registry,
     * and an app can rename its album in any release. Adding a title is a one-line change and none of them
     * are load-bearing, so the list is allowed to rot gracefully rather than being defended by machinery.
     */
    val denylistTitles: Set<String>,
) {
    /**
     * Whether [albumTitle] is denied. Matched **case-insensitively and exactly** after trimming surrounding
     * whitespace — `whatsapp` matches, `WhatsApp Backup` does not. An exact match is what keeps a user's own
     * album named e.g. "Signal Hill Hike" from being silently swallowed; the trim guards against an app that
     * pads its title, which would otherwise slip the whole album through.
     */
    fun isDenylistedAlbum(albumTitle: String): Boolean =
        denylistTitles.any { it.equals(albumTitle.trim(), ignoreCase = true) }
}

/** The one calibration every composition runs. */
val SELECTION_CALIBRATION: SelectionCalibration = SelectionCalibration(
    imageFloor = 3_000_000,
    videoFloor = 1280L * 720L,
    denylistTitles = setOf(
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
    ),
)
