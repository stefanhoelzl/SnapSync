package app.snapsync.domain.upload

import app.snapsync.domain.wake.UploadsLeft

/** What one step of a wake's uploads came to — a top-up, a walk — as the wake and the operating system read it. */
enum class UploadPass {
    /** Nothing this step could do is left: every row it could create a job for has one. */
    CAUGHT_UP,

    /** The platform took no more jobs: work remains, and the next pass is due. */
    WORK_LEFT,

    /** Admitted, but waiting for another process's work (the app's copy of the shared records, or of its store). */
    PAUSED,

    /** The membership contributes nothing now — none joined, sharing off, or this process may not create. */
    SKIPPED,

    ;

    /** What the heartbeat reads of this pass: a paused one's work remains too. */
    fun left(): UploadsLeft = when (this) {
        WORK_LEFT, PAUSED -> UploadsLeft.WORK_LEFT
        CAUGHT_UP -> UploadsLeft.CAUGHT_UP
        SKIPPED -> UploadsLeft.NOT_CONTRIBUTING
    }

    /**
     * What the upload extension answers the operating system after this pass, holding [inFlight] jobs: invoked lazily,
     * it would leave an upload that quietly finished unrecorded until the next library change — so while a job is in
     * flight it asks to be invoked again. Only an in-flight job counts: a row the policy no longer admits never
     * finishes, and must not keep the extension waking.
     */
    fun extensionAnswer(inFlight: Int): ExtensionAnswer = when (this) {
        WORK_LEFT, PAUSED -> ExtensionAnswer.INVOKE_AGAIN
        SKIPPED -> ExtensionAnswer.REST
        CAUGHT_UP -> ExtensionAnswer.of(inFlight)
    }

    companion object {
        /**
         * What a wake's uploads came to over its steps, in order (a step not taken is `null`): the latest
         * [SKIPPED] wins — the freshest gate answer says the membership contributes nothing; then work left, a paused
         * step's included; then caught up — and a wake that ran no step is caught up.
         */
        fun of(vararg steps: UploadPass?): UploadPass {
            val ran = steps.filterNotNull()
            return when (ran.lastOrNull()) {
                SKIPPED -> SKIPPED
                else -> remaining(ran)
            }
        }

        private fun remaining(ran: List<UploadPass>): UploadPass = when (ran.any { it == WORK_LEFT || it == PAUSED }) {
            true -> WORK_LEFT
            false -> CAUGHT_UP
        }
    }
}

/** What the upload extension tells the operating system after an invocation. */
enum class ExtensionAnswer {
    /** Done for now: the OS may rest until the next library change. */
    REST,

    /** Work remains, or a job is in flight: invoke again (the OS throttles, so this polls at its cadence). */
    INVOKE_AGAIN,

    ;

    companion object {
        /** A caught-up pass holding [inFlight] jobs. */
        fun of(inFlight: Int): ExtensionAnswer = when (inFlight > 0) {
            true -> INVOKE_AGAIN
            false -> REST
        }
    }
}
