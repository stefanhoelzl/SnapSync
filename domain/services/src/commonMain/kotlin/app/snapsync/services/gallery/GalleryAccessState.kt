package app.snapsync.services.gallery

import app.snapsync.model.GalleryAccess
import app.snapsync.model.grantsPhotoAccess
import app.snapsync.ports.PhotoAccessStatusSource
import kotlinx.coroutines.flow.StateFlow

/**
 * **What the photo-library grant means right now** (capability `photo-access`), observed: the one place the core reads
 * the grant, over the permission port. A feature asks this what it may do — read at all ([usable]), or treat a read as
 * the whole library ([full]) — rather than comparing grant values of its own, so the two meanings are decided once.
 *
 * The grant itself is the platform's and arrives through the port: the platform re-reads it at every return to the
 * foreground, since a change made in Settings reaches the app no other way.
 */
class GalleryAccessState(private val source: PhotoAccessStatusSource) {

    /** The grant, level-triggered: its current value is always available synchronously. */
    val grant: StateFlow<GalleryAccess> get() = source.permission

    /**
     * Whether the app may read the library at all — a full or a partial grant. Under a partial grant the user's
     * selection IS the scope (capability `photo-access`), so a usable grant is not necessarily a [full] one.
     */
    val usable: Boolean get() = grant.value.grantsPhotoAccess

    /**
     * Whether a library read is the WHOLE library — a full grant, exactly. Only then is a walk authoritative (an asset
     * it does not return is gone) and the OS-driven extension registrable; under a partial grant neither holds.
     */
    val full: Boolean get() = grant.value == GalleryAccess.GRANTED
}
