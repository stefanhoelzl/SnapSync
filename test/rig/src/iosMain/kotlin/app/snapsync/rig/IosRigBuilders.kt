package app.snapsync.rig

import app.snapsync.compose.AppCore
import app.snapsync.contracts.EntryDriver
import app.snapsync.contracts.InAppContract
import app.snapsync.levers.GalleryView
import app.snapsync.levers.MetricReport
import app.snapsync.levers.WipeWindow
import app.snapsync.mock.MockedSystem
import app.snapsync.mock.metricPayloadOf
import app.snapsync.model.ProcessMetricReport
import app.snapsync.model.RegistrationState
import app.snapsync.model.processMetricEmissions
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.ProcessMetricPayload
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.rig.gallery.GalleryReport
import app.snapsync.rig.gallery.addLivePhoto
import app.snapsync.rig.gallery.photoKitCensus
import app.snapsync.rig.gallery.seedPhotos
import app.snapsync.rig.gallery.wipeGallery
import app.snapsync.ui.state.Layer
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers

/**
 * Everything the hook would otherwise have to decide.
 *
 * The hook file lives in this module's tree but is compiled INTO `:app:ios`, so it is scanned by the shell
 * gate and may hold **no** decisions — not a default, not a cast, not an elvis. Every `when`, every parse,
 * every fallback that the commands need therefore lives here, one module across the seam, where it is
 * ordinary ungated code. That split is the same one `rigPort` already made for a single env-var parse; the
 * command maps just make it carry more.
 */

private val log = Logger.withTag("rig")

/**
 * The typed services this launch serves, once its hooks exist: every declared one over its world — each refused, with
 * its reason, where the adapter choice cannot serve it — and this host's own, each method classified by this host.
 */
fun rigServices(
    launch: RigLaunch,
    core: () -> AppCore,
    host: () -> StatusContainerHost,
    photoAccess: PhotoLibraryPermission,
    /** The app's OWN process-metric handler, so a synthetic report drives the path the OS drives. */
    handleReport: (ProcessMetricPayload) -> Unit,
    /** The upload extension root's `process()`, as a thunk: touching the root forces it (see the hook). */
    processExtension: () -> Int,
    /** What this host delivers of the operating system itself (`os`, `extension-host`). */
    osServices: HostServices,
    contracts: List<InAppContract>,
): (RigHooks) -> List<ServedService> = { hooks ->
    val world = launch.world
    world.servedServices(osServices, reconcileUploads = { core().uploadTransitions.onOverrideChanged() }) +
        world.servedHostServices(
            listOf(
                world.screenService(Dispatchers.Main, launch.ui, shown = { host().screen.value }),
                hostService(
                    hooks,
                    core,
                    host,
                    relaunch = refusing(
                        "process death on a device is the app's exit (adapters.choose does one) followed by a launch; the " +
                            "channel cannot relaunch the process it runs in",
                    ),
                    reinstall = refusing(
                        "deleting the app ends the process the channel runs in; delete and reinstall it from outside " +
                            "(`ios-device` / `simctl uninstall`, then install)",
                    ),
                    invite = refusing(
                        "an event's key leaves the creating phone only inside its invite: read it from the joined screen's " +
                            "share link",
                    ),
                    // What the app's files take on the real disk, per container — the App Group is not pullable over USB.
                    disk = honoured { depth, largest -> diskCensus(depth, largest) },
                    // A synthetic process-metric report through the app's OWN handler. Real reports arrive on the OS's
                    // cadence — roughly daily, and only after a period has closed — so without this the only way to exercise
                    // the three channels is to wait a day. The report is an open key/value bag by design, so a synthetic one
                    // needs no MetricKit types: it feeds the same rule and the same channels the OS feeds.
                    processMetrics = honoured { fields ->
                        handleReport(metricPayloadOf(fields))
                        MetricReport(fields.size, processMetricEmissions(ProcessMetricReport(fields)).mapNotNull { it.reason })
                    },
                ),
                photoKitGallery(launch, core, photoAccess),
                adaptersService(adapterVerbs(launch)),
                contractService(contracts, refusal = null),
                uploadJobService(world, processExtension),
            ),
        )
}

/**
 * The photo library's verbs: PhotoKit's where the library is real, the mocked library's where the launch adapters mock
 * it — whose seed and read the JVM host's are, and which no PhotoKit wipe or camera file reaches.
 */
