package app.snapsync.domain.asset

/**
 * What one resource of an asset is: its primary (the photo or video itself), or a live photo's motion. An asset's
 * resources are told apart by it in the object keys, the device manifest and the ledger — its text there is
 * [app.snapsync.format.ResourceRoleText]'s.
 */
enum class ResourceRole {
    PRIMARY,
    LIVE,
}
