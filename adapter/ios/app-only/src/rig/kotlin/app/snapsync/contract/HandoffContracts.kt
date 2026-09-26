package app.snapsync.contract

import app.snapsync.contracts.WakeContract
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.Contract
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.InAppContract
import app.snapsync.contracts.LinkOpenerContract
import app.snapsync.contracts.LinkOpenerState
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.SharePresenterState
import app.snapsync.contracts.ExtensionRegistryContract
import app.snapsync.contracts.recordingName
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.contracts.render
import app.snapsync.contracts.run
import app.snapsync.link.SystemUrlOpenerApi
import app.snapsync.link.UrlOpenerApi
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.model.GalleryAccess
import app.snapsync.ports.SystemUi
import app.snapsync.systemui.IosSystemUi
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync

/*
 * The hand-off ports' contract bindings on iOS (`docs/architecture.md`): `LinkOpenerContract` recorded on a
 * device and replayed on every CI build, both hand-off contracts run live in the simulator app.
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true`, and into `iosTest` otherwise — one
 * file, so the recorder and the replayer cannot spell a call differently.
 */

private fun call(url: NSURL): String = "openURL:options:completionHandler:(url=${url.absoluteString} options={})"

/** Passes every call to [real] and records it, with iOS's answer, in the clause block [recorder] has open. */
internal class RecordingUrlOpenerApi(private val real: UrlOpenerApi, private val recorder: Recorder) : UrlOpenerApi {
    override fun open(url: NSURL, completion: (Boolean) -> Unit) =
        real.open(url) { opened ->
            recorder.record(call(url), "$opened")
            completion(opened)
        }
}

/** Answers every call from one clause's recorded block, exactly and in order. */
internal class ReplayingUrlOpenerApi(private val replayer: Replayer) : UrlOpenerApi {
    override fun open(url: NSURL, completion: (Boolean) -> Unit) =
        completion(replayer.answer(call(url)).toBooleanStrict())
}

internal const val SIM_APP_UNREACHABLE_CLAIMED =
    "opening a URL another app claims backgrounds the app under test mid-run; this state is recorded on a device"

/**
 * The real [IosSystemUi] in the app on a device, recording its `openURL` call and iOS's answer. Replayed on
 * every CI build by `IosLinkOpenerReplayContractTest`.
 */
internal class DeviceLinkOpenerBinding(private val recorder: Recorder) : Binding<LinkOpenerState, SystemUi> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(LinkOpenerState.CLAIMED, LinkOpenerState.UNCLAIMED)

    override fun create(state: LinkOpenerState, clauseId: String): Entered<SystemUi> {
        recorder.open(clauseId)
        return Entered.Ready(IosSystemUi(RecordingUrlOpenerApi(SystemUrlOpenerApi, recorder)))
    }
}

/**
 * This module's contracts the rig can run on a device, by name — `POST /contract/<name>`. Each answers with the
 * recording to commit verbatim at `test/contracts/recordings/<name>.rec`, the name its header's `contract`,
 * `host` and (where declared) `grant` lines spell ([recordingName]).
 *
 * [refusal] is the rig's precondition for a run that rewrites durable OS state: the registration contract's
 * full-grant run disables and re-enables the extension, which wipes every in-flight upload job, so it is
 * refused while a membership could own one. It answers the reason, or `null` to proceed.
 */
fun appDeviceContracts(refusal: () -> String? = { null }): List<InAppContract> = listOf(
    InAppContract(LinkOpenerContract.name, Host.IOS_DEVICE_APP) {
        recordAppOnDevice(LinkOpenerContract, null) { DeviceLinkOpenerBinding(it) }
    },
    InAppContract(WakeContract.name, Host.IOS_DEVICE_APP) { recordScheduler() },
    InAppContract(ExtensionRegistryContract.name, Host.IOS_DEVICE_APP) { recordRegistry(refusal) },
)

/**
 * Runs the registration contract under the grant this process holds — the grant is a precondition of the run,
 * not something a binding can enter (`docs/architecture.md`, "An authorization the process cannot give
 * itself is a precondition of the run") — so a person switches it in Settings between the two recordings.
 */
private fun recordRegistry(refusal: () -> String?): String = when (val grant = currentPhotoPermission()) {
    GalleryAccess.GRANTED -> refusal()?.let { "$CONTRACT_REFUSED$it\n" }
        ?: recordAppOnDevice(ExtensionRegistryContract, grant) { DeviceRegistryGrantedBinding(it) }
    GalleryAccess.LIMITED ->
        recordAppOnDevice(ExtensionRegistryContract, grant) { DeviceRegistryLimitedBinding(it) }
    else -> CONTRACT_REFUSED +
        "the registration contract records under a full grant or a partial one; this process holds $grant. " +
        "Set photo access in Settings and re-run.\n"
}

/**
 * Runs [contract] against this app's real platform through the recording binding [binding] builds, under
 * [grant] where the binding declares one, and renders the recording.
 *
 * It refuses on a simulator: a recording taken there would be filed under the device's name.
 */
private fun <K : Enum<K>, T> recordAppOnDevice(
    contract: Contract<K, T>,
    grant: GalleryAccess?,
    binding: (Recorder) -> Binding<K, T>,
): String {
    if (NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null) {
        return CONTRACT_REFUSED +
            "this process is a simulator app, not ${Host.IOS_DEVICE_APP}; record ${contract.name} on a device.\n"
    }
    val recorder = Recorder()
    val results = run(contract, binding(recorder))
    val env = deviceDiagnosticEnvironment(uploadTier = "n/a")
    val header = listOf("contract" to contract.name, "host" to Host.IOS_DEVICE_APP.name) +
        listOfNotNull(grant?.let { "grant" to it.name }) +
        listOf(
            "file" to recordingName(contract.name, Host.IOS_DEVICE_APP, grant) + ".rec",
            "device" to env.deviceModel,
            "os" to env.osVersion,
            "build" to env.buildNumber,
            "kotlin" to KotlinVersion.CURRENT.toString(),
            "recorded" to NSISO8601DateFormatter().stringFromDate(NSDate()),
        ) + results.map { "live ${it.clauseId}" to it.outcome.render() }
    return recorder.recording(header).render()
}

/** The real [IosSystemUi] in the simulator app, for the state that leaves the app where it is. */
class SimAppLinkOpenerBinding : Binding<LinkOpenerState, SystemUi> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(LinkOpenerState.UNCLAIMED)

    override fun create(state: LinkOpenerState, clauseId: String): Entered<SystemUi> = when (state) {
        LinkOpenerState.UNCLAIMED -> Entered.Ready(IosSystemUi())
        LinkOpenerState.CLAIMED -> Entered.Unreachable(SIM_APP_UNREACHABLE_CLAIMED)
    }
}

/**
 * The real [IosSystemUi] in the simulator app, which the rig drives in the foreground. Disposal dismisses the
 * sheet the clause presented, so the next contract finds the app as it was.
 */
class SimAppSharePresenterBinding : Binding<SharePresenterState, SystemUi> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(SharePresenterState.PRESENTABLE)

    override fun create(state: SharePresenterState, clauseId: String): Entered<SystemUi> =
        Entered.Ready(IosSystemUi(), dispose = ::dismissPresented)
}

/** Dismisses whatever the key window's root controller presents. Called off the main queue, by the runner. */
private fun dismissPresented() = dispatch_sync(dispatch_get_main_queue()) {
    UIApplication.sharedApplication.keyWindow?.rootViewController?.let { root ->
        root.presentedViewController?.let { root.dismissViewControllerAnimated(false, completion = null) }
    }
}
