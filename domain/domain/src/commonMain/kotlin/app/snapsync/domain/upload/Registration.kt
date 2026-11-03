package app.snapsync.domain.upload

/** A write to the OS's registration of the upload extension (iOS ≥ 26.1). */
enum class RegistrationWrite { ENABLE, DISABLE }

/** What the platform answered a registration write, as its facts. */
sealed interface RegistrationReply {
    /** The write took. */
    data object Took : RegistrationReply

    /** The write was refused, with the platform's error [code]. */
    data class Refused(val code: Long?) : RegistrationReply

    /** This platform has no upload extension: nothing was asked of the operating system. */
    data object NotCarried : RegistrationReply
}

/**
 * What a registration write actually did — classified here, so the adapter only reports the platform's raw facts and
 * the log line renders without deciding. Two refusals are expected and named apart from a failure: a disable that found
 * no record (3201, every clean device's first join) and a refusal under a partial grant (3311). Collapsing either would
 * bury the failures this classification exists to surface.
 */
sealed interface RegistrationVerdict {
    /** The write took. A DISABLE that took is evidence a record existed and was removed. */
    data class Applied(val write: RegistrationWrite) : RegistrationVerdict

    /** A disable that found no configuration record — expected on a clean device (measured twice, SE2, iOS 26.6). */
    data object NothingToDisable : RegistrationVerdict

    /** A disable refused under a partial grant: the record survives, and the extension's entry gate withholds. */
    data object DisableRefusedByGrant : RegistrationVerdict

    /** An enable refused under a partial grant: the OS will never launch the extension. */
    data object EnableRefusedByGrant : RegistrationVerdict

    /** This platform has no upload extension. */
    data class Unsupported(val write: RegistrationWrite) : RegistrationVerdict

    /** Any other refusal: the extension is not in the state the app believes, and nothing else will report it. */
    data class Failed(val write: RegistrationWrite, val code: Long?) : RegistrationVerdict

    companion object {
        /** `PHPhotosErrorIdentifierNotFound`, as the Kotlin/Native platform klib names it. */
        const val IDENTIFIER_NOT_FOUND: Long = 3201

        /** `PHPhotosErrorAccessUserDenied` — iOS's answer to either write under a partial (`.limited`) grant. */
        const val ACCESS_USER_DENIED: Long = 3311

        /** What [reply] to [write] means. */
        fun of(write: RegistrationWrite, reply: RegistrationReply): RegistrationVerdict = when (reply) {
            RegistrationReply.Took -> Applied(write)
            RegistrationReply.NotCarried -> Unsupported(write)
            is RegistrationReply.Refused -> refused(write, reply.code)
        }

        private fun refused(write: RegistrationWrite, code: Long?): RegistrationVerdict = when (write) {
            RegistrationWrite.ENABLE -> enableRefused(code)
            RegistrationWrite.DISABLE -> disableRefused(code)
        }

        private fun enableRefused(code: Long?): RegistrationVerdict = when (code) {
            ACCESS_USER_DENIED -> EnableRefusedByGrant
            else -> Failed(RegistrationWrite.ENABLE, code)
        }

        private fun disableRefused(code: Long?): RegistrationVerdict = when (code) {
            IDENTIFIER_NOT_FOUND -> NothingToDisable
            ACCESS_USER_DENIED -> DisableRefusedByGrant
            else -> Failed(RegistrationWrite.DISABLE, code)
        }
    }
}
