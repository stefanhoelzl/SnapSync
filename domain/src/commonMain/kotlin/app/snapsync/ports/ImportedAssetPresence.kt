package app.snapsync.ports

import app.snapsync.model.AssetPresence

/**
 * Asks the photo library whether assets this device created still exist (capability `photo-download`).
 *
 * The need, not the technology: an import that recorded its created asset but never recorded a
 * confirmation must be adjudicated before anything creates a second one. On iOS the answer comes from a
 * `PHAsset` fetch by local identifier under a full grant, and from the held selection snapshot under a
 * partial one — but the feature asks one question and reads one verdict, because *which source can
 * answer* is a property of the current photo-access grant, and choosing between sources by another
 * port's state is composition's job (the same shape as the candidate source).
 *
 * **Batched.** One call per import pass, for every unconfirmed row at once — and no call at all when no
 * row carries a marker, which is the ordinary case. The platform query takes a list anyway, so asking
 * per row would buy nothing and cost a round-trip each.
 *
 * **The identifiers are the store's normalized form** (`/`→`_`, `model/`'s `normalizeAssetId`) — the same
 * form `createdLocalId` and the upload keys use. An implementation that must talk to a platform in raw
 * form converts on the way in and back on the way out; callers never see the raw shape.
 *
 * ⚠️ **Implementations own their dispatcher hop.** The iOS query is a synchronous XPC round-trip that
 * blocks its thread, and no timeout can abandon it (cancellation is cooperative). It therefore must not
 * run on a caller's thread of convenience, and the guard calls it outside the download controller's lock
 * — a stalled photo library must park one background thread, never the lock every reconcile, import,
 * leave and switch queues behind.
 */
interface ImportedAssetPresence {

    /**
     * The verdict for each of [localIds]. The result SHALL carry an entry for every id asked about; a
     * missing entry and [AssetPresence.UNKNOWN] mean the same thing to callers, and returning the entry
     * is the honest form.
     */
    suspend fun presence(localIds: Set<String>): Map<String, AssetPresence>

    companion object {
        /** Answers nothing, for compositions that never adjudicate (the desktop harnesses). */
        val Unanswerable: ImportedAssetPresence = object : ImportedAssetPresence {
            override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> =
                localIds.associateWith { AssetPresence.UNKNOWN }
        }
    }
}
