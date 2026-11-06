package app.snapsync.domain.upload

/**
 * How far one of the member's photos has got: every row of it [DONE]; else any row [HANDED_OVER] — a job carries it,
 * so its transfer is under way; else [WAITING] for a job. Only a handed-over photo moves: the status line's arrow
 * pulses for it and stands still for a waiting one.
 */
enum class AssetProgress {
    DONE,
    HANDED_OVER,
    WAITING,
    ;

    companion object {
        /** A photo of [notDone] rows not done, [handedOver] of them carried by a job. */
        fun of(notDone: Long, handedOver: Long): AssetProgress = when {
            notDone == 0L -> DONE
            handedOver > 0L -> HANDED_OVER
            else -> WAITING
        }
    }
}
