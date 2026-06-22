package app.snapsync.gallery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A settable, in-memory [GalleryStatusSource]: holds its truth synchronously and re-emits on
 * [set]. Used by the desktop harness (to forge any total — discovery-lag `N > n`, overshoot
 * `n > N`, empty `N = 0`) and by integration tests; the iOS app backs the seam with PhotoKit
 * instead.
 */
class InMemoryGalleryStatusSource(initial: Int = 0) : GalleryStatusSource {
    private val _size = MutableStateFlow(initial)
    override val size: StateFlow<Int> = _size.asStateFlow()

    fun set(count: Int) {
        require(count >= 0) { "gallery size cannot be negative: $count" }
        _size.value = count
    }
}
