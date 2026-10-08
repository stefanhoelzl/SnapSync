package app.snapsync.ports

import app.snapsync.model.GalleryAccess

/**
 * A one-shot read of this process's current photo grant — a status read, never a request, so it can present
 * no dialog. The photo library answers it ([GalleryReader] extends it); a consumer that needs only the grant takes
 * this, so it cannot reach the library through it.
 *
 * The upload extension decides its own admission from it at every gate: it has no long-lived permission
 * state to observe, and its grant is read in its own process. A port and not a `() -> GalleryAccess`,
 * because the read is a platform call (law "Ports are the I/O boundary named for the need", capability
 * `docs/architecture.md`).
 */
fun interface PhotoGrantRead : Port {

    /** The grant as the platform reports it right now. Cheap, synchronous, and never raises a dialog. */
    fun access(): GalleryAccess
}
