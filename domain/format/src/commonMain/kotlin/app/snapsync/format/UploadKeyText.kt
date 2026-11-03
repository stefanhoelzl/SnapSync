package app.snapsync.format

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.upload.UploadKey

/**
 * An upload key's text — `<assetId>-<role>.<ext>`: the ledger row's key, the object name its bytes land under, the tag
 * a transfer carries and the manifest's file name. The role token carries no `-` though an asset id may, so a key reads
 * back by its LAST `-` before its LAST `.`. Every key a device stores was written in this form; a text that does not
 * read back is not a key, and its reader says what that means.
 */
object UploadKeyText {
    /** [key]'s text. */
    fun of(key: UploadKey): String = "${key.asset.value}-${ResourceRoleText.of(key.role)}.${key.extension}"

    /** The key [text] spells, or `null` when it spells none. */
    fun parse(text: String): UploadKey? {
        val stem = text.substringBeforeLast('.', missingDelimiterValue = "")
        val asset = AssetId.parse(stem.substringBeforeLast('-', missingDelimiterValue = "")) ?: return null
        val role = ResourceRoleText.parse(stem.substringAfterLast('-')) ?: return null
        return UploadKey.read(asset, role, text.substringAfterLast('.'))
    }
}
