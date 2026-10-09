package app.snapsync.model

/**
 * This device's chosen **participation direction** for a joined event, first set at join and persisted on [EventConfig]
 * — changeable in place afterward from the event's settings. It masks two independently wired arms:
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
    DownloadOnly("download"),

    /**
     * Neither arm runs: the member stays in the event and shares and receives nothing. Reached only by switching both
     * off in the event's settings — a join always carries a direction, so [fromWire] refuses its token and no link can
     * set it.
     */
    Neither("none"),
    ;

    /** The device contributes its own photos (producer enabled) — [Both] and [UploadOnly]. */
    val includesUpload: Boolean
        get() = this == Both || this == UploadOnly

    /** The device imports others' photos (reconcile runs) — [Both] and [DownloadOnly]. */
    val includesDownload: Boolean
        get() = this == Both || this == DownloadOnly

    companion object {
        /**
         * Maps a dev/test deeplink [wire] token to a [Direction], or `null` if it is not a known token.
         *
         * Absence: null means the token is not a direction this build knows — an unrecognised or absent
         * wire value are one answer, because the caller's response to either is the same (reject the
         * link / fall back to the default). A pure lookup over a closed set has no failure mode to hide.
         */
        fun fromWire(wire: String): Direction? = entries.firstOrNull { it.wire == wire && it != Neither }
    }
}
