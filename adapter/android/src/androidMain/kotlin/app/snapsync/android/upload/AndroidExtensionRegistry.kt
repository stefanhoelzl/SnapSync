package app.snapsync.android.upload

import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState
import app.snapsync.ports.ExtensionRegistry

/**
 * Android's [ExtensionRegistry]: there is no upload extension to register — the app's
 * own uploader is the only one. Every write answers [RegistrationAnswer.Unsupported] and asks the platform nothing, as
 * iOS below 26.1 does, so the membership transitions need no platform branch.
 */
object AndroidExtensionRegistry : ExtensionRegistry {
    override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer = RegistrationAnswer.Unsupported

    override fun isEnabled(): RegistrationState = RegistrationState.UNSUPPORTED
}
