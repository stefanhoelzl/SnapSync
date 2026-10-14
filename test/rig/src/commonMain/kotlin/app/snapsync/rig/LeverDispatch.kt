package app.snapsync.rig

import app.snapsync.levers.BackendLevers
import app.snapsync.levers.BackgroundTimeLevers
import app.snapsync.levers.BuildLevers
import app.snapsync.levers.ClockLevers
import app.snapsync.levers.CrashReporterLevers
import app.snapsync.levers.DatabasesLevers
import app.snapsync.levers.DevControlsLevers
import app.snapsync.levers.DeviceAdvertisement
import app.snapsync.levers.DeviceConditionsLevers
import app.snapsync.levers.DownloadSessionLevers
import app.snapsync.levers.ExtensionHostLevers
import app.snapsync.levers.ExtensionRegistryLevers
import app.snapsync.levers.FilesLevers
import app.snapsync.levers.JoinedEvent
import app.snapsync.levers.KeychainLevers
import app.snapsync.levers.LEVER_SERVICES
import app.snapsync.levers.LeverNeed
import app.snapsync.levers.LeverRefused
import app.snapsync.levers.LeverService
import app.snapsync.levers.LibraryLevers
import app.snapsync.levers.LifecycleLevers
import app.snapsync.levers.LinksLevers
import app.snapsync.levers.NetworkLevers
import app.snapsync.levers.OlderInstallLevers
import app.snapsync.levers.OsLevers
import app.snapsync.levers.PlayedOsLevers
import app.snapsync.levers.ProcessInfoLevers
import app.snapsync.levers.PublicBackendLevers
import app.snapsync.levers.PushLevers
import app.snapsync.levers.RigLevers
import app.snapsync.levers.SystemUiLevers
import app.snapsync.levers.ThisDevice
import app.snapsync.levers.UploadQueueLevers
import app.snapsync.levers.UploadSessionLevers
import app.snapsync.levers.WakeLevers
import app.snapsync.levers.WorldLevers
import app.snapsync.mock.DeclaredVersion
import app.snapsync.mock.MockedSystem
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlinx.rpc.descriptor.RpcInvokator
import kotlinx.rpc.internal.utils.ExperimentalRpcApi

// The typed services as this host serves them (`docs/testing.md`, "Typed services"). The rig registers every declared
// service (`LEVER_SERVICES`) on its kRPC route, each behind ONE wrapper written for all of them: it names the joined event
// and this device for an omitted `@JoinedEvent` / `@ThisDevice` argument, refuses every method with the reason when the
// host cannot meet the service's needs, runs each call as an act of the host's (`RigActs`), and calls the implementation
// through the descriptor's invoker — so a face that migrates adds an interface and an implementation, and nothing here.

/**
 * A declared service as this world serves it: the wrapper the kRPC route registers, why it refuses every method, if it
 * does, and the methods it refuses one by one ([methodRefusals], by method name — a host's own service).
 */
class ServedService internal constructor(
    val service: LeverService<*>,
    internal val wrapper: Any,
    val refusal: String?,
    val methodRefusals: Map<String, String> = emptyMap(),
) {
    /** Every method this host refuses, by advertisement entry (`<service>.<method>`), each with its reason. */
    val refused: Map<String, String>
        get() = refusal?.let { why -> service.entries.associateWith { why } }
            ?: methodRefusals.mapKeys { (method, _) -> "${service.name}.$method" }
}

/**
 * A host's own service ([LeverNeed.HOST]): its [implementation], and the methods this host refuses, each with its reason
 * ([refused], by method name) — a refused method is never invoked on the implementation.
 */
class HostService<T : Any>(
    val service: LeverService<T>,
    val implementation: T,
    val refused: Map<String, String> = emptyMap(),
) {
    init {
        val unknown = refused.keys - service.methods
        require(unknown.isEmpty()) { "${service.name} refuses methods it does not declare: $unknown" }
    }
}

/** A method this host does not honour: the reason, for a [HostService]'s refusals. */
fun <T : Any> LeverService<T>.refusing(vararg methods: String, why: String): Map<String, String> =
    methods.associateWith { why }

/** Every method of this service refused, with [why]. */
fun <T : Any> LeverService<T>.refusingAll(why: String): Map<String, String> = methods.associateWith { why }

