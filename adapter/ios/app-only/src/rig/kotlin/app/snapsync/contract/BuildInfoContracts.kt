package app.snapsync.contract

import app.snapsync.config.IosBuildInfo
import app.snapsync.config.iosBootLines
import app.snapsync.config.osCarriesOsDrivenUpload
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.BuildInfoContract
import app.snapsync.contracts.BuildInfoState
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.proxy.recorded
import app.snapsync.ports.BuildInfo

/*
 * `BuildInfoContract` on an OS below 26.1 (`docs/architecture.md`): the real `IosBuildInfo` built on the one OS fact
 * the app's root hands it — whether this OS carries the OS-driven uploader — recorded on the XS, and replayed on every
 * CI build. The fact is the only thing iOS answers here: the adapter reads it from its root, so the recording holds
 * the root's call and iOS's answer to it.
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true`, and into `iosTest` otherwise — one file, so
 * the recorder and the replayer cannot spell a call differently.
 */

private const val OS_FACT_CALL = "osCarriesOsDrivenUpload()"

/** The build facts over the OS fact [carries] answers: the device's, recorded, or the recording's on a replay. */
internal fun buildInfoOn(carries: () -> Boolean, log: CallLog, afterDispose: () -> Unit = {}): Entered<BuildInfo> =
    Entered.Ready(
        IosBuildInfo(osSupportsOsDrivenUpload = carries(), bootLines = iosBootLines("app")).recorded(log),
        dispose = afterDispose,
    )

/** The fact as the recording holds it. */
internal fun replayedBuildFact(replayer: Replayer): () -> Boolean = { replayer.answer(OS_FACT_CALL).toBooleanStrict() }

/** The real build facts in the app on a device below iOS 26.1 (the XS), recording the OS fact they are built on. */
internal class DeviceBuildInfoBinding(private val recorder: Recorder) : Binding<BuildInfoState, BuildInfo> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val precondition = "BELOW_IOS_26_1"
    override val reaches = setOf(BuildInfoState.NO_OS_DRIVEN_UPLOAD)

    override fun create(state: BuildInfoState, clauseId: String, log: CallLog): Entered<BuildInfo> {
        if (state !in reaches) return Entered.Unreachable("this run records an OS below 26.1; $state runs live")
        recorder.open(clauseId)
        return buildInfoOn({ osCarriesOsDrivenUpload().also { recorder.record(OS_FACT_CALL, "$it") } }, log)
    }
}

/** `POST /contract/BuildInfo` on a device: recorded only where the OS lacks the mechanism, the one fact a device adds. */
internal fun recordBuildInfo(): String = if (osCarriesOsDrivenUpload()) {
    CONTRACT_REFUSED + "this phone carries the OS-driven uploader; the simulator app proves that live. Record " +
        "${BuildInfoContract.name} on a phone below iOS 26.1 (the XS).\n"
} else {
    recordAppOnDevice(BuildInfoContract, null, BELOW_IOS_26_1) { DeviceBuildInfoBinding(it) }
}
