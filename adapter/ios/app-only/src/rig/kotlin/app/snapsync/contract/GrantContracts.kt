package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.GalleryChange
import app.snapsync.contracts.GalleryContract
import app.snapsync.contracts.GalleryReaderContract
import app.snapsync.contracts.GalleryReaderState
import app.snapsync.contracts.GalleryState
import app.snapsync.contracts.Host
import app.snapsync.contracts.InAppContract
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.SeededLibrary
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.simulatorAppContract
import app.snapsync.gallery.IosGallery
import app.snapsync.gallery.IosGalleryReader
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import co.touchlab.kermit.Logger
import platform.posix.usleep
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The simulator app's photo-library bindings under the grants short of full: one launch holds one grant, so the
 * `journeys (ios)` job relaunches the app under each — a partial selection, a refusal, and never asked — and runs the
 * entries listed for it (`contract.list(grant = <GRANT>)`). Each entry refuses its whole run under any other grant.
 *
 * Under every grant the app has a screen. So the states a clause enters "with no screen to ask on" are the ones whose
 * asking raises nothing: a refused or partial grant is already decided, and iOS answers a request for it at once. A
 * never-asked one would raise the system's dialog, which no run can answer — Gallery's `NEVER_ASKED` runs nowhere on
 * iOS, and only the reads of an undecided grant run here.
 */
internal fun otherGrantContracts(): List<InAppContract> = listOf(
    simulatorAppContract(GalleryContract, SimAppPartialGalleryBinding(), ::partialRefusal),
    simulatorAppContract(PhotoAccessContract, SimAppPartialPhotoAccessBinding(), ::partialRefusal),
    simulatorAppContract(GalleryContract, SimAppRefusedGalleryBinding(), ::refusedRefusal),
    simulatorAppContract(GalleryReaderContract, SimAppNeverAskedGalleryReaderBinding(), ::neverAskedRefusal),
    simulatorAppContract(GalleryReaderContract, SimAppNeverAskedGalleryAccessBinding(), ::neverAskedRefusal),
    simulatorAppContract(GalleryReaderContract, SimAppPartialGalleryReaderBinding(), ::partialRefusal),
    simulatorAppContract(PhotoAccessContract, SimAppNeverAskedPhotoAccessBinding(), ::neverAskedRefusal),
    simulatorAppContract(GalleryContract, SimAppNeverAskedInBackgroundGalleryBinding(), ::neverAskedRefusal),
)

private fun partialRefusal() = grantRefusal(LibraryGrant.LIMITED)

private fun refusedRefusal() = grantRefusal(LibraryGrant.DENIED)

private fun neverAskedRefusal() = grantRefusal(LibraryGrant.NOT_DETERMINED)

/** Why this process cannot run an entry taken under [grant], or `null` when it can. */
private fun grantRefusal(grant: LibraryGrant): String? = hostRefusal() ?: currentPhotoPermission().let { held ->
    if (held == grant) {
        null
    } else {
        "the simulator app holds $held, not $grant; set photo access with applesimutils before launch"
    }
}

/**
 * The real [app.snapsync.gallery.IosGallery] under a partial grant. The photos it seeds are its own creations, which
 * join the selection, so they are the selection the clause finds observed. With a screen up, widening presents the
 * picker and leaves it up; the answer is the grant as it stands either way.
 */
class SimAppPartialGalleryBinding : Binding<GalleryState, GalleryChange> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val adapter = IosGallery::class
    override val grant = LibraryGrant.LIMITED
    override val reaches = setOf(GalleryState.PARTIAL)

    override fun create(state: GalleryState, clauseId: String, log: CallLog): Entered<GalleryChange> {
        if (state !in reaches) return Entered.Unreachable("this launch holds a partial grant")
        val seedDate = PhotoLibrary.window(GalleryContract.name, clauseId).seedDate
        val selection = seedPhotos(seedDate).mapTo(linkedSetOf()) {
            PlatformAssetId(it)
        }
        return Entered.Ready(GalleryChange(contractGallery().recorded(log), selection) { seedPhotos(seedDate) })
    }
}

/** The real [PhotoLibraryPermission] under a partial grant. */
class SimAppPartialPhotoAccessBinding : Binding<PhotoAccessState, PhotoAccess> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val adapter = IosGallery::class
    override val grant = LibraryGrant.LIMITED
    override val reaches = setOf(PhotoAccessState.PARTIAL)

    override fun create(state: PhotoAccessState, clauseId: String, log: CallLog): Entered<PhotoAccess> =
        if (state in reaches) {
            Entered.Ready(PhotoAccess(photoAccessGallery().recorded(log)))
        } else {
            Entered.Unreachable("this launch holds a partial grant")
        }
}

