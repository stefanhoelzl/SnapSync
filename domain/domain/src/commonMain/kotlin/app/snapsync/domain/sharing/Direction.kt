package app.snapsync.domain.sharing

/**
 * This device's chosen **participation direction** for a joined event, first set at join and changeable in place
 * afterward from the event's settings. It masks two independently wired arms:
 * - the **upload** arm (the background-upload producer) runs only when [includesUpload];
 * - the **download** arm (the `DownloadController` reconcile) runs only when [includesDownload].
 *
 * [Both] is the default (today's bidirectional behavior) and the value a membership stored before this field existed
 * reads as. A membership stores a direction by its name.
 */
enum class Direction {
    Both,
    UploadOnly,
    DownloadOnly,

    /**
     * Neither arm runs: the member stays in the event and shares and receives nothing. Reached only by switching both
     * off in the event's settings — a join always carries a direction.
     */
    Neither,
    ;

    /** The device contributes its own photos (producer enabled) — [Both] and [UploadOnly]. */
    val includesUpload: Boolean
        get() = this == Both || this == UploadOnly

    /** The device imports others' photos (reconcile runs) — [Both] and [DownloadOnly]. */
    val includesDownload: Boolean
        get() = this == Both || this == DownloadOnly
}
