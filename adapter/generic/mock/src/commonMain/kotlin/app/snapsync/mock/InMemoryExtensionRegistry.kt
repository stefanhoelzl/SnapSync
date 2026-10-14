package app.snapsync.mock

import app.snapsync.levers.WorldEvent
import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState
import app.snapsync.ports.ExtensionRegistry
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [ExtensionRegistry]. With no [record] it is a platform without the upload extension — the JVM,
 * iOS below 26.1 — and answers `Unsupported` to everything, which is what every off-device composition composes.
 * With one, a write is applied to the caller's cell and answered as taken — except the answers a device gives: a disable
 * that finds no record, and every change under a partial grant, are refused with the device's own codes.
 */
internal class InMemoryExtensionRegistry(
    private val record: MutableStateFlow<Boolean>?,
    private val partialGrant: Boolean = false,
    /** Told each change taken, as the operating system records it. */
    private val watcher: OsWatcher = OsWatcher {},
) : ExtensionRegistry {
    override val supported: Boolean = record != null

    override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer {
        val cell = record ?: return RegistrationAnswer.Unsupported
        // Under a partial grant a device refuses every change, either way (`PHPhotosErrorAccessUserDenied`).
        if (partialGrant) return RegistrationAnswer.Answered(ok = false, domain = "PHPhotosErrorDomain", code = 3311L)
        // A disable that finds no record is the clean device's answer (`PHPhotosErrorIdentifierNotFound`).
        if (!enabled && !cell.value) {
            return RegistrationAnswer.Answered(
                ok = false,
                domain = "PHPhotosErrorDomain",
                code = 3201L,
            )
        }
        cell.value = enabled
        watcher.told(WorldEvent.ExtensionRegistered(enabled))
        return RegistrationAnswer.Answered(ok = true, domain = null, code = null)
    }

    override fun isEnabled(): RegistrationState = when (record?.value) {
        null -> RegistrationState.UNSUPPORTED
        true -> RegistrationState.REGISTERED
        false -> RegistrationState.NOT_REGISTERED
    }
}
