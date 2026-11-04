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

    /**
     * This phone holds no proof it is genuine, and this process could obtain none: nothing more is sent until it is
     * verified (change `withhold-unverified-uploads`). The app, which renews its proof at its next wake, has work left;
     * the upload extension, which cannot, rests — asking to be invoked again would only repeat the refusal.
     */
    UNVERIFIED,

    /** The membership contributes nothing now — none joined, sharing off, or this process may not create. */
    SKIPPED,

    ;

    /** What the heartbeat reads of this pass: a paused one's work remains too. */
    fun left(): UploadsLeft = when (this) {
        WORK_LEFT, PAUSED, UNVERIFIED -> UploadsLeft.WORK_LEFT
        CAUGHT_UP -> UploadsLeft.CAUGHT_UP
        SKIPPED -> UploadsLeft.NOT_CONTRIBUTING
    }

    /**
     * What the upload extension answers the operating system after this pass, holding [inFlight] jobs: invoked lazily,
     * it would leave an upload that quietly finished unrecorded until the next library change — so while a job is in
     * flight it asks to be invoked again. Only an in-flight job counts: a row the policy no longer admits never
     * finishes, and must not keep the extension waking. A count that could not be read (`null`) cannot say whether to
     * be invoked again: the pass [ExtensionAnswer.COULD_NOT_LOOK].
     */
    fun extensionAnswer(inFlight: Int?): ExtensionAnswer = when (inFlight) {
        null -> ExtensionAnswer.COULD_NOT_LOOK
        else -> answered(inFlight)
    }

    private fun answered(inFlight: Int): ExtensionAnswer = when (this) {
        WORK_LEFT, PAUSED -> ExtensionAnswer.INVOKE_AGAIN
        SKIPPED, UNVERIFIED -> ExtensionAnswer.REST
        CAUGHT_UP -> ExtensionAnswer.of(inFlight)
    }

    companion object {
        /**
         * What a wake's uploads came to over its steps, in order (a step not taken is `null`): the latest
         * [SKIPPED] wins — the freshest gate answer says the membership contributes nothing; then [UNVERIFIED] — a step
         * that could send nothing says the next is no better off; then work left, a paused step's included; then caught
         * up — and a wake that ran no step is caught up.
         */
        fun of(vararg steps: UploadPass?): UploadPass {
            val ran = steps.filterNotNull()
            return when (ran.lastOrNull()) {
                SKIPPED -> SKIPPED
                else -> unverified(ran)
            }
        }

        private fun unverified(ran: List<UploadPass>): UploadPass = when (UNVERIFIED in ran) {
            true -> UNVERIFIED
            false -> remaining(ran)
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

    /**
     * The pass could not look — its membership, its records or what it holds in flight could not be read (a device
     * locked since boot): it uploaded and claimed nothing, and says so.
     */
    COULD_NOT_LOOK,

    ;

    companion object {
        /** A caught-up pass holding [inFlight] jobs. */
        fun of(inFlight: Int): ExtensionAnswer = when (inFlight > 0) {
            true -> INVOKE_AGAIN
            false -> REST
        }
    }
}