/** This service's method names. */
val LeverService<*>.methods: Set<String> get() = entries.map { it.removePrefix("$name.") }.toSet()

/** The services the channel serves itself rather than over the mocks: what it advertises, and the world's events. */
private val CHANNEL_SERVICES: Set<LeverService<*>> = setOf(RigLevers, WorldLevers)

/**
 * The services a host implements itself rather than over the mocks: the operating system's deliveries to the app
 * ([entries], over the host's own entry driver, real or mocked per system) and its invocations of the upload extension
 * ([extensionHost]) — each `null` where the host has none, refused then with the reason given.
 */
class HostServices(
    val entries: OsLevers?,
    val extensionHost: ExtensionHostLevers?,
    val noExtensionHost: String = "this host has no upload extension to invoke",
)

/**
 * Every declared service but the channel's own, each call run as one of [acts]; [host]'s own among them.
 * [reconcileUploads] brings the app's uploads in line with a pin the development controls just took — the composed
 * app's, which this world cannot reach.
 */
fun MockWorld.servedServices(
    host: HostServices,
    acts: RigActs = RigActs.Direct,
    reconcileUploads: suspend () -> Unit = {},
): List<ServedService> = LEVER_SERVICES.filter {
    it !in CHANNEL_SERVICES && LeverNeed.HOST !in it.needs
}.map { service ->
    val refusal = refusalFor(service.needs.map(::needOf), service.name) ?: host.refusalOf(service)
    val implementation = if (refusal == null) implementationOf(service, host, reconcileUploads) else null
    served(service, implementation, refusal, acts, resolving(this))
}

/**
 * This host's own services ([LeverNeed.HOST]) as it serves them — each method honoured, or refused with the host's
 * reason — every call run as one of [acts], an omitted `@JoinedEvent` / `@ThisDevice` named by this world.
 */
fun MockWorld.servedHostServices(services: List<HostService<*>>, acts: RigActs = RigActs.Direct): List<ServedService> =
    services.map { host ->
        check(LeverNeed.HOST in host.service.needs) { "${host.service.name} is no host's own service" }
        served(host.service, host.implementation, refusal = null, acts, resolving(this), host.refused)
    }

/** Why [host] does not serve [service] itself, where it is one a host implements. */
private fun HostServices.refusalOf(service: LeverService<*>): String? = when (service) {
    OsLevers -> "this host delivers no operating-system entry point".takeIf { entries == null }
    ExtensionHostLevers -> noExtensionHost.takeIf { extensionHost == null }
    else -> null
}

/** The channel's own service: what this host advertises. */
internal fun servedRig(advertise: () -> DeviceAdvertisement): ServedService =
    ServedService(
        RigLevers,
        object : RigLevers {
            override suspend fun advertise() = advertise()
        },
        refusal = null,
    )

/**
 * The world's events and clock, served by a host that can say when its app is settled — or refused, with [refusal], by
 * one that cannot. Never an act itself: a call that moves the world ([WorldLevers.advance]) runs its own.
 */
fun servedWorld(implementation: WorldLevers?, refusal: String?): ServedService =
    served(
        WorldLevers,
        implementation,
        refusal ?: "no world is served".takeIf { implementation == null },
        RigActs.Direct,
        null,
    )

/** Why a device host refuses the world's events: its operating system runs work the channel cannot see. */
const val WORLD_REFUSED_ON_A_DEVICE: String =
    "this host's operating system runs work the channel cannot see, so it cannot say when the app is settled — the " +
        "event stream is the JVM host's (`docs/testing.md`, \"How a test waits\")"

/** Every typed service's methods, as a host's advertisement names them (`<service>.<method>`). */
internal val SERVICE_ENTRIES: List<String> = LEVER_SERVICES.filter { it !== RigLevers }.flatMap { it.entries }

/** The refusal of each method of every service this world refuses, by advertisement entry. */
internal fun MockWorld.serviceRefusals(
    host: HostServices,
): Map<String, String> = servedServices(host).flatMap { served ->
    served.refusal?.let { why -> served.service.entries.map { it to why } }.orEmpty()
}.toMap()

