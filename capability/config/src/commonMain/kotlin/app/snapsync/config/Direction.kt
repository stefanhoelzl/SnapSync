package app.snapsync.config

/**
 * This device's chosen **participation direction** for a joined event (capability
 * `join-direction-mode`), fixed at join and persisted on [EventConfig]. It masks two independently
 * wired arms:
 * - the **upload** arm (the background-upload producer) runs only when [includesUpload];
 * - the **download** arm (the `DownloadController` reconcile) runs only when [includesDownload].
 *
 * [Both] is the default (today's bidirectional behavior) and the value a config persisted before this
 * field existed decodes to. [wire] is the compact token used **only** by the dev/test deeplink override
 * ([EventLinkPayload.direction]); the persisted [EventConfig] serializes the enum by its constant name.
 */
enum class Direction(val wire: String) {
    Both("both"),
    UploadOnly("upload"),
    DownloadOnly("download");

    /** The device contributes its own photos (producer enabled) — true for everything but [DownloadOnly]. */
    val includesUpload: Boolean
        get() = this != DownloadOnly

    /** The device imports others' photos (reconcile runs) — true for everything but [UploadOnly]. */
    val includesDownload: Boolean
        get() = this != UploadOnly

    companion object {
        /** Maps a dev/test deeplink [wire] token to a [Direction], or `null` if it is not a known token. */
        fun fromWire(wire: String): Direction? = entries.firstOrNull { it.wire == wire }
    }
}
