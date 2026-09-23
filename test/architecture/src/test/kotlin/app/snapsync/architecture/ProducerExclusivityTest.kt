package app.snapsync.architecture

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.EventConfig
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.ConfigSource
import app.snapsync.feature.upload.AppUploadEngine
import app.snapsync.feature.upload.ExtensionRegistration
import app.snapsync.feature.upload.UploadAdmission
import app.snapsync.feature.upload.UploadTransitions
import app.snapsync.feature.upload.appAdmission
import app.snapsync.feature.upload.extensionAdmission
import app.snapsync.model.PermissionStatus
import app.snapsync.model.UploaderPin
import app.snapsync.model.extensionRegistrable
import app.snapsync.model.selectionScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upload transitions stop in-flight work only at a leave (capability `architecture-guards`, "The upload
 * transitions stop in-flight work only at a leave"; decision record `changes/both-uploaders-active`, D11).
 *
 * **Re-pointed, not retired.** This guard used to assert that no reachable state admitted two ledger writers.
 * Both uploaders now run by design — an overlap is a duplicate of the same object, never a loss — so that is no
 * longer the risk. The risk now is the one the redesign exists to remove: a transition that **orphans** work — a
 * deregistration that wipes the extension's in-flight OS jobs, or a cancel of the app's transfers — anywhere but at
 * a leave, where the ledger is cleared right after. Nothing repairs an orphaned `REQUESTED` row any more, so an
 * orphan is a photo that silently never uploads. Neither the compiler nor any single unit test sees every
 * transition sequence at once; this guard does.
 *
 * It drives the REAL registration fact and the REAL [UploadTransitions], over a fake registration that behaves as
 * the platform was measured to (every write refused under `LIMITED` — 3311; the read untrustworthy without a full
 * grant) and a fake app engine whose OS-held state survives a relaunch. Every script starts from each combination
 * of leftovers a previous process or installation can leave.
 *
 * **"No bare enable" is structural**, so it is not asserted here: [ExtensionRegistration] has no enable verb but
 * the ritual (`register()`), and `OsDrivenRegistrationTest` pins that the ritual disables first. "No duplicate job
 * start in a normal sequence" is a cycle property and lives in `UploadCycleTest`.
 */
class ProducerExclusivityTest {

    /** The platform's registration record as measured: unchangeable under a partial grant, unreadable without a full one. */
    private class PlatformRegistration(var grant: PermissionStatus, var registered: Boolean) : ExtensionRegistration {
        val calls = mutableListOf<String>()

        override suspend fun register() {
            calls += "register"
            if (grant != PermissionStatus.LIMITED) registered = true
        }

        override suspend fun deregister() {
            calls += "deregister"
            if (grant != PermissionStatus.LIMITED) registered = false
        }

        override fun isRegistered(): Boolean {
            calls += "read"
            return grant == PermissionStatus.GRANTED && registered
        }
    }

    /** The app engine's OS-held state. */
    private class Engine : AppUploadEngine {
        val calls = mutableListOf<String>()

        override suspend fun arm() { calls += "arm" }
        override suspend fun disarm() { calls += "disarm" }
        override suspend fun cancelTransfers() { calls += "cancel" }
        override suspend fun onForeground() = Unit
        override suspend fun onSilentPush(eventId: String) = Unit
        override suspend fun onBackgroundTask() = Unit
        override suspend fun onSelectionChanged() = Unit
        override fun onBackgroundTransfers(completion: () -> Unit) = completion()
    }

    /** One device: OS fact, grant, membership, the rig switch, and the two uploaders' platform state. */
    private class Device(val osSupported: Boolean, registered: Boolean) {
        var grant = PermissionStatus.NOT_DETERMINED
        var joined = false
        var pin: UploaderPin? = null
        val registration = PlatformRegistration(grant, registered)
        val engine = Engine()
        val transitions = UploadTransitions(
            configSource = liveMembership { "E".takeIf { joined } },
            photoAccess = liveGrant { grant },
            extensionRegistrable = { extensionRegistrable(osSupported, grant, pin) },
            registration = registration.takeIf { osSupported },
            appEngine = { engine },
        )
    }

    /** One step of a script: a real app-reachable event, with the state change that comes with it. */
    private sealed interface Step {
        data object Join : Step
        data object Reconfigure : Step
        data object Leave : Step
        data class Permission(val grant: PermissionStatus) : Step
        /** A relaunch with its UI; the grant may have changed while dead, and the switch dies with the process. */
        data class Launch(val grant: PermissionStatus) : Step
        data class Override(val pin: UploaderPin?) : Step
    }

    private val steps: List<Step> = buildList {
        add(Step.Join); add(Step.Reconfigure); add(Step.Leave)
        for (g in PermissionStatus.entries) { add(Step.Permission(g)); add(Step.Launch(g)) }
        for (pin in listOf(null, UploaderPin(app = false), UploaderPin(extension = false))) add(Step.Override(pin))
    }

    private suspend fun Device.apply(step: Step) {
        when (step) {
            Step.Join -> { joined = true; transitions.onJoin() }
            Step.Reconfigure -> { if (joined) transitions.onReconfigure() }
            Step.Leave -> { joined = false; transitions.onLeave() }
            is Step.Permission -> { grant = step.grant; registration.grant = grant; transitions.onPermissionChanged() }
            is Step.Launch -> {
                grant = step.grant; registration.grant = grant; pin = null
                transitions.onLaunch()
            }
            is Step.Override -> { pin = step.pin; transitions.onOverrideChanged() }
        }
    }

    @Test
    fun `the extension is never registrable below 26_1`() {
        // The sharper risk, with no other guard in this module: below 26.1 the registration selector does not
        // exist, so a registrable cell there is a dead process, not a wrong choice.
        for (permission in PermissionStatus.entries) {
            for (pin in listOf(null, UploaderPin(), UploaderPin(app = false), UploaderPin(extension = false))) {
                assertTrue(!extensionRegistrable(false, permission, pin), "registrable below 26.1: $permission / $pin")
            }
        }
    }

    @Test
    fun `the admission gates answer from the grant and the switch alone`() {
        for (permission in PermissionStatus.entries) {
            for (pin in listOf(null, UploaderPin(), UploaderPin(app = false), UploaderPin(extension = false))) {
                val usable = permission == PermissionStatus.GRANTED || permission == PermissionStatus.LIMITED
                val app = appAdmission(permission, selectionScope(permission, emptyList()), pin) == UploadAdmission.Admit
                assertEquals(usable && pin?.app != false, app, "app under $permission / $pin")
            }
            val ext = extensionAdmission(permission) == UploadAdmission.Admit
            assertEquals(permission == PermissionStatus.GRANTED, ext, "extension under $permission")
        }
    }

    /**
     * Every 3-step script over every reachable event, on both OSes, from every leftover registration — asserting
     * after every step that work is stopped only by a leave (or the rig's extension switch), and that no compared
     * transition writes the registration under a grant that refuses or cannot read it. (A re-provision of the
     * joined event never reaches the transitions: the provision flow runs the membership entry only on a real
     * entry — `ProvisionTest` pins that.)
     */
    @Test
    fun `no transition but a leave orphans in-flight work`() = runTest {
        var scripts = 0
        for (osSupported in listOf(true, false)) for (registered in listOf(false, true)) {
            for (a in steps) for (b in steps) for (c in steps) {
                val device = Device(osSupported, registered = registered && osSupported)
                val script = listOf(a, b, c)
                for (step in script) {
                    device.registration.calls.clear()
                    device.engine.calls.clear()
                    device.apply(step)
                    check(device, step, script)
                }
                scripts++
            }
        }
        assertTrue(scripts > 5_000, "the guard drove only $scripts scripts — its step alphabet collapsed")
    }

    private fun check(device: Device, step: Step, script: List<Step>) {
        val where = "os=${device.osSupported} after $step in $script"
        val reg = device.registration.calls
        val eng = device.engine.calls
        if (!device.osSupported) assertTrue(reg.isEmpty(), "a registration call below 26.1 — $where")
        if ("cancel" in eng) assertEquals(Step.Leave, step, "transfers cancelled outside a leave — $where")
        if ("deregister" in reg) {
            val switchedOff = step is Step.Override && step.pin?.extension == false
            assertTrue(step == Step.Leave || switchedOff, "a deregistration outside a leave or the switch — $where")
        }
        val forced = step == Step.Join || step == Step.Leave
        if (!forced && device.grant != PermissionStatus.GRANTED) {
            assertTrue(
                reg.none { it == "register" || it == "deregister" },
                "a compared transition wrote the registration under ${device.grant} — $where",
            )
        }
        val switchedOff = step is Step.Override && step.pin?.extension == false
        val couldDeregister = device.joined && device.osSupported && device.grant == PermissionStatus.GRANTED
        if (switchedOff && couldDeregister) {
            assertTrue(!device.registration.registered, "switching the extension off left it registered — $where")
        }
        if (step == Step.Join && extensionRegistrable(device.osSupported, device.grant, device.pin)) {
            assertTrue(device.registration.registered, "a join where registrable left the extension unregistered — $where")
        }
    }
}

/** A membership fake whose answer is read at every access, so a test's `var` drives it live. */
private fun liveMembership(eventId: () -> String?): ConfigSource = object : ConfigSource {
    override val config: StateFlow<EventConfig?>
        get() = MutableStateFlow(
            eventId()?.let {
                EventConfig(it, "E", captureCutoff("2026-01-01T00:00:00Z"), maxPhotoDate = captureCeiling("2099-01-01T00:00:00Z"))
            },
        )
}

/** A grant fake read at every access, so a test's `var` drives it live. */
private fun liveGrant(grant: () -> PermissionStatus): PhotoAccessStatusSource = object : PhotoAccessStatusSource {
    override val permission: StateFlow<PermissionStatus> get() = MutableStateFlow(grant())
}
