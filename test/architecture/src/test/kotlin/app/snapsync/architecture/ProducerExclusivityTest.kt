package app.snapsync.architecture

import app.snapsync.application.EntryPointCall
import app.snapsync.application.upload.ReconcileUploader
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.gallery.photoAccess
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.Stored
import app.snapsync.data.upload.ExtensionRegistrar
import app.snapsync.data.upload.OsUploader
import app.snapsync.data.upload.UploaderSwitches
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.membership.LeftMembership
import app.snapsync.domain.membership.MintsLeftMembership
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.upload.ExtensionSwitch
import app.snapsync.domain.upload.OsSupport
import app.snapsync.domain.upload.OsUploaderAdmission
import app.snapsync.domain.upload.UploaderChange
import app.snapsync.format.TimestampText
import app.snapsync.inbound.CompositionOnly
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.model.EventConfig
import app.snapsync.ports.DevControls
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.RegistrationAnswer
import app.snapsync.ports.RegistrationState
import app.snapsync.ports.UploaderPin
import app.snapsync.services.databases.LegacyRecords
import app.snapsync.services.databases.use
import app.snapsync.testids.testEventId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The upload transitions stop in-flight work only at a leave (`docs/architecture.md`, "The upload
 * transitions stop in-flight work only at a leave"; decision record `changes/both-uploaders-active`, D11).
 *
 * **Re-pointed, not retired.** This guard used to assert that no reachable state admitted two ledger writers.
 * Both uploaders now run by design — an overlap is a duplicate of the same object, never a loss — so that is no
 * longer the risk. The risk now is the one the redesign exists to remove: a transition that **orphans** work — a
 * write of the extension's registration that wipes its in-flight OS jobs — anywhere but at a leave. Nothing repairs an
 * orphaned `REQUESTED` row any more, so an orphan is a photo that silently never uploads. Neither the compiler nor any
 * single unit test sees every transition sequence at once; this guard does.
 *
 * It drives the REAL [ReconcileUploader] and [ExtensionRegistrar] — what each membership transition and the launch
 * run, as their entry points call them (which use case a trigger runs is the entry point's, read in one file and
 * covered by the integration suite) — over a fake registry port that behaves as the platform was measured to (every
 * write refused under `LIMITED` — 3311; the read untrustworthy without a full grant). Every script starts from each
 * leftover registration a previous process or installation can leave. Cancelling the app's own transfers is not
 * driven: only `StopUploads` does it, and only with the evidence a membership's uploads end (`EndsUploads`, an
 * opt-in marker pinned in `OptInPinsTest`).
 */
@OptIn(EntryPointCall::class, MintsLeftMembership::class)
class ProducerExclusivityTest {

    /**
     * The platform's registration record as measured: unchangeable under a partial grant, unreadable without a full
     * one — and, below iOS 26.1 ([supported] false), no record at all: the port answers `Unsupported` and asks the OS
     * nothing, so [calls] (the operating-system calls) stays empty there.
     */
    private class PlatformRegistry(var grant: LibraryGrant, var registered: Boolean, override val supported: Boolean) :
        ExtensionRegistry {
        val calls = mutableListOf<String>()

        override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer {
            if (!supported) return RegistrationAnswer.Unsupported
            calls += if (enabled) "enable" else "disable"
            val takes = grant != LibraryGrant.LIMITED
            if (takes) registered = enabled
            return RegistrationAnswer.Answered(ok = takes, domain = null, code = if (takes) null else 3311L)
        }

        override fun isEnabled(): RegistrationState {
            if (!supported) return RegistrationState.UNSUPPORTED
            calls += "read"
            return if (grant == LibraryGrant.GRANTED && registered) {
                RegistrationState.REGISTERED
            } else {
                RegistrationState.NOT_REGISTERED
            }
        }
    }

    /** One device: OS fact, grant, membership, and the extension's platform registration. */
    private class Device(val osSupported: Boolean, registered: Boolean, database: SharedDatabase) {
        private val grantCell = MutableStateFlow(LibraryGrant.NOT_DETERMINED)
        var grant: LibraryGrant
            get() = grantCell.value
            set(value) {
                grantCell.value = value
                registry.grant = value
            }
        var joined = false

        /** The REAL membership, over [database] — which the caller hands over with no membership row. */
        val membership = MembershipRepository(database)
        private val held = database

        /** [joined] as the membership row a join writes, re-read as every trigger re-reads it. */
        fun join(joined: EventConfig) {
            held.use {
                it.membershipQueries.saveMembership(
                    eventId = Stored.Valid(joined.eventId),
                    name = joined.name,
                    minPhotoDate = joined.minPhotoDate,
                    startsAt = joined.startsAt,
                    endsAt = joined.endsAt,
                    maxPhotoDate = joined.maxPhotoDate,
                    deletesAt = joined.deletesAt,
                    direction = joined.direction,
                    saveToAlbum = joined.saveToAlbum,
                    membersActive = null,
                    membersSettled = null,
                    membersWaiting = null,
                    keyId = null,
                    openUntil = null,
                    deleted = false,
                )
            }
            membership.reload()
        }

