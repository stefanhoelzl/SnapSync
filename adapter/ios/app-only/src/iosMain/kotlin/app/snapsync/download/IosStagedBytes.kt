package app.snapsync.download

import app.snapsync.ports.StagedBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager

/**
 * The iOS [StagedBytes]: deletes the App-Group staging files of settled rows (capability
 * `download-store`).
 *
 * `app-only` by linkage — the extension never downloads, so it must stay unable to link this.
 *
 * Owns its dispatcher hop: file removal is synchronous I/O, and a sync-I/O port impl hops because only
 * it knows the call blocks. A missing file is not an error — `removeItemAtPath` simply reports failure,
 * which this ignores, so the operation is idempotent and a partially-completed release costs nothing.
 */
@OptIn(ExperimentalForeignApi::class)
class IosStagedBytes : StagedBytes {

    override suspend fun release(paths: List<String>) {
        if (paths.isEmpty()) return
        withContext(Dispatchers.Default) {
            val fm = NSFileManager.defaultManager
            paths.forEach { fm.removeItemAtPath(it, error = null) }
        }
    }
}
