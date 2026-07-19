package app.snapsync.fake

import app.snapsync.ports.GalleryStatusSource

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An honest in-memory [GalleryStatusSource]: surfaces a constructor-injected count cell as the
 * port's [size] StateFlow. Whoever owns the cell forges the total (discovery-lag `N > n`, overshoot
 * `n > N`, empty `N = 0`); the fake itself exposes only the port (the honesty gate). The iOS app
 * backs the seam with PhotoKit instead.
 */
class InMemoryGalleryStatusSource(state: MutableStateFlow<Int>) : GalleryStatusSource {

    constructor(initial: Int = 0) : this(MutableStateFlow(initial))

    override val size: StateFlow<Int> = state.asStateFlow()
}
