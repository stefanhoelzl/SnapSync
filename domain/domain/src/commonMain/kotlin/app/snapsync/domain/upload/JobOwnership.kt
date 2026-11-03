package app.snapsync.domain.upload

/** Which of the backend's routes an OS-held upload job is addressed to. */
enum class JobRoute {
    /** One of the byte routes this build's uploads are made to. */
    UPLOAD,

    /** Any other — a destination this build cannot map back to a row. */
    OTHER,

    ;

    /**
     * What a job on this route is when no row of this device's uploads names its destination: on the byte route, a row
     * an authoritative walk withdrew — its photo left the library or the selection, maybe mid-upload — answered quietly
     * (decision record `changes/selection-is-the-walk`, D3); on any other, an outcome this build cannot map, which
     * nothing else will report.
     */
    fun ownerless(): Ownerless = when (this) {
        UPLOAD -> Ownerless.WITHDRAWN
        OTHER -> Ownerless.UNMAPPABLE
    }
}

/** An OS-held job that no row owns. Either way it is acknowledged, and nothing is written for it. */
enum class Ownerless { WITHDRAWN, UNMAPPABLE }
