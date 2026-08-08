package app.snapsync.ports

/**
 * The command port for permission: fire-and-forget, no return values, no suspension.
 * Any status change a command causes arrives exclusively via [PhotoAccessStatusSource] —
 * permission also changes without a request (the user flips it in system settings), so
 * that path is the only one. Implementations typically also implement the source as one
 * platform adapter; consumers depend on each port separately.
 */
interface PhotoAccessRequester {
    /** Triggers the platform permission dialog. Duplicate calls are harmless. */
    fun request()

    /** Opens the platform's settings surface for this app's permissions. */
    fun openSettings()

    /**
     * Presents the platform's surface for revising a **partial** grant's hand-picked selection
     * (capability `limited-photo-access`) — on iOS, PhotoKit's limited-library picker.
     *
     * It belongs on this port and not on one of its own because it is the same need as [openSettings]:
     * both hand the user back to the system to widen what this app may see, and both are answered only
     * through [PhotoAccessStatusSource] / the selection-change seam. Under a partial grant the user's
     * selection **is** the membership's own-photo scope, so without this the app suppresses iOS's
     * automatic "Select More Photos" alert (`PHPhotoLibraryPreventAutomaticLimitedAccessAlert`) and
     * offers nothing in its place — a limited member with no way to widen their selection at all.
     *
     * This was `AppPorts.presentPhotoPicker: () -> Unit`, a function-typed field the shell filled with a
     * top-level UIKit presenter — a platform touch handed to the core past the port boundary (spec
     * `module-architecture`, "Ports are the I/O boundary named for the need"), and defaulted inert, so a
     * composition that simply forgot to wire it was indistinguishable from one that had.
     *
     * Fire-and-forget, like the rest of this port: the resulting selection arrives via
     * [PhotoSelectionChangeSource], never as a return value here.
     */
    fun choosePhotos()
}
