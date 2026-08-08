package app.snapsync.model

/**
 * What `SNAPSYNC_WIPE_GALLERY` may ask for (capability `ios-app-shell`, dev/test launch trigger).
 *
 * The two flags are the WHOLE decision, so the untested shell reads booleans instead of switching on a
 * token: the mapping token → what-gets-deleted is settled here, under `commonTest` on JVM **and** the
 * simulator, exactly like every other launch directive's parse.
 *
 * - [ASSETS] — every asset the fetch returns (photos *and* videos). Under a LIMITED grant that is the
 *   user's hand-picked selection and nothing else, because it is all PhotoKit will return.
 * - [ALBUMS] — the album/folder *collections* only. Deleting a collection never deletes its members, so
 *   this scope leaves every photo in place.
 * - [ALL] — both, in one change block. That is one transaction, not one prompt: iOS confirms **per
 *   kind**, so an [ALL] wipe asks twice (measured on device — see `DevGalleryWiper`).
 */
enum class WipeScope(
    val token: String,
    val includesAssets: Boolean,
    val includesAlbums: Boolean,
) {
    ALL("all", includesAssets = true, includesAlbums = true),
    ASSETS("assets", includesAssets = true, includesAlbums = false),
    ALBUMS("albums", includesAssets = false, includesAlbums = true),
    ;

    companion object {
        /** The recognized tokens, for the diagnostic that names them when a value is rejected. */
        val tokens: String get() = entries.joinToString("|") { it.token }
    }
}

/**
 * The parsed `SNAPSYNC_WIPE_GALLERY` value — an **irreversible** dev trigger, so its absence rule is
 * spelled out rather than collapsed (law "Absence is never silent"): unset and unrecognized are
 * different answers, and only a recognized token deletes anything.
 *
 * Every case carries its own [plan] line, so the shell logs one string and branches on nothing: a launch
 * that wipes nothing still says *why* it wiped nothing, which is the difference between "the operator
 * did not ask" and "the operator asked and I could not tell what for".
 */
sealed interface WipeRequest {

    /** Whether assets are in scope for deletion. */
    val includesAssets: Boolean

    /** Whether album/folder collections are in scope for deletion. */
    val includesAlbums: Boolean

    /** The one line the shell logs before doing anything — the trigger's own account of itself. */
    val plan: String

    /** Whether anything at all is to be deleted; the shell's single early return. */
    val wipesAnything: Boolean get() = includesAssets || includesAlbums

    /** The variable was not set: the production case, and the common dev case. */
    data object None : WipeRequest {
        override val includesAssets: Boolean = false
        override val includesAlbums: Boolean = false
        override val plan: String = "wipe: SNAPSYNC_WIPE_GALLERY unset — wiping nothing"
    }

    /** A recognized scope. */
    data class Wipe(val scope: WipeScope) : WipeRequest {
        override val includesAssets: Boolean get() = scope.includesAssets
        override val includesAlbums: Boolean get() = scope.includesAlbums
        override val plan: String
            get() = "wipe: SNAPSYNC_WIPE_GALLERY=${scope.token} — assets=${scope.includesAssets} " +
                "albums=${scope.includesAlbums}"
    }

    /**
     * The variable was set to something that is not a scope — including the blank string a bare
     * `--env SNAPSYNC_WIPE_GALLERY=` produces. Deliberately NOT treated as a request: this trigger is
     * unrecoverable, so a typo must refuse loudly rather than pick a scope on the operator's behalf.
     */
    data class Unrecognized(val raw: String) : WipeRequest {
        override val includesAssets: Boolean = false
        override val includesAlbums: Boolean = false
        override val plan: String
            get() = "wipe: SNAPSYNC_WIPE_GALLERY=$raw is not ${WipeScope.tokens} — wiping nothing"
    }
}

/**
 * Parse a raw `SNAPSYNC_WIPE_GALLERY` value. Surrounding whitespace is trimmed and matching is
 * case-insensitive (a shell-quoted value picks up neither meaning), but nothing else is guessed.
 */
fun wipeRequest(raw: String?): WipeRequest {
    val value = raw ?: return WipeRequest.None
    val scope = WipeScope.entries.firstOrNull { it.token.equals(value.trim(), ignoreCase = true) }
        ?: return WipeRequest.Unrecognized(value)
    return WipeRequest.Wipe(scope)
}
