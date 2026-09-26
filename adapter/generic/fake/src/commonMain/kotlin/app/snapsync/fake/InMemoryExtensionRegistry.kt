package app.snapsync.fake

import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState
import app.snapsync.ports.ExtensionRegistry
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [ExtensionRegistry]. With no [record] it is a platform without the upload extension — the JVM,
 * iOS below 26.1 — and answers `Unsupported` to everything, which is what every off-device composition composes.
 * With one, a write is applied to the caller's cell and answered as taken; it models no grant and no refusal (those
 * are the platform's, and its contract holds its adapters to them).
 */
internal class InMemoryExtensionRegistry(private val record: MutableStateFlow<Boolean>?) : ExtensionRegistry {
    override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer {
        val cell = record ?: return RegistrationAnswer.Unsupported
        cell.value = enabled
        return RegistrationAnswer.Answered(ok = true, domain = null, code = null)
    }

    override fun isEnabled(): RegistrationState = when (record?.value) {
        null -> RegistrationState.UNSUPPORTED
        true -> RegistrationState.REGISTERED
        false -> RegistrationState.NOT_REGISTERED
    }
}
