package app.snapsync.services.manifest

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.Files
import co.touchlab.kermit.Logger

/** The manifest skip record's directory in the shared area — runtime identity (`docs/architecture.md`). */
const val DEVICE_MANIFEST_DIR: String = "device-manifest"

/** The manifest skip record's file name — runtime identity (`docs/architecture.md`). */
const val LAST_UPLOADED_FILE: String = "last-uploaded.json"

private const val LAST_UPLOADED: String = "$DEVICE_MANIFEST_DIR/$LAST_UPLOADED_FILE"

/**
 * The device manifest's skip record (capability `photo-sharing`): [DeviceManifestStore] over one file in the
 * shared area, so the app and the extension agree on what was last published.
 *
 * Every non-answer reads as **not believed** — the record is a skip optimisation, and "no record" only costs a
 * republish — so an unreadable record is `null`, a failed write leaves the old one (logged), and a failed delete
 * leaves the file believed (logged): the manifest then republishes, which is the safe direction.
 */
class DeviceManifestService(
    private val files: Files,
    private val log: Logger = Logger.withTag("deviceManifest"),
) : DeviceManifestStore {

    override fun loadLastUploaded(): String? = when (val read = files.read(FileArea.SHARED, LAST_UPLOADED)) {
        is FileResult.Ok -> read.value.decodeToString()
        FileResult.NotFound -> null
        else -> null.also { log.w { "the manifest record is unreadable ($read) — not believed" } }
    }

    override fun saveLastUploaded(json: String) {
        val written = files.write(FileArea.SHARED, LAST_UPLOADED, json.encodeToByteArray())
        if (written !is FileResult.Ok) log.w { "the manifest record was not written ($written)" }
    }

    /** Deletes the file: absent and "not believed" are the same state, which [loadLastUploaded] reads. */
    override fun clearLastUploaded() {
        when (val deleted = files.delete(FileArea.SHARED, LAST_UPLOADED)) {
            is FileResult.Ok, FileResult.NotFound -> Unit
            else -> log.w { "clearLastUploaded: the file stays, so the manifest is still believed ($deleted)" }
        }
    }
}
