package app.snapsync.model

/**
 * The **human filename** an imported foreign photo carries in the receiving device's library
 * (capability `photo-download`): the name the capturing device gave it, falling back to the storage
 * [resourceKey] when that name is unknown.
 *
 * It exists because the platform picks a name whether or not we do. `PHAssetCreationRequest`'s
 * `addResource(with:fileURL:options:)` derives the resource's `originalFilename` from the **file URL's
 * last path component** when `options` is `nil` — and the file we hand it is staged under its storage
 * object name, `"<assetId>-<role>.<ext>"` (see [uploadKey]). So a downloaded photo used to land in the
 * library named `03C741F2-…_L0_001-primary.heic`: an internal key, and the role token in particular,
 * shown to the user as the photo's name. (Forcing proof: PhotoKit's documented default for a `nil`
 * options argument; expires only if that default changes.)
 *
 * The name is available — it rides the whole way from the uploader's manifest through the union into
 * the download store — so the import supplies it explicitly instead of letting the staging path decide.
 *
 * [originalFilename] is `""` when the uploader's manifest row was never enriched (a row predating the
 * 5.sqm migration, or one the re-join reconcile seeded from a filename listing, which carries no
 * capture detail — see `sync-ledger`). That is the one case with no human name to use, and the key is
 * the honest answer: it is what the bytes are actually called, and it is what the receiving device
 * displayed before this rule existed. Never produce an empty name — an unnamed resource is worse than
 * an ugly one.
 *
 * Collisions are deliberately not resolved here. Two devices both offering `IMG_0001.HEIC` is ordinary,
 * and the photo library keys assets by `localIdentifier`, not by name — the duplicate names are as
 * harmless there as they are in any camera roll. (The web download zip, which *does* need distinct
 * names, de-duplicates at its own edge; capability `web-event-download`.)
 */
fun importFilename(originalFilename: String, resourceKey: String): String =
    originalFilename.ifEmpty { resourceKey }
