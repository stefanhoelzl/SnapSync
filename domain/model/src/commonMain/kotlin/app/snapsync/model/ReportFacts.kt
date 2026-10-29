package app.snapsync.model

/**
 * The bug report's reading of facts the app composition holds — pure, so the composition hands them over without
 * deciding anything.
 */

/**
 * How many photos a partial grant's selection holds — **photos**, not resources: the snapshot lists each photo's
 * resources, and a Live Photo has several. Only a partial grant has a selection; under any other the fact is
 * unsupported and the report leaves it out. A selection not read yet is failed, never zero: an empty selection and an
 * unread one are different reports.
 */
fun selectionPhotos(permission: GalleryAccess, selectedPhotos: Int?): Fact<Int> = when {
    permission != GalleryAccess.LIMITED -> Fact.Unsupported
    selectedPhotos == null -> Fact.Failed("the selection has not been read yet")
    else -> Fact.Known(selectedPhotos)
}

/** A process's footprint in whole megabytes; unsupported where the platform's is not read. */
fun MemoryFootprint?.inMegabytes(): Fact<Long> =
    this?.let { Fact.Known(it.footprintBytes / BYTES_PER_MEGABYTE) } ?: Fact.Unsupported

private const val BYTES_PER_MEGABYTE = 1_048_576L
