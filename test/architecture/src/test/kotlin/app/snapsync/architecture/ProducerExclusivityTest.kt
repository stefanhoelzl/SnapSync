package app.snapsync.architecture

import app.snapsync.feature.upload.AppUploadEngine
import app.snapsync.feature.upload.ExtensionRegistration
import app.snapsync.feature.upload.UploadAdmission
import app.snapsync.feature.upload.UploadTransitions
import app.snapsync.feature.upload.appAdmission
import app.snapsync.feature.upload.extensionAdmission
import app.snapsync.model.PermissionStatus
import app.snapsync.model.UploadMechanism
import app.snapsync.model.resolveUploadMechanism
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Exactly one process writes the ledger (capability `architecture-guards`, "The upload producers are never both
 * started"; the behavioural half of `upload-lifecycle`'s "Exactly one mechanism writes the ledger, enforced at
 * each engine's entry gate").
 *
 * **Re-pointed, not retired.** Exclusion used to be structural — an arm held one mechanism reference, so starting
 * two had no expression, and this guard drove that arm's transitions. The arm is gone. Exclusion is now a gate
 * decision in each process plus the OS's registration record, kept consistent by the stateless
 * [UploadTransitions]. That is a weaker form, and exactly why the guard must stay: what can now go wrong is an
 * admission function and a registration reconcile drifting apart, and neither the compiler nor any single unit
 * test sees both at once.
 *
 * It drives the REAL resolver, both REAL admission functions and the REAL [UploadTransitions], over a fake
 * registration that behaves as the platform was measured to (every write refused under `LIMITED` — 3311; the
 * read untrustworthy without a full grant) and a fake app engine whose armed state, like the OS-held transfers
 * and heartbeat it stands for, survives a relaunch. Every script starts from each combination of leftovers a
 * previous process or installation can leave: a surviving registration record, a still-armed app engine.
 *
 * **"No bare enable" is structural now**, so it is not asserted here: [ExtensionRegistration] has no enable verb
 * but the ritual (`register()`), and `OsDrivenRegistrationTest` pins that the ritual disables and demotes first.
 */
class ProducerExclusivityTest {

    /** The platform's registration record as measured: unchangeable under a partial grant, unreadable without a full one. */
    private class PlatformRegistration(var grant: PermissionStatus, var registered: Boolean) : ExtensionRegistration {
        /** Registration writes attempted during the current step. */
        var writes = 0

        override suspend fun register() {
            writes++
            if (grant != PermissionStatus.LIMITED) registered = true
        }

        override suspend fun deregister() {
            writes++
            if (grant != PermissionStatus.LIMITED) registered = false
        }

        override fun isRegistered(): Boolean = grant == PermissionStatus.GRANTED && registered
    }

    /** The app engine's OS-held state: in-flight transfers and a submitted heartbeat, surviving a relaunch. */
    private class Engine(var armed: Boolean) : AppUploadEngine {
        /** Whether the current step armed the engine (as opposed to leaving it armed). */
        var armedThisStep = false

        override suspend fun arm() { armed = true; armedThisStep = true }
        override suspend fun disarm() { armed = false }
        override suspend fun onForeground() = Unit
        override suspend fun onSilentPush(eventId: String) = Unit
        override suspend fun onBackgroundTask() = Unit
        override suspend fun onSelectionChanged() = Unit
    }

    /** One device: OS fact, grant, membership posture, the rig pin, and the two mechanisms' platform state. */
    private class Device(val osSupported: Boolean, registered: Boolean, armed: Boolean) {
        var grant = PermissionStatus.NOT_DETERMINED
        var posture: Boolean? = null
        var pin: UploadMechanism? = null
        val registration = PlatformRegistration(grant, registered)
        val engine = Engine(armed)
        val transitions = UploadTransitions(
            resolve = ::resolved,
            membershipIncludesUpload = { posture },
            permission = { grant },
            registration = registration.takeIf { osSupported },
            appEngine = { engine },
        )

        fun resolved() = resolveUploadMechanism(osSupported, grant, pin)
        val joined get() = posture != null

        /** The app process may write: its gate admits (it writes whether armed or not — triggers always reach it). */
        val appWrites get() = joined && appAdmission(resolved()) == UploadAdmission.Admit

        /** The extension may write: the OS can invoke it, and its own gate admits. */
        val extensionWrites
            get() = osSupported && joined && registration.registered &&
                extensionAdmission(grant) == UploadAdmission.Admit

        /** Leftover app transfers record outcomes from the app process — through its delegate, outside any gate. */
        val appTransfersLive get() = engine.armed
    }

    /** One step of a script: a real app-reachable event, with the state change that comes with it. */
    private sealed interface Step {
        val forcesRegistration: Boolean get() = false

        data class Join(val includesUpload: Boolean) : Step {
            override val forcesRegistration get() = true
        }
        data object Reconfigure : Step
        data object Leave : Step {
            override val forcesRegistration get() = true
        }
        data class Permission(val grant: PermissionStatus) : Step
        /** A relaunch with its UI; the grant may have changed while dead, and the pin dies with the process. */
        data class Launch(val grant: PermissionStatus) : Step
        data class Override(val pin: UploadMechanism?) : Step
    }

    private val steps: List<Step> = buildList {
        add(Step.Join(true)); add(Step.Join(false)); add(Step.Reconfigure); add(Step.Leave)
        for (g in PermissionStatus.entries) { add(Step.Permission(g)); add(Step.Launch(g)) }
        for (pin in listOf(null) + UploadMechanism.entries) add(Step.Override(pin))
    }

    private suspend fun Device.apply(step: Step) {
        when (step) {
            is Step.Join -> { posture = step.includesUpload; transitions.onJoin() }
            Step.Reconfigure -> { if (posture != null) { posture = true; transitions.onReconfigure() } }
            Step.Leave -> { posture = null; transitions.onLeave() }
            is Step.Permission -> { grant = step.grant; registration.grant = grant; transitions.onPermissionChanged() }
            is Step.Launch -> {
                grant = step.grant; registration.grant = grant; pin = null
                transitions.onLaunch()
            }
            is Step.Override -> { pin = step.pin; transitions.onOverrideChanged() }
        }
    }

    @Test
    fun `no resolver cell yields a mechanism the OS cannot run`() {
        // The sharper risk, and the one with no other guard in this module: below 26.1 the registration
        // selector does not exist, so a PHOTOKIT cell there is a dead process, not a wrong choice.
        for (permission in PermissionStatus.entries) {
            for (override in listOf(null) + UploadMechanism.entries) {
                assertTrue(
                    resolveUploadMechanism(false, permission, override) != UploadMechanism.PHOTOKIT,
                    "os-driven mechanism resolved below 26.1: permission=$permission override=$override",
                )
            }
        }
    }

    @Test
    fun `the two admission answers never both admit without a registration to arbitrate`() {
        // With no override the two gates are disjoint on their own: the app admits only when resolution yields
        // the app-driven kind, which on an OS carrying the OS-driven mechanism means "not GRANTED" — exactly
        // when the extension withholds. Only an override can make both admit, and that case is what the
        // registration reconcile below exists for.
        for (permission in PermissionStatus.entries) {
            val app = appAdmission(resolveUploadMechanism(true, permission, null))
            val ext = extensionAdmission(permission)
            assertTrue(
                !(app == UploadAdmission.Admit && ext == UploadAdmission.Admit),
                "both processes admit under $permission with no override",
            )
        }
    }

    /**
     * Every 3-step script over every reachable event, on both OSes, from every combination of leftovers —
     * asserting after every step that no two processes can write the ledger, that the app engine is armed only
     * where resolution yields it, and that no compared transition attempts a registration write the platform
     * would refuse or could not read for.
     */
    @Test
    fun `no transition sequence admits two ledger writers`() = runTest {
        var scripts = 0
        for (osSupported in listOf(true, false)) for (registered in listOf(false, true)) for (armed in listOf(false, true)) {
            for (a in steps) for (b in steps) for (c in steps) {
                val device = Device(osSupported, registered = registered && osSupported, armed = armed)
                val script = listOf(a, b, c)
                for (step in script) {
                    device.registration.writes = 0
                    device.engine.armedThisStep = false
                    device.apply(step)
                    check(device, step, script)
                }
                scripts++
            }
        }
        assertTrue(scripts > 10_000, "the guard drove only $scripts scripts — its step alphabet collapsed")
    }

    private fun check(device: Device, step: Step, script: List<Step>) {
        val where = "os=${device.osSupported} after $step in $script"
        if (device.appWrites && device.extensionWrites) {
            fail("two ledger writers: the app admits AND a registered extension admits — $where")
        }
        if (device.extensionWrites && device.appTransfersLive) {
            fail("app transfers left live under a writing extension — their delegate would record from the app — $where")
        }
        if (device.engine.armedThisStep) {
            assertTrue(
                device.posture == true && device.resolved() == UploadMechanism.URL_SESSION,
                "the app engine was armed without resolution yielding it for an upload-inclusive membership — $where",
            )
        }
        val pinnedAwayUnderFullGrant = device.grant == PermissionStatus.GRANTED &&
            device.resolved() != UploadMechanism.PHOTOKIT
        if (step is Step.Override && device.joined && pinnedAwayUnderFullGrant) {
            assertTrue(
                !device.registration.registered,
                "a pin away from the OS-driven mechanism left the extension registered — it cannot read the pin — $where",
            )
        }
        if (!step.forcesRegistration && device.grant != PermissionStatus.GRANTED) {
            assertTrue(
                device.registration.writes == 0,
                "a compared transition attempted a registration write under ${device.grant} — $where",
            )
        }
    }
}
