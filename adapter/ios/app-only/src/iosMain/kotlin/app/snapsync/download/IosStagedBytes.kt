package app.snapsync.download

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.ports.StagedBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager

/**
 * The iOS [StagedBytes]: names the App-Group staging directory downloaded bytes land in, and deletes
 * the files of settled rows (capability `download-store`).
 *
 * `app-only` by linkage — the extension never downloads, so it must stay unable to link this.
 *
 * [stagingRoot] resolves the shared App-Group container, the same one the ledger, the download store
 * and the config file live in. It was an inline lambda in the composition root
 * (`AppPorts.downloadStagingRoot: () -> String`) — a platform container lookup handed to the core past
 * the port boundary (spec `module-architecture`, "Ports are the I/O boundary named for the need"),
 * beside the port that already owned those very files' lifetimes. Two halves of one concern with only
 * one of them declared: nothing structural stopped the two from naming different directories, which
 * would leave every staged photo permanently unreleasable.
 *
 * A missing container is an **error, not an empty answer**: without it there is nowhere durable to
 * stage, and inventing a path would put every downloaded photo somewhere the release side cannot find
 * (spec `module-architecture`, "Absence is never silent"). Resolved at first download, never at
 * composition, so a locked background launch is not forced into it.
 *
 * `release` owns its dispatcher hop: file removal is synchronous I/O, and a sync-I/O port impl hops
 * because only it knows the call blocks. A missing file is not an error — `removeItemAtPath` simply
 * reports failure, which this ignores, so the operation is idempotent and a partially-completed release
 * costs nothing. `stagingRoot` does not hop: a container-URL lookup is a path resolve, not I/O.
 */
@OptIn(ExperimentalForeignApi::class)
class IosStagedBytes : StagedBytes {

    override fun stagingRoot(): String {
        val container = NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path
            ?: error("App Group container '$LEDGER_APP_GROUP' unavailable")
        return "$container/download-staging"
    }

    override suspend fun release(paths: List<String>) {
        if (paths.isEmpty()) return
        withContext(Dispatchers.Default) {
            val fm = NSFileManager.defaultManager
            paths.forEach { fm.removeItemAtPath(it, error = null) }
        }
    }
}
