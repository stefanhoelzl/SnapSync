package app.snapsync.model

/** Outcome of a per-asset import. */
sealed interface ImportResult {
    /** The asset was created; [createdLocalId] is its sanitized local identifier (the suppression handle). */
    data class Imported(val createdLocalId: AssetId) : ImportResult

    /**
     * The import did not complete.
     *
     * This is an OBSERVED outcome — the library reported the change failed — and it is the only kind of
     * failure this seam reports. There is deliberately no "we stopped waiting" case: nothing bounds an
     * import in time any more (capability `receiving-photos`), because a wall-clock bound expires against
     * transactions that are alive, and the wake it would otherwise protect is bounded by the operating system's
     * expiry instead. An import that never reports never returns, and stays claimed for the life of the process.
     *
     * [consumedResources] is what separates a failure worth retrying from one that never can be, and it is
     * a **platform fact the adapter observes**, not an interpretation the caller may make. The photo library
     * takes a resource's file when it INGESTS it — before validating the content and before the commit — so
     * a rejection of the file's content leaves nothing on disk to retry from, while a rejection of the
     * request's shape leaves every file untouched. Measured 2026-08-26 (iOS 26.2): `InvalidResource`
     * consumed the file with no asset created; `ChangeNotSupported` consumed nothing.
     *
     * `true` therefore means the row must SETTLE — a staged resource is never re-downloaded, so retrying it
     * imports from files that no longer exist, forever. `false` means the bytes are intact and a later
     * trigger should try again. An adapter that cannot tell SHALL answer `false`: retrying costs a
     * transaction, settling wrongly costs the photo.
     *
     * [placeholder] is the created-asset marker the change block reported before the change failed, or `null` when
     * it reported none (a change refused before its block ran). It is what the settle clears: the marker points at
     * an asset that does not exist, and a row still holding it would be skipped as "already created" forever.
     */
    data class Failed(
        val message: String,
        val consumedResources: Boolean = false,
        val placeholder: AssetId? = null,
    ) : ImportResult
}