/** The real [app.snapsync.gallery.IosGallery] in an app the member refused: iOS asks nothing again, and shows no picker. */
class SimAppRefusedGalleryBinding : Binding<GalleryState, GalleryChange> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val adapter = IosGallery::class
    override val grant = LibraryGrant.DENIED
    override val reaches = setOf(GalleryState.REFUSED)

    override fun create(state: GalleryState, clauseId: String, log: CallLog): Entered<GalleryChange> =
        if (state in reaches) {
            Entered.Ready(GalleryChange(contractGallery().recorded(log)) {})
        } else {
            Entered.Unreachable("this launch holds a refusal")
        }
}

/** The real [IosGalleryReader] in an app never asked for photo access: it reads the grant, and asks nothing. */
class SimAppNeverAskedGalleryReaderBinding : Binding<GalleryReaderState, SeededLibrary<GalleryReader>> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val adapter = IosGalleryReader::class
    override val grant = LibraryGrant.NOT_DETERMINED
    override val reaches = setOf(GalleryReaderState.NEVER_ASKED)

    override fun create(
        state: GalleryReaderState,
        clauseId: String,
        log: CallLog,
    ): Entered<SeededLibrary<GalleryReader>> = if (state in reaches) {
        Entered.Ready(SeededLibrary(IosGalleryReader(Logger.withTag("contract")).recorded(log)))
    } else {
        Entered.Unreachable("this launch was never asked for photo access")
    }
}

/** The real [PhotoLibraryPermission] in an app never asked for photo access. */
class SimAppNeverAskedPhotoAccessBinding : Binding<PhotoAccessState, PhotoAccess> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val adapter = IosGallery::class
    override val grant = LibraryGrant.NOT_DETERMINED
    override val reaches = setOf(PhotoAccessState.NEVER_ASKED)

    override fun create(state: PhotoAccessState, clauseId: String, log: CallLog): Entered<PhotoAccess> =
        if (state in reaches) {
            Entered.Ready(PhotoAccess(photoAccessGallery().recorded(log)))
        } else {
            Entered.Unreachable("this launch was never asked for photo access")
        }
}

/**
 * The real [app.snapsync.gallery.IosGallery] in an app never asked for photo access and sent behind another — with no
 * screen to ask on, so asking raises nothing and the grant is answered as it stands. The run sends the app to the
 * background (`journeys run --platform ios`, `?precondition=IN_BACKGROUND`); entering waits for that, holding background time
 * so the process is not suspended under the clause.
 */
class SimAppNeverAskedInBackgroundGalleryBinding : Binding<GalleryState, GalleryChange> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val adapter = IosGallery::class
    override val grant = LibraryGrant.NOT_DETERMINED
    override val precondition = "IN_BACKGROUND"
    override val reaches = setOf(GalleryState.NEVER_ASKED)

    override fun create(state: GalleryState, clauseId: String, log: CallLog): Entered<GalleryChange> {
        if (state !in reaches) return Entered.Unreachable("this launch was never asked, and is sent to the background")
        val hold = holdBackgroundTime()
        val deadline = TimeSource.Monotonic.markNow() + BACKGROUND_WAIT
        while (!appInBackground()) {
            check(deadline.hasNotPassedNow()) { "the app was not sent to the background within $BACKGROUND_WAIT" }
            usleep(POLL_MICROS)
        }
        return Entered.Ready(GalleryChange(contractGallery().recorded(log)) {}) {
            releaseBackgroundTime(hold)
        }
    }

    private companion object {
        val BACKGROUND_WAIT = 30.seconds
        const val POLL_MICROS = 100_000u
    }
}

/** The real [IosGalleryReader] under a partial grant: it reads the grant as limited. */
class SimAppPartialGalleryReaderBinding : Binding<GalleryReaderState, SeededLibrary<GalleryReader>> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val adapter = IosGalleryReader::class
    override val grant = LibraryGrant.LIMITED
    override val reaches = setOf(GalleryReaderState.PARTIAL)

    override fun create(state: GalleryReaderState, clauseId: String, log: CallLog): Entered<SeededLibrary<GalleryReader>> =
        if (state in reaches) {
            Entered.Ready(SeededLibrary(IosGalleryReader(Logger.withTag("contract")).recorded(log)))
        } else {
            Entered.Unreachable("this launch holds a partial grant")
        }
}

/** The app's [IosGallery] read as a [GalleryReader] in an app never asked: its own grant read answers undetermined. */
class SimAppNeverAskedGalleryAccessBinding : Binding<GalleryReaderState, SeededLibrary<GalleryReader>> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val adapter = IosGallery::class
    override val grant = LibraryGrant.NOT_DETERMINED
    override val reaches = setOf(GalleryReaderState.NEVER_ASKED)

    override fun create(state: GalleryReaderState, clauseId: String, log: CallLog): Entered<SeededLibrary<GalleryReader>> =
        if (state in reaches) {
            Entered.Ready(SeededLibrary(contractGallery().recorded(log)))
        } else {
            Entered.Unreachable("this launch was never asked for photo access")
        }
}