private fun photoKitGallery(launch: RigLaunch, core: () -> AppCore, photoAccess: PhotoLibraryPermission) =
    if (launch.world.isMocked(MockedSystem.LIBRARY)) {
        galleryService(
            seed = honoured { n, kind -> launch.world.seedMockLibrary(n, kind) },
            wipe = refusing("the photo library is mocked on this launch; a mocked library is never PhotoKit's to wipe"),
            addMoving = refusing(
                "the photo library is mocked on this launch and holds no media bytes; add a moving photo through the " +
                    "typed `library` service's `add` of kind `live-photo`",
            ),
            read = honoured(launch.world.mockGalleryReader()),
            uncomposed = launch.uncomposed,
        )
    } else {
        galleryService(
            seed = honoured { n, kind -> seedPhotos(log, n, kind) },
            addMoving = honoured { photo -> addLivePhoto(log, photo) },
            wipe = honoured { scope, limit, offset ->
                val window = if (limit == null && offset == null) null else WipeWindow(offset ?: 0L, limit)
                wipeGallery(log, scope, photoAccess::requestAccess, window = window)
            },
            read = honoured(photoKitGalleryReader(core)),
            uncomposed = launch.uncomposed,
        )
    }

/** The gallery read, bound to the app's own permission-aware candidate seam rather than a second walk. */
private fun photoKitGalleryReader(core: () -> AppCore): suspend (String?, Boolean, Boolean) -> GalleryView =
    { cutoff, resources, includesUpload ->
        val reader = GalleryReport(
            candidates = core().candidates,
            grant = { core().photoPermission.value.name },
            census = ::photoKitCensus,
        )
        reader.read(cutoff, resources, includesUpload)
    }

/**
 * The OS's view of the extension registration — `null` anywhere it cannot be asked.
 *
 * `isUploadJobExtensionEnabled` is a **26.1 selector** and this app deploys to min iOS 18, so calling it
 * unconditionally traps as an unrecognized selector. [osSupportsOsDrivenUpload] is the capability check
 * that gates the selector's existence — asked directly now, rather than through the resolved tier, since
 * which mechanism *runs* is a runtime fact and this question is about what the OS *has*.
 *
 * It is also grant-dependent in a way the name does not admit: measured on device (SE2, iOS 26.6), the
 * read returns `false` for a live configuration record whenever the app does not hold photo access, so a
 * `false` here means "no record **or** not allowed to look". Read it beside the reported permission.
 */
fun osExtensionEnabled(registry: () -> ExtensionRegistry): () -> Boolean? = {
    // Read through the PORT, never through PhotoKit directly. The adapter behind it is the repo's sole
    // caller of `isUploadJobExtensionEnabled`, and on a target whose host cannot hold a record it is the
    // substitute — so this answers what the app itself would read rather than a second opinion.
    //
    // `UNSUPPORTED` is the OS having no such notion at all: below 26.1 the selector does not exist, and
    // `notApplicable` (null) is the honest answer.
    when (registry().isEnabled()) {
        RegistrationState.REGISTERED -> true
        RegistrationState.NOT_REGISTERED -> false
        RegistrationState.UNSUPPORTED -> null
    }
}

/**
 * The precondition a contract run that rewrites the extension registration needs: **no membership**
 * (`docs/architecture.md`). Re-registering wipes every in-flight upload job, and an automatic leave would
 * destroy a real membership on a shared phone, so the run is refused while the screen shows one, naming the
 * reset the operator runs deliberately. `null` means proceed.
 */
fun noMembershipRefusal(host: () -> StatusContainerHost): () -> String? = {
    (host().screen.value.layer as? Layer.Joined)?.let { joined ->
        "this device is a member of event ${joined.membership.eventId}, and the run re-registers the extension, " +
            "which wipes its in-flight upload jobs. Reset deliberately first, then re-run."
    }
}

/** Each delivery through the system that makes it: the mock's where the adapter choice mocks it, [real] otherwise. */
private fun appEntries(launch: RigLaunch, real: EntryDriver): EntryDriver =
    ChosenEntryDriver(launch.world::isMocked, MockEntryDriver(launch.world.device, launch.world.os), real)

/**
 * The services this host implements itself: the operating system's deliveries — each through the system that makes it,
 * on main, as Swift calls the app root's entry points — and the extension root's invocations ([process] and [terminate],
 * thunks over the REAL `UploadExtensionRoot` members).
 */
fun iosHostServices(launch: RigLaunch, real: EntryDriver, process: () -> Int, terminate: () -> Unit): HostServices =
    HostServices(
        entries = PlayedEntries(appEntries(launch, real), Dispatchers.Main, launch.world.os::atRelease),
        extensionHost = extensionHost(process, terminate),
    )
