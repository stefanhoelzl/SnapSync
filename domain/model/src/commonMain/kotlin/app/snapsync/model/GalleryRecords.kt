package app.snapsync.model

/** An album's identity as the app keeps it: the gallery's id of it, opaque to the core. */
typealias AlbumId = String

/**
 * One foreign asset to rebuild in the gallery from its staged [resources], identified by the [ref] it came from — the
 * id every import handler is called with. [creationDate] (ISO-8601) is its original capture date, so it sorts by when
 * it was taken; [album] the event album it is filed into in the same commit (`null`: the camera roll only), resolved by
 * the caller before the import.
 */
class ImportRequest(
    val ref: AssetRef,
    val resources: List<StagedResource>,
    val creationDate: String,
    val album: AlbumId?,
)
