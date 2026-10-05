package app.snapsync.model

/**
 * The pages the app menu links to (capability `sync-status`), on SnapSync's own site.
 *
 * Built from [LINK_ORIGIN] — the origin invite links are anchored to — rather than from build constants of their own:
 * the site that serves `/join` is the site that publishes the Privacy Policy, so one origin answers both, and a dev
 * build links to its own deployment's pages exactly as its invites do.
 */
enum class AppLink {
    WEBSITE,

    /** The site's privacy section (capability `web-site`), the same anchor the store listings name. */
    PRIVACY_POLICY,
    ;

    val url: String
        get() = when (this) {
            WEBSITE -> LINK_ORIGIN
            PRIVACY_POLICY -> "$LINK_ORIGIN/#privacy"
        }
}
