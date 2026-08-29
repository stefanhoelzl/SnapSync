package app.snapsync.download

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.ports.StagedBytes
import kotlinx.cinterop.ExperimentalForeignApi
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
 * **Neither member hops.** `stagingRoot` has nothing to hop for — a container-URL lookup is a path
 * resolve, not I/O. `release` does block, one synchronous unlink per path, but where blocking work runs
 * is the composition's decision rather than this seam's (spec `module-architecture`, law "Dispatcher
 * lanes are fixed by the composition"), so it is off main either way; on that **serial** lane a hop
 * could only buy throughput, and no call site has any to gain. The two settle paths and the
 * leave/switch prune hold `DownloadController`'s mutex across the call, so what a released lane would
 * let proceed is blocked by the lock anyway; `ResetDeviceState`'s release is one awaited step of a
 * sequential teardown; and the backlog reclaim is a one-shot with nothing running beside it. Local
 * unlinks are also not the shape that makes a hop worth it — no daemon to wedge, no XPC round-trip,
 * unlike the PhotoKit seams next door.
 *
 * A missing file is not an error — `removeItemAtPath` simply reports failure, which this ignores, so
 * the operation is idempotent and a partially-completed release costs nothing.
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
        val fm = NSFileManager.defaultManager
        paths.forEach { fm.removeItemAtPath(it, error = null) }
    }

    /**
     * One `stat` per path, and no dispatcher hop — for the same reason [release] has none, only more so:
     * this reads a directory entry and touches no daemon, unlike the PhotoKit seams whose blocking is
     * what keeps them off the controller's lock.
     */
    override suspend fun allPresent(paths: List<String>): Boolean {
        if (paths.isEmpty()) return true
        val fm = NSFileManager.defaultManager
        return paths.all { fm.fileExistsAtPath(it) }
    }
}
