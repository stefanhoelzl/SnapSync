package app.snapsync.model

/**
 * The bug report's reading of facts the app composition holds (capability `privacy-security`) — pure, so the
 * composition hands them over without deciding anything.
 */

/** The device id as a report writes it: the id, or why there is none. Never a mint — see `PersistedDeviceIdentity.current`. */
fun DeviceIdResult.asFact(): Fact<String> = when (this) {
    is DeviceIdResult.Id -> Fact.Known(value)
    is DeviceIdResult.Unavailable -> Fact.Failed("the secure store could not be read: $detail")
    DeviceIdResult.AbsentNotMintable -> Fact.Failed("no device id stored yet")
}

/**
 * How many photos a partial grant's selection holds — **photos**, not resources: the snapshot lists each photo's
 * resources, and a Live Photo has several. Only a partial grant has a selection; under any other the fact is
 * unsupported and the report leaves it out. A selection not read yet is failed, never zero: an empty selection and an
 * unread one are different reports.
 */
fun selectionPhotos(permission: GalleryAccess, snapshot: List<Resource>?): Fact<Int> = when {
    permission != GalleryAccess.LIMITED -> Fact.Unsupported
    snapshot == null -> Fact.Failed("the selection has not been read yet")
    else -> Fact.Known(snapshot.distinctBy { it.assetId }.size)
}

/** A process's footprint in whole megabytes; unsupported where the platform's is not read. */
fun MemoryFootprint?.inMegabytes(): Fact<Long> =
    this?.let { Fact.Known(it.footprintBytes / BYTES_PER_MEGABYTE) } ?: Fact.Unsupported

private const val BYTES_PER_MEGABYTE = 1_048_576L
