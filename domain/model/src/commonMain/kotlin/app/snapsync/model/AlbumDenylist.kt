package app.snapsync.model

/**
 * The album denylist (capability `photo-selection-policy`): albums whose contents are, by construction,
 * things the member received rather than took.
 *
 * **Its recall is known to be poor, and that is accepted.** On current iOS most messaging apps save straight
 * to the camera roll and create **no album at all**; only WhatsApp is confirmed to create one, and only when
 * its "Save to Camera Roll" setting is on (off by default in recent versions). This rule is kept because it
 * is cheap — cost is O(albums), not O(assets) — and strictly additive. It is **not** the primary defence
 * against received media; the resolution floors are (see `:domain:gallery`'s `SelectionPolicy`).
 *
 * It is also a heuristic against a moving target: these titles are app-chosen strings with no registry, and
 * an app can rename its album in any release. Adding a title is a one-line change and none of them are
 * load-bearing, so the list is allowed to rot gracefully rather than being defended by machinery.
 *
 * Matched **case-insensitively and exactly** — `whatsapp` matches, `WhatsApp Backup` does not. An exact match
 * is what keeps a user's own album named e.g. "Signal Hill Hike" from being silently swallowed.
 */
val DENYLISTED_ALBUM_TITLES: Set<String> = setOf(
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
)

/**
 * Whether [albumTitle] is denied. Case-insensitive exact match after trimming surrounding whitespace — the
 * trim guards against an app that pads its title, which would otherwise slip the whole album through.
 */
fun isDenylistedAlbum(albumTitle: String): Boolean =
    DENYLISTED_ALBUM_TITLES.any { it.equals(albumTitle.trim(), ignoreCase = true) }
