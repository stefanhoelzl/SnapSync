package app.snapsync.data.legacy

import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.ports.Preferences

/**
 * Where 0.4 kept the shared records — runtime identity: an updated device still holds them there — and their removal.
 */
internal class LegacyHomes(private val files: Files, private val preferences: Preferences) {
    private val log = Log("LegacyRecords")

    /**
     * A copied file-held source's legacy file: one not deleted now is deleted at a later open, its mark outranking
     * it.
     */
    fun delete(path: String) = when (val deleted = files.delete(FileArea.SHARED, path)) {
        is FileResult.Ok, FileResult.NotFound -> Unit
        FileResult.AreaUnavailable, is FileResult.Denied, is FileResult.Failed -> log.unlabelled.w(
            "legacy home copied but not deleted — its mark outranks it; deleted at a later open",
            "path" of path,
            "deleted" of deleted,
        )
    }

    /** The event-album map's two keys. */
    fun forgetAlbums() {
        preferences.remove(ALBUM_MAP_KEY)
        preferences.remove(ALBUM_FILLED_KEY)
    }

    companion object {
        const val CONFIG_FILE_NAME: String = "eventconfig.json"

        /** The manifest record's legacy directory and file. */
        private const val DEVICE_MANIFEST_DIR = "device-manifest"
        private const val LAST_UPLOADED_FILE = "last-uploaded.json"
        const val LAST_UPLOADED: String = "$DEVICE_MANIFEST_DIR/$LAST_UPLOADED_FILE"

        /** The pending leaves' legacy file. */
        const val PENDING_LEAVES: String = "membership/pending-leaves.txt"

        /** The event-album map's legacy Preferences key. */
        const val ALBUM_MAP_KEY: String = "app.snapsync.album.map"

        /** The event-album filled marks' legacy Preferences key. */
        const val ALBUM_FILLED_KEY: String = "app.snapsync.album.filled"
    }
}
