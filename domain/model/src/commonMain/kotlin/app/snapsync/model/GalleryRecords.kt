package app.snapsync.model

/**
 * An asset's identity as the gallery hands it out — **opaque to the core**: it is stored, compared and handed
 * back, never parsed. Until raw asset ids land (11i) the iOS adapter hands out the normalized form
 * (`/`→`_`, [normalizeAssetId]) that the ledger and the upload keys carry, and converts back itself.
 */
typealias AssetId = String

/** An album's identity as the gallery hands it out; opaque to the core, like [AssetId]. */
typealias AlbumId = String

/**
 * What a gallery read produced: the answer, or the statement that the gallery cannot be read at all right now
 * (`docs/architecture.md`, "Absence is never silent").
 *
 * [NotReadable] is the platform's own fact — no grant that lets this process see the library — and never
 * "nothing matched": a read that found nothing is `Read(empty)`, a counted zero. What a read under a
 * **partial** grant means (the selection, not the library) is for the caller to judge; the gallery answers
 * with what the platform shows.
 */
sealed interface GalleryRead<out T> {
    data class Read<out T>(val value: T) : GalleryRead<T>

    data object NotReadable : GalleryRead<Nothing>
}

/** One user-created album: an album the member or another app made (never a system-made smart album). */
data class AlbumRecord(val id: AlbumId, val title: String)

/**
 * The whole current selection under a **partial** grant (capability `photo-access`), every asset with its
 * resources: a snapshot, never a delta — platform change details are unreliable for bulk changes (measured: a
 * batched create reports no itemized inserts), so a consumer reloads and lets the ledger deduplicate. It carries the
 * resources read with it because under a partial grant the core may read only on the cold-launch baseline and on an
 * observer emission, never go back to the gallery on its own.
 */
class SelectionSnapshot(val assets: List<RawAsset>)

/**
 * One foreign asset to rebuild in the gallery from its staged [resources] (capability `receiving-photos`),
 * identified by the [ref] it came from — the id every import handler is called with. [creationDate] (ISO-8601) is
 * its original capture date, so it sorts by when it was taken; [album] the event album it is filed into in the same
 * commit (`null`: the camera roll only), resolved by the caller before the import.
 */
class ImportRequest(
    val ref: AssetRef,
    val resources: List<StagedResource>,
    val creationDate: String,
    val album: AlbumId?,
)
