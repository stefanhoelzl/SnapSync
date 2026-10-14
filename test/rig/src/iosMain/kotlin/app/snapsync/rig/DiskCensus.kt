package app.snapsync.rig

import app.snapsync.engine.LEDGER_APP_GROUP
import app.snapsync.levers.ContainerCensus
import app.snapsync.levers.DiskCensus
import app.snapsync.levers.DiskTally
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber

/**
 * What the app's files take on this device, per container: the App Group (`shared`, which iOS attributes to the app as
 * "Documents & Data") and the app's own sandbox (`app`). Bytes and file counts summed per directory down to [depth]
 * levels, and the [largest] files by size.
 *
 * A read of the real disk, and nothing else: it is how a device whose storage grew is measured without a Mac — the
 * App Group is not pullable over USB, and a TestFlight build's sandbox is not either.
 */
fun diskCensus(depth: Int, largest: Int): DiskCensus = DiskCensus(
    shared = census(
        NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path,
        depth,
        largest,
    ),
    app = census(NSHomeDirectory(), depth, largest),
)

private class Tally(var bytes: Long = 0, var files: Int = 0)

@OptIn(ExperimentalForeignApi::class)
private fun census(root: String?, depth: Int, largest: Int): ContainerCensus {
    if (root == null) return ContainerCensus(error = "no container")
    val enumerator = NSFileManager.defaultManager.enumeratorAtPath(root)
        ?: return ContainerCensus(root = root, error = "not enumerable")
    val total = Tally()
    val dirs = HashMap<String, Tally>()
    val files = ArrayList<Pair<String, Long>>()
    while (true) {
        val relative = enumerator.nextObject() as? String ?: break
        val attributes = enumerator.fileAttributes ?: continue
        if (attributes[NSFileType] != NSFileTypeRegular) continue
        val size = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
        total.bytes += size
        total.files++
        val parts = relative.split('/')
        // Every enclosing directory down to `depth` — a file at the top level counts toward the total only.
        for (n in 1..minOf(depth, parts.size - 1)) {
            dirs.getOrPut(parts.take(n).joinToString("/")) { Tally() }.let {
                it.bytes += size
                it.files++
            }
        }
        files += relative to size
    }
    return ContainerCensus(
        root = root,
        bytes = total.bytes,
        files = total.files,
        dirs = dirs.entries.sortedByDescending { it.value.bytes }.map { (path, tally) ->
            DiskTally(
                path,
                tally.bytes,
                tally.files,
            )
        },
        largest = files.sortedByDescending { it.second }.take(largest).map { (path, size) -> DiskTally(path, size) },
    )
}