private fun MockWorld.implementationOf(
    service: LeverService<*>,
    host: HostServices,
    reconcileUploads: suspend () -> Unit,
): Any =
    when (service) {
        BackendLevers -> device.backend.operator.also { check(isMocked(MockedSystem.BACKEND)) }
        PublicBackendLevers -> PublicBackend(this, checkNotNull(reach))
        UploadQueueLevers -> device.uploadQueue.operator
        UploadSessionLevers -> device.uploadSession.operator
        DownloadSessionLevers -> device.downloads.operator
        LibraryLevers -> device.library.operator.also { check(isMocked(MockedSystem.LIBRARY)) }
        NetworkLevers -> device.connectivity.operator
        DeviceConditionsLevers -> device.deviceConditions.operator
        ClockLevers -> device.clock.operator
        ProcessInfoLevers -> device.processInfo.operator
        CrashReporterLevers -> device.crashReporter.operator
        SystemUiLevers -> device.systemUi.operator
        DevControlsLevers -> ReconcilingControls(controls, reconcileUploads)
        BuildLevers -> DeclaredBuild(checkNotNull(version))
        DatabasesLevers -> device.databases.operator
        FilesLevers -> device.disk.operator
        KeychainLevers -> Keychain(this)
        OlderInstallLevers -> OlderInstall(this)
        OsLevers -> checkNotNull(host.entries)
        PlayedOsLevers -> os
        ExtensionHostLevers -> checkNotNull(host.extensionHost)
        LifecycleLevers -> device.lifecycle.operator
        LinksLevers -> device.links.operator
        PushLevers -> device.pushService.operator
        WakeLevers -> device.wakes.operator
        BackgroundTimeLevers -> device.backgroundTime.operator
        ExtensionRegistryLevers -> device.extensionRegistry.operator
        else -> error("$service has no implementation in this world")
    }

private fun needOf(need: LeverNeed): Need = when (need) {
    LeverNeed.BACKEND_MOCKED -> Need.Operator
    LeverNeed.BACKEND_REACHABLE -> Need.Reach
    LeverNeed.UPLOAD_QUEUE_MOCKED -> Need.Mocked(MockedSystem.UPLOAD_QUEUE)
    LeverNeed.UPLOAD_SESSION_MOCKED -> Need.Mocked(MockedSystem.UPLOAD_SESSION)
    LeverNeed.DOWNLOADS_MOCKED -> Need.Mocked(MockedSystem.DOWNLOADS)
    LeverNeed.LIBRARY_MOCKED -> Need.Mocked(MockedSystem.LIBRARY)
    LeverNeed.NETWORK_MOCKED -> Need.Mocked(MockedSystem.NETWORK)
    LeverNeed.DEVICE_CONDITIONS_MOCKED -> Need.Mocked(MockedSystem.DEVICE_CONDITIONS)
    LeverNeed.CLOCK_MOCKED -> Need.Mocked(MockedSystem.CLOCK)
    LeverNeed.CLOCK_UNOWNED -> Need.ClockUnowned
    LeverNeed.PROCESS_INFO_MOCKED -> Need.Mocked(MockedSystem.PROCESS_INFO)
    LeverNeed.CRASH_REPORTER_MOCKED -> Need.Mocked(MockedSystem.CRASH_REPORTER)
    LeverNeed.SYSTEM_UI_MOCKED -> Need.Mocked(MockedSystem.SYSTEM_UI)
    LeverNeed.VERSION_SETTABLE -> Need.Version
    LeverNeed.FILES_MOCKED -> Need.Mocked(MockedSystem.FILES)
    LeverNeed.DATABASES_MOCKED -> Need.Mocked(MockedSystem.DATABASES)
    LeverNeed.KEYCHAIN_MOCKED -> Need.Mocked(MockedSystem.KEYCHAIN)
    LeverNeed.LIFECYCLE_MOCKED -> Need.Mocked(MockedSystem.LIFECYCLE)
    LeverNeed.LINKS_MOCKED -> Need.Mocked(MockedSystem.LINKS)
    LeverNeed.PUSH_MOCKED -> Need.Mocked(MockedSystem.PUSH)
    LeverNeed.WAKE_MOCKED -> Need.Mocked(MockedSystem.WAKE)
    LeverNeed.BACKGROUND_TIME_MOCKED -> Need.Mocked(MockedSystem.BACKGROUND_TIME)
    LeverNeed.EXTENSION_REGISTRY_MOCKED -> Need.Mocked(MockedSystem.EXTENSION_REGISTRY)
    LeverNeed.HOST -> error("a host's own service is classified by the host, method by method (servedHostServices)")
}

