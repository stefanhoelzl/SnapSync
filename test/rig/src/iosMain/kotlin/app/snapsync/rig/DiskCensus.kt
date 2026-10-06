package app.snapsync.rig

import app.snapsync.engine.LEDGER_APP_GROUP
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber

/**
 * `POST /device/disk` — what the app's files take on this device, per container: the App Group (`shared`, which iOS
 * attributes to the app as "Documents & Data") and the app's own sandbox (`app`). Bytes and file counts summed per
 * directory down to [depth] levels, and the [largest] files by size.
 *
 * A read of the real disk, and nothing else: it is how a device whose storage grew is measured without a Mac — the
 * App Group is not pullable over USB, and a TestFlight build's sandbox is not either.
 */
fun diskCommand(): RigCommand = RigCommand { params, _ ->
    val depth = params["depth"]?.toIntOrNull()?.coerceIn(1, MAX_DEPTH) ?: DEFAULT_DEPTH
    val largest = params["largest"]?.toIntOrNull()?.coerceIn(0, MAX_LARGEST) ?: DEFAULT_LARGEST
    val shared = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(LEDGER_APP_GROUP)?.path
    CommandResult.ok(
        buildJsonObject {
            putJsonObject("shared") { census(shared, depth, largest) }
            putJsonObject("app") { census(NSHomeDirectory(), depth, largest) }
        }.toString(),
    )
}

private class Tally(var bytes: Long = 0, var files: Int = 0)

@OptIn(ExperimentalForeignApi::class)
private fun kotlinx.serialization.json.JsonObjectBuilder.census(root: String?, depth: Int, largest: Int) {
    if (root == null) {
        put("error", "no container")
        return
    }
    put("root", root)
    val enumerator = NSFileManager.defaultManager.enumeratorAtPath(root) ?: run {
        put("error", "not enumerable")
        return
    }
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
            dirs.getOrPut(parts.take(n).joinToString("/")) { Tally() }.let { it.bytes += size; it.files++ }
        }
        files += relative to size
    }
    put("bytes", total.bytes)
    put("files", total.files)
    putJsonArray("dirs") {
        dirs.entries.sortedByDescending { it.value.bytes }.forEach { (path, tally) ->
            add(buildJsonObject { put("path", path); put("bytes", tally.bytes); put("files", tally.files) })
        }
    }
    putJsonArray("largest") {
        files.sortedByDescending { it.second }.take(largest).forEach { (path, size) ->
            add(buildJsonObject { put("path", JsonPrimitive(path)); put("bytes", size) })
        }
    }
}

private const val DEFAULT_DEPTH = 2
private const val MAX_DEPTH = 6
private const val DEFAULT_LARGEST = 20
private const val MAX_LARGEST = 500
