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

    /** Delete the files at [paths]. Missing files are not an error; the operation is idempotent. */
    suspend fun release(paths: List<String>)

    companion object {
        /** Releases nothing, for compositions with no staging of their own (the desktop harnesses). */
        val None: StagedBytes = object : StagedBytes {
            override suspend fun release(paths: List<String>) = Unit
        }
    }
}
