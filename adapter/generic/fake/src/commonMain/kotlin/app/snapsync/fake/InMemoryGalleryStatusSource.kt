package app.snapsync.fake

import app.snapsync.ports.GalleryStatusSource

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An honest in-memory [GalleryStatusSource]: surfaces a constructor-injected count cell as the
 * port's [size] StateFlow. Whoever owns the cell (a test, a `:test:world` wrapper) forges the total —
 * **not-yet-counted** (`null`), discovery-lag (`N > n`), overshoot (`n > N`), counted-empty (`0`) —
 * and the fake itself exposes only the port (the honesty gate). The iOS app backs the seam with
 * PhotoKit instead.
 *
 * The cell defaults to `null`, so a test that does not state a count reproduces the **device's
 * cold-launch state** rather than a counted zero. That default is load-bearing: while this fake seeded
 * `0`, the un-counted state was unreachable from any test in the repository, which is why a status
 * projection that rendered "In sync" over unread inputs shipped twice (`SNAPSYNC-14`, `SNAPSYNC-16`).
 * A fake seeded with a count it was never given cannot fail the way the device fails.
 */
class InMemoryGalleryStatusSource(state: MutableStateFlow<Int?>) : GalleryStatusSource {

    constructor(initial: Int? = null) : this(MutableStateFlow(initial))

    override val size: StateFlow<Int?> = state.asStateFlow()
}
