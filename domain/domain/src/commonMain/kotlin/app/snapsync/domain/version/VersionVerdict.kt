package app.snapsync.domain.version

import app.snapsync.domain.trust.BackendAnswer

/**
 * The backend refuses this build as too old, naming [minimumVersion] when it carried one. A refusal without a version
 * is still a refusal — the update notice appears, without a version to name.
 */
data class VersionRefusal(val minimumVersion: String?)

/**
 * What one backend answer says about this build: `426` refuses it, a served request clears a refusal, and every other
 * answer says nothing about it.
 */
sealed interface VersionVerdict {
    data class Refuse(val refusal: VersionRefusal) : VersionVerdict

    data object Clear : VersionVerdict

    data object NoVerdict : VersionVerdict

    companion object {
        fun of(answer: BackendAnswer): VersionVerdict = when (answer) {
            is BackendAnswer.TooOld -> Refuse(VersionRefusal(answer.minimumVersion))
            BackendAnswer.Served -> Clear
            BackendAnswer.Rejected,
            BackendAnswer.Refused,
            BackendAnswer.ServerIssue,
            BackendAnswer.NoAnswer,
            -> NoVerdict
        }
    }
}