        val registry = PlatformRegistry(grantCell.value, registered, supported = osSupported)
        private val grantRead = GrantRead(grantCell)
        val registrar = ExtensionRegistrar(registry)
        val reconcile = ReconcileUploader(
            membership,
            OsUploader(registry, UploaderSwitches(NoPin), grantRead),
            grantRead,
            registrar,
        )
    }

    /** One step of a script: a real app-reachable event, with the state change that comes with it. */
    private sealed interface Step {
        data object Join : Step
        data object Reconfigure : Step
        data object Leave : Step
        data class Permission(val grant: LibraryGrant) : Step

        /** A relaunch with its UI; the grant may have changed while dead. */
        data class Launch(val grant: LibraryGrant) : Step
    }

    private val steps: List<Step> = buildList {
        add(Step.Join)
        add(Step.Reconfigure)
        add(Step.Leave)
        for (g in LibraryGrant.entries) {
            add(Step.Permission(g))
            add(Step.Launch(g))
        }
    }

    private suspend fun Device.apply(step: Step) {
        when (step) {
            Step.Join -> {
                joined = true
                join(MEMBERSHIP)
                reconcile(UploaderChange.JOIN)
            }
            Step.Reconfigure -> reconcile(UploaderChange.RECONFIGURE)
            Step.Leave -> {
                joined = false
                membership.leave()
                registrar.deregister(LeftMembership(MEMBERSHIP.eventId))
            }
            is Step.Permission -> {
                grant = step.grant
                reconcile(UploaderChange.GRANT_CHANGED)
            }
            is Step.Launch -> {
                grant = step.grant
                reconcile(UploaderChange.LAUNCH)
            }
        }
    }

    @Test
    fun `the extension is never registrable below 26_1`() {
        // The sharper risk, with no other guard in this module: below 26.1 the registration selector does not
        // exist, so a registrable cell there is a dead process, not a wrong choice.
        for (grant in LibraryGrant.entries) {
            for (pin in listOf(null, UploaderPin(), UploaderPin(app = false), UploaderPin(extension = false))) {
                assertTrue(!registrable(false, grant, pin), "registrable below 26.1: $grant / $pin")
            }
        }
    }

    /**
     * Every 3-step script over every reachable event, on both OSes, from every leftover registration — asserting
     * after every step that a registration write which wipes in-flight jobs happens only at a leave or a join, and
     * that no compared transition writes the registration under a grant that refuses or cannot read it.
     */
    @Test
    fun `no transition but a leave orphans in-flight work`() = runTest {
        var scripts = 0
        // One in-memory shared database for every script, its membership row cleared before each: opening and
        // migrating a fresh one per script was most of this guard's runtime.
        val database = SharedDatabase(inMemoryDatabases(), LegacyRecords(inMemoryFiles(), inMemoryPreferences()))
        val reset = MembershipRepository(database)
        for (osSupported in listOf(true, false)) for (registered in listOf(false, true)) {
            for (a in steps) for (b in steps) for (c in steps) {
                reset.leave()
                val device = Device(osSupported, registered = registered && osSupported, database)
                val script = listOf(a, b, c)
                for (step in script) {
                    val wasConfirmed = device.registry.registered && device.grant == LibraryGrant.GRANTED
                    device.registry.calls.clear()
                    device.apply(step)
                    check(device, step, script, wasConfirmed)
                }
                scripts++
            }
        }
        assertTrue(scripts > 1_000, "the guard drove only $scripts scripts — its step alphabet collapsed")
    }

    private fun check(device: Device, step: Step, script: List<Step>, wasConfirmed: Boolean) {
        val where = "os=${device.osSupported} after $step in $script"
        val writes = device.registry.calls.filter { it != "read" }
        if (!device.osSupported) assertTrue(device.registry.calls.isEmpty(), "a registration call below 26.1 — $where")
        if (writes == listOf("disable")) assertTrue(step == Step.Leave, "a deregistration outside a leave — $where")
        val forced = step == Step.Join || step == Step.Leave
        if (!forced) {
            assertTrue(
                writes.isEmpty() || device.grant == LibraryGrant.GRANTED,
                "a compared transition wrote the registration under ${device.grant} — $where",
            )
            assertTrue(writes.isEmpty() || !wasConfirmed, "a compared transition re-registered a live record — $where")
        }
        if (step == Step.Join && registrable(device.osSupported, device.grant, pin = null)) {
            assertTrue(device.registry.registered, "a join where registrable left the extension unregistered — $where")
        }
    }

    /** The rig's switches as a production build reads them: no pin. */
    private object NoPin : DevControls {
        override fun uploaderPin(): UploaderPin? = null

        override fun createsPlainEvents(): Boolean = false
    }
}

/** Whether the extension may be registered for [supported], [grant] and [pin] — the domain's verdict, as the app reads it. */
private fun registrable(supported: Boolean, grant: LibraryGrant, pin: UploaderPin?): Boolean = OsUploaderAdmission.of(
    if (supported) OsSupport.CARRIED else OsSupport.NOT_CARRIED,
    grant.photoAccess(),
    if (pin?.extension == false) ExtensionSwitch.OFF else ExtensionSwitch.ON,
) == OsUploaderAdmission.REGISTRABLE

/** The membership a join persists. */
private val MEMBERSHIP =
    EventConfig(
        testEventId("E"),
        "E",
        CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-01-31T00:00:00Z")),
        endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
    )
