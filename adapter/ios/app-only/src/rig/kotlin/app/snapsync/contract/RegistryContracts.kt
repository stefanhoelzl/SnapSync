package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.ExtensionRegistryState
import app.snapsync.ios.registry.ExtensionRegistrationApi
import app.snapsync.ios.registry.PhotoKitExtensionRegistry
import app.snapsync.ios.registry.PlatformWriteAnswer
import app.snapsync.ios.registry.SystemExtensionRegistrationApi
import app.snapsync.model.GalleryAccess
import app.snapsync.ports.ExtensionRegistry
import co.touchlab.kermit.Logger

/*
 * `ExtensionRegistryContract`'s iOS bindings (`docs/architecture.md`): the real
 * `PhotoKitExtensionRegistry` recorded in the app on a device — once under a full grant, once under a partial
 * one — and replayed on every CI build.
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true`, and into `iosTest` otherwise — one
 * file, so the recorder and the replayer cannot spell a call differently.
 */

private fun setCall(enabled: Boolean) = "setUploadJobExtensionEnabled(enabled=$enabled)"
private const val IS_ENABLED_CALL = "isUploadJobExtensionEnabled()"

private fun PlatformWriteAnswer.render() = "ok=$ok domain=${errorDomain ?: "-"} code=${errorCode ?: "-"}"

private fun parseAnswer(rendered: String): PlatformWriteAnswer {
    val fields = rendered.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
    return PlatformWriteAnswer(
        ok = fields.getValue("ok").toBooleanStrict(),
        errorDomain = fields.getValue("domain").takeIf { it != "-" },
        errorCode = fields.getValue("code").takeIf { it != "-" }?.toLong(),
    )
}

/** Passes every call to [real] and records it, with iOS's answer, in the clause block [recorder] has open. */
internal class RecordingRegistrationApi(
    private val real: ExtensionRegistrationApi,
    private val recorder: Recorder,
) : ExtensionRegistrationApi {
    override fun setEnabled(enabled: Boolean): PlatformWriteAnswer =
        real.setEnabled(enabled).also { recorder.record(setCall(enabled), it.render()) }

    override fun isEnabled(): Boolean = real.isEnabled().also { recorder.record(IS_ENABLED_CALL, "$it") }
}

/** Answers every call from one clause's recorded block, exactly and in order. */
internal class ReplayingRegistrationApi(private val replayer: Replayer) : ExtensionRegistrationApi {
    override fun setEnabled(enabled: Boolean): PlatformWriteAnswer = parseAnswer(replayer.answer(setCall(enabled)))
    override fun isEnabled(): Boolean = replayer.answer(IS_ENABLED_CALL).toBooleanStrict()
}

private val log = Logger.withTag("RegistryContract")

/**
 * The registry in [state], over [api]. The record is entered through the port's own write — recorded like any
 * other call — whose answer is not judged here: the clause judges what follows. A partial grant enters nothing,
 * because the platform refuses the write in both directions.
 */
internal fun registryInState(
    api: ExtensionRegistrationApi,
    state: ExtensionRegistryState,
    afterDispose: () -> Unit = {},
): Entered<ExtensionRegistry> {
    when (state) {
        ExtensionRegistryState.RECORD_ABSENT -> api.setEnabled(false)
        ExtensionRegistryState.RECORD_PRESENT -> api.setEnabled(true)
        ExtensionRegistryState.UNDER_PARTIAL_GRANT -> Unit
    }
    // Recorded on an iOS 26.6 device, which carries the selector: replayed as such on whatever host replays it.
    return Entered.Ready(PhotoKitExtensionRegistry(log, api, supported = true), dispose = afterDispose)
}

internal const val REGISTRY_NEEDS_FULL_GRANT =
    "the registration can be written only under a full photo grant; this binding runs under a partial one"
internal const val REGISTRY_NEEDS_PARTIAL_GRANT =
    "this binding runs under a full photo grant; the partial-grant refusal is recorded by the LIMITED binding"

/**
 * The real registry in the app on a device under a FULL grant, recording every call and iOS's answer.
 * Replayed on every CI build by `PhotoKitExtensionRegistryReplayContractTest`.
 */
internal class DeviceRegistryGrantedBinding(private val recorder: Recorder) :
    Binding<ExtensionRegistryState, ExtensionRegistry> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val grant = GalleryAccess.GRANTED
    override val reaches = setOf(ExtensionRegistryState.RECORD_ABSENT, ExtensionRegistryState.RECORD_PRESENT)

    override fun create(state: ExtensionRegistryState, clauseId: String): Entered<ExtensionRegistry> {
        if (state !in reaches) return Entered.Unreachable(REGISTRY_NEEDS_PARTIAL_GRANT)
        recorder.open(clauseId)
        return registryInState(RecordingRegistrationApi(SystemExtensionRegistrationApi, recorder), state)
    }
}

/**
 * The real registry in the app on a device under a PARTIAL grant, which a person sets in Settings before the
 * run. Replayed on every CI build by `PhotoKitExtensionRegistryReplayContractTest`.
 */
internal class DeviceRegistryLimitedBinding(private val recorder: Recorder) :
    Binding<ExtensionRegistryState, ExtensionRegistry> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val grant = GalleryAccess.LIMITED
    override val reaches = setOf(ExtensionRegistryState.UNDER_PARTIAL_GRANT)

    override fun create(state: ExtensionRegistryState, clauseId: String): Entered<ExtensionRegistry> {
        if (state !in reaches) return Entered.Unreachable(REGISTRY_NEEDS_FULL_GRANT)
        recorder.open(clauseId)
        return registryInState(RecordingRegistrationApi(SystemExtensionRegistrationApi, recorder), state)
    }
}