/** The build's development controls, a pin reconciled with the app's uploads as it lands. */
private class ReconcilingControls(
    private val controls: DevControlsLevers,
    private val reconcile: suspend () -> Unit,
) : DevControlsLevers by controls {
    override suspend fun pin(app: Boolean, extension: Boolean) {
        controls.pin(app, extension)
        reconcile()
    }

    override suspend fun unpin() {
        controls.unpin()
        reconcile()
    }
}

/** The build's declared version, as a cell the channel changes. */
private class DeclaredBuild(private val version: DeclaredVersion) : BuildLevers {
    override suspend fun declare(version: String) {
        this.version.value = version
    }

    override suspend fun declared(): String? = version.value
}

/** How an omitted `@JoinedEvent` / `@ThisDevice` argument is named: by this world's joined event and device. */
private fun resolving(world: MockWorld) = Resolve(joinedEvent = world.joinedEventId, ownDevice = world.ownDeviceId)

/** What names an omitted argument; `null` for a service whose methods take none. */
private class Resolve(val joinedEvent: () -> String?, val ownDevice: () -> String)

@OptIn(ExperimentalRpcApi::class)
private fun served(
    service: LeverService<*>,
    implementation: Any?,
    refusal: String?,
    acts: RigActs,
    resolve: Resolve?,
    methodRefusals: Map<String, String> = emptyMap(),
): ServedService {
    @Suppress("UNCHECKED_CAST")
    val typed = service as LeverService<Any>
    val wrapper = typed.descriptor.createInstance(
        0L,
        Resolving(typed, implementation, refusal, methodRefusals, acts, resolve),
    )
    return ServedService(service, wrapper, refusal, methodRefusals)
}

/**
 * The wrapper's calls: what the generated stub of a service's interface hands an [RpcClient] — here not sent anywhere,
 * but resolved and invoked on the implementation in this process.
 */
@OptIn(ExperimentalRpcApi::class)
private class Resolving(
    private val service: LeverService<Any>,
    private val implementation: Any?,
    private val refusal: String?,
    private val methodRefusals: Map<String, String>,
    private val acts: RigActs,
    private val resolve: Resolve?,
) : RpcClient {
    override suspend fun <T> call(call: RpcCall): T {
        log.i { "→ ${service.name}.${call.callableName}" }
        refuseIfRefused(call)
        val arguments = arguments(call)
        val name = "${service.name}.${call.callableName}"
        @Suppress("UNCHECKED_CAST")
        return if (call.callableName in service.reads) {
            acts.read { invoke(call, arguments) as T }
        } else {
            acts.act(name) { invoke(call, arguments) as T }
        }
    }

    /** A method returning a `Flow`: the implementation's flow, invoked once it is collected. */
    override fun <T> callServerStreaming(call: RpcCall): Flow<T> = flow {
        log.i { "→ ${service.name}.${call.callableName} (stream)" }
        refuseIfRefused(call)
        @Suppress("UNCHECKED_CAST")
        emitAll(invoke(call, arguments(call)) as Flow<T>)
    }

    private fun refuseIfRefused(call: RpcCall) {
        (refusal ?: methodRefusals[call.callableName])?.let {
            throw LeverRefused("${service.name}.${call.callableName} is refused here: $it")
        }
    }

    private fun arguments(call: RpcCall): Array<Any?> {
        val callable = checkNotNull(service.descriptor.getCallable(call.callableName))
        val arguments = call.parameters.copyOf()
        callable.parameters.forEachIndexed { i, parameter ->
            if (arguments[i] != null) return@forEachIndexed
            when {
                parameter.annotations.any { it is JoinedEvent } -> arguments[i] = checkNotNull(resolve).joinedEvent()
                    ?: throw IllegalArgumentException("no joined event, and no `event` was named")
                parameter.annotations.any { it is ThisDevice } -> arguments[i] = checkNotNull(resolve).ownDevice()
            }
        }
        return arguments
    }

    private suspend fun invoke(call: RpcCall, arguments: Array<Any?>): Any? {
        val callable = checkNotNull(service.descriptor.getCallable(call.callableName))
        val invoker = callable.invokator as RpcInvokator.Method<Any>
        return invoker.call(checkNotNull(implementation), arguments)
    }

    private companion object {
        val log = Logger.withTag("rig")
    }
}
