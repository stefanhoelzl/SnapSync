package app.snapsync.ports

/**
 * Releases the downloaded bytes staged for a foreign asset (capability `download-store`).
 *
 * Nothing released them before this port existed, so every photo a device received was stored twice —
 * once as the library asset, once as its staged file — permanently, in a location the OS never reclaims.
 * One field device reported 102 imports; at the sizes the event union shows, that is a couple of hundred
 * megabytes of dead weight, growing with every event.
 *
 * **When, and only when.** Bytes may be released once the row referencing them is *settled*: its import
 * is confirmed, or its row is about to be dropped. They SHALL NOT be released while an import is
 * unconfirmed, failed, or abandoned on its deadline — those bytes are the **only** source for the retry,
 * because a resource already recorded as staged is never re-downloaded. Releasing early does not cost a
 * retry; it loses the photo permanently and silently.
 *
 * **The order is the same discipline as the marker write.** Release *after* the confirming write commits,
 * never before: a crash between them must leave extra bytes (which a later pass reclaims), never a row
 * pointing at bytes that are gone.
 *
 * Best-effort by contract — a failure to delete is logged and ignored. Freeing disk is never worth
 * failing an import over, and anything left behind is attributable to a settled row and collected later.
 */
interface StagedBytes {

    /**
     * The durable directory staged bytes live under — the other end of [release], and the reason this
     * member is here rather than on a port of its own: one owner decides where staging lives *and* what
     * may be reclaimed from it, so the two can never name different directories.
     *
     * This was `AppPorts.downloadStagingRoot: () -> String`, a function-typed field the shell filled with
     * an inline App-Group container lookup — a platform read handed straight to the core past the port
     * boundary (spec `module-architecture`, "Ports are the I/O boundary named for the need"). Its type
     * said nothing: `() -> String` is exactly the type of `deviceId`, which returns a value the
     * composition already holds. Only a port makes the difference legible.
     *
     * Resolved lazily, at first download rather than at composition, because on iOS it is a container
     * lookup that a locked background launch must not be forced into early. It is NOT suspend: the
     * lookup is a path resolve, not I/O, and making it suspend would push `AppCore`'s download-jobs
     * assembly out of the lazy web whose construction timing is load-bearing.
     */
    fun stagingRoot(): String

    /** Delete the files at [paths]. Missing files are not an error; the operation is idempotent. */
    suspend fun release(paths: List<String>)

    companion object {
        /**
         * Stages nothing and releases nothing — the default for the features that only ever *reclaim*
         * (`DownloadController`, `ResetDeviceState`), where a composition with no staging of its own
         * has nothing to free and failing to free disk is harmless.
         *
         * [stagingRoot] **throws** rather than answering, and that asymmetry is the point: "release
         * nothing" is a safe no-op, but "stage into a directory nobody chose" is not — it would write
         * every downloaded photo somewhere the release side does not know about, silently and
         * permanently (spec `module-architecture`, "Absence is never silent"). Unreachable in practice:
         * `AppPorts.stagedBytes` is a required input precisely so no composition that downloads can
         * arrive here.
         */
        val None: StagedBytes = object : StagedBytes {
            override fun stagingRoot(): String =
                error("StagedBytes.None stages nothing — a composition that downloads must supply a real StagedBytes")

            override suspend fun release(paths: List<String>) = Unit
        }
    }
}
