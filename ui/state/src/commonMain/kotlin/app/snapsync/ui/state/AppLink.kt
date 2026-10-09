package app.snapsync.ui.state

/**
 * The pages the app menu links to, on SnapSync's own site. Which address each is, is the
 * app's: the origin invite links are anchored to, so a dev build links to its own deployment's pages exactly as its
 * invites do.
 */
enum class AppLink {
    WEBSITE,

    /** The site's privacy section, the same anchor the store listings name. */
    PRIVACY_POLICY,
}
