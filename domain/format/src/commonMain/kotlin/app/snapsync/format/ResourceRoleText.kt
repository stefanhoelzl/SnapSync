package app.snapsync.format

import app.snapsync.domain.asset.ResourceRole

/**
 * A resource role's text — the token in an object key (`<assetId>-<role>.<ext>`), the manifest and the ledger: `primary`
 * or `live`. Written once, so a key the uploader names and the role the edge stores it under cannot drift.
 */
object ResourceRoleText {
    /** [role]'s token. */
    fun of(role: ResourceRole): String = when (role) {
        ResourceRole.PRIMARY -> "primary"
        ResourceRole.LIVE -> "live"
    }

    /** The role [text] names, or `null` when it names none. */
    fun parse(text: String): ResourceRole? = ResourceRole.entries.firstOrNull { of(it) == text }
}
