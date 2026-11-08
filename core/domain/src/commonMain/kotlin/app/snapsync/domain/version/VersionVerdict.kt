package app.snapsync.domain.version

import app.snapsync.domain.trust.BackendAnswer

/**
 * The backend refuses this build as too old, naming [minimumVersion] when it carried one. A refusal without a version
 * is still a refusal — the update notice appears, without a version to name.
 */
data class VersionRefusal(val minimumVersion: String?)

/**
 * What one backend answer says about this build: `426` refuses it, and any answer from past the version check — a
 * success, and a refusal of the request itself (`401`, a `4xx`) alike — clears a refusal, since the backend checks the
 * version before anything else. A server issue or no answer says nothing about it: either may not have come from past
 * the check.
 */
sealed interface VersionVerdict {
    data class Refuse(val refusal: VersionRefusal) : VersionVerdict

    data object Clear : VersionVerdict

    data object NoVerdict : VersionVerdict

    companion object {
        fun of(answer: BackendAnswer): VersionVerdict = when (answer) {
            is BackendAnswer.TooOld -> Refuse(VersionRefusal(answer.minimumVersion))
            BackendAnswer.Served,
            BackendAnswer.Rejected,
            BackendAnswer.Refused,
            -> Clear
            BackendAnswer.ServerIssue,
            BackendAnswer.Updating,
            BackendAnswer.NoAnswer,
            -> NoVerdict
        }
    }
}
