package app.snapsync.android.upload

import app.snapsync.model.Never
import app.snapsync.model.NeverAnswers
import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState
import app.snapsync.ports.ExtensionRegistry

/**
 * Android's [ExtensionRegistry]: there is no upload extension to register — the app's
 * own uploader is the only one. Every write answers [RegistrationAnswer.Unsupported] and asks the platform nothing, as
 * iOS below 26.1 does, so the membership transitions need no platform branch.
 */
@NeverAnswers(
    Never(member = "isEnabled", answer = "RegistrationState.NOT_REGISTERED"),
    Never(member = "isEnabled", answer = "RegistrationState.REGISTERED"),
    Never(member = "setEnabled", answer = "RegistrationAnswer.Answered"),
    Never(member = "supported", answer = "true"),
)
object AndroidExtensionRegistry : ExtensionRegistry {
    override val supported: Boolean = false

    override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer = RegistrationAnswer.Unsupported

    override fun isEnabled(): RegistrationState = RegistrationState.UNSUPPORTED
}
