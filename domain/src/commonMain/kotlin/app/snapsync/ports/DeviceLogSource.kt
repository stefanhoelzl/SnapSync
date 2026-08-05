package app.snapsync.ports

/**
 * Reading back what this device logged (capability `diagnostic-logging`): the **tail** of a process's
 * device log, bounded in bytes.
 *
 * Named for the need — anything that can hand back the end of a log can seat it. The bound is the
 * contract, not an optimization: a log is up to 10 MB and the diagnostic dump that consumes this must
 * fit inside a single reporting event, so an unbounded read would be a promise no caller can keep.
 *
 * Implementations SHALL:
 * - return **at most** [maxBytes] bytes, cut at a **line boundary** so the first line is whole (a
 *   dump whose first line begins mid-token reads as corruption);
 * - read only the **current** file, never a rolled `.1` sibling — by the time anyone dumps, a roll
 *   file is stale, and including it would halve the live tail;
 * - return `null` when the log does not exist or cannot be read, never a partial lie.
 */
interface DeviceLogSource {

    /** Which process's log to read. Both live on this device; only one is this process's own. */
    enum class Process { APP, EXTENSION }

    /**
     * The last [maxBytes]-ish bytes of [process]'s log, line-aligned; `null` if unreadable.
     *
     * Absence: null covers "no such log on this device" and "could not read it", and the two are
     * identical downstream — the dump ships without that section and the reader sees it reported
     * absent. Nothing branches on which, so the collapse costs no information anyone acts on.
     */
    suspend fun tail(process: Process, maxBytes: Int): String?

    companion object {
        /** No device logs exist — every composition off a device (world, harnesses, tests). */
        val None: DeviceLogSource = object : DeviceLogSource {
            override suspend fun tail(process: Process, maxBytes: Int): String? = null
        }
    }
}
