package app.snapsync.ports

import app.snapsync.model.PermissionStatus

/**
 * A one-shot read of this process's current photo grant — a status read, never a request, so it can present
 * no dialog.
 *
 * The upload extension decides its own admission from it at every gate: it has no long-lived permission
 * state to observe, and its grant is read in its own process. A port and not a `() -> PermissionStatus`,
 * because the read is a platform call (law "Ports are the I/O boundary named for the need", capability
 * `docs/architecture.md`); it used to be an inline lambda in the extension's composition root.
 */
fun interface PhotoGrantRead {
    fun current(): PermissionStatus
}
