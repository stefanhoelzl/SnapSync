package app.snapsync.gallery

import kotlinx.coroutines.flow.StateFlow

/**
 * The state port for the photo library's size: a level-triggered state holder whose current value
 * is always available synchronously, so the status projection never has to guess `N` while waiting
 * for a first read. Every value is a real, source-derived count — never a placeholder or negative
 * sentinel.
 *
 * [size] is the count of photos currently in the device photo library, used as the sync total `N`.
 * In this version it is the **whole-library** count, matching the extension's current (unfiltered)
 * discovery. When discovery later filters by capture date and media type, the same predicate MUST
 * drive this count so the two never diverge (see the gallery-status spec).
 *
 * The seam exposes the count only — never individual assets, identity, or per-asset state.
 */
interface GalleryStatusSource {
    val size: StateFlow<Int>
}
