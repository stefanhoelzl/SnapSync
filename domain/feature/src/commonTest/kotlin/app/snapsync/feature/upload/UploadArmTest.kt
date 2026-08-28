package app.snapsync.feature.upload

import app.snapsync.model.PermissionStatus
import app.snapsync.model.UploadMechanism
import app.snapsync.model.resolveUploadMechanism
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upload-arm lifecycle (capability `upload-lifecycle`) — the tests that could not exist before this
 * change, because the logic lived in `SnapSyncRoot`, which is wiring-only and untested by the project's
 * hard rule. Grep found **zero** tests touching it, which is how the app-driven tier shipped a provision
 * path that tore the upload arm down and started nothing.
 *
 * Since `accept-limited-photo-access` the arm is **permission-aware** over the composed producers: the
 * OS-driven mechanism runs under `GRANTED` (where composed), the app-driven one under `LIMITED` (the OS
 * never invokes the extension there — measured), and a flip between them is a stop-then-start switch.
 *
 * These run on JVM **and** `iosSimulatorArm64` (`commonTest`), against fake producers.
 */
class UploadArmTest {

    /** Records the verb sequence. There is no destructive verb to record — that is the point. */
    private class FakeProducer(private val name: String = "") : UploadMechanismRuntime {
        val verbs = mutableListOf<String>()
        var shared: MutableList<String>? = null
        override suspend fun start() { verbs += "start"; shared?.add("$name.start") }
        override suspend fun stop() { verbs += "stop"; shared?.add("$name.stop") }
        override suspend fun onForeground() = Unit
        override suspend fun onSilentPush(eventId: String) = Unit
        override suspend fun onBackgroundTask() = Unit
        override suspend fun onSelectionChanged() = Unit
    }

    /** The single-mechanism shape (iOS 18–26.0, the world): the OS carries no OS-driven mechanism. */
    private fun arm(
        producer: FakeProducer,
        granted: Boolean,
        membershipIncludesUpload: Boolean? = true,
    ) = UploadArm(
        resolve = {
            resolveUploadMechanism(
                backgroundUploadSupported = false,
                permission = if (granted) PermissionStatus.GRANTED else PermissionStatus.NOT_DETERMINED,
            )
        },
        mechanismFor = { kind -> if (kind == UploadMechanism.IDLE) IdleUploadMechanism else producer },
        membershipIncludesUpload = { membershipIncludesUpload },
    )

    /** The both-mechanisms shape (iOS ≥26.1): resolution picks, and the app-driven cell relinquishes. */
    private class BothProducers {
        val log = mutableListOf<String>()
        val osDriven = FakeProducer("os").also { it.shared = log }
        val appDriven = FakeProducer("app").also { it.shared = log }
    }

    /**
     * Mirrors the real factory (`compose/`): on an OS carrying both, the app-driven cell is wrapped so it
     * gives the OS back a registration this process must not run behind. Wiring `appDriven` bare here
     * would still pass the switch tests while testing nothing — the deregistration is a property of the
     * *cell*, not of the arm stopping a sibling, and that relocation is the whole point of the change.
     */
    private fun armBoth(
        both: BothProducers,
        permission: () -> PermissionStatus,
        membershipIncludesUpload: () -> Boolean? = { true },
        override: UploadMechanism? = null,
    ) = UploadArm(
        resolve = {
            resolveUploadMechanism(
                backgroundUploadSupported = true,
                permission = permission(),
                override = override,
            )
        },
        mechanismFor = { kind ->
            when (kind) {
                UploadMechanism.PHOTOKIT -> RelinquishThenRun({ both.appDriven.stop() }, both.osDriven)
                UploadMechanism.URL_SESSION -> RelinquishThenRun({ both.osDriven.stop() }, both.appDriven)
                UploadMechanism.IDLE -> IdleUploadMechanism
            }
        },
        membershipIncludesUpload = membershipIncludesUpload,
    )

    // ---- provision -----------------------------------------------------------------------------------

    /**
     * THE REGRESSION TEST. On the app-driven tier this path used to resolve to a full teardown — cancel
     * transfers, cancel the heartbeat, wipe the ledger and the discovery cursor — and then start nothing.
     */
    @Test
    fun provisioning_with_access_already_granted_starts_the_producer() = runTest {
        val producer = FakeProducer()

        arm(producer, granted = true, membershipIncludesUpload = true).onProvision()

        assertEquals(listOf("start"), producer.verbs)
    }

    @Test
    fun provisioning_a_download_only_membership_stops_the_producer() = runTest {
        val producer = FakeProducer()

        // Not merely "skip the start": a grant that landed before this join may already have started it.
        arm(producer, granted = true, membershipIncludesUpload = false).onProvision()

        assertEquals(listOf("stop"), producer.verbs)
    }

    @Test
    fun provisioning_without_access_fires_neither_verb() = runTest {
        val producer = FakeProducer()

        arm(producer, granted = false, membershipIncludesUpload = true).onProvision()

        assertTrue(producer.verbs.isEmpty(), "the grant transition drives this case, not the provision")
    }

    // ---- permission change ---------------------------------------------------------------------------

    @Test
    fun a_grant_starts_the_producer() = runTest {
        val producer = FakeProducer()

        arm(producer, granted = true, membershipIncludesUpload = true).onPermissionChanged()

        assertEquals(listOf("start"), producer.verbs)
    }

    @Test
    fun a_grant_on_a_download_only_membership_starts_nothing() = runTest {
        val producer = FakeProducer()

        arm(producer, granted = true, membershipIncludesUpload = false).onPermissionChanged()

        assertTrue(producer.verbs.isEmpty(), "photo access is needed for import, but upload stays off")
    }

    // ---- the permission-selected producer (capability `limited-photo-access`) -------------------------

    @Test
    fun granted_runs_the_os_driven_producer_where_composed() = runTest {
        val both = BothProducers()

        armBoth(both, permission = { PermissionStatus.GRANTED }).onProvision()

        assertEquals(listOf("start"), both.osDriven.verbs)
        assertEquals(listOf("stop"), both.appDriven.verbs, "the non-selected mechanism is stopped, never started")
    }

    @Test
    fun limited_runs_the_app_driven_producer_never_the_extension() = runTest {
        val both = BothProducers()

        // The OS never invokes the extension under a partial grant (measured, `ios-photokit-upload`) —
        // starting it would be a silent no-op forever ("Synchronization pending…" with no mechanism).
        armBoth(both, permission = { PermissionStatus.LIMITED }).onProvision()

        assertEquals(listOf("start"), both.appDriven.verbs)
        assertEquals(listOf("stop"), both.osDriven.verbs, "the extension is stopped (deregistered), never started")
    }

    @Test
    fun a_full_to_limited_flip_switches_stop_then_start() = runTest {
        val both = BothProducers()
        var permission = PermissionStatus.GRANTED
        val a = armBoth(both, permission = { permission })

        a.onProvision() // GRANTED → the OS-driven mechanism runs
        permission = PermissionStatus.LIMITED
        a.onPermissionChanged()

        // The outgoing producer's stop() (which deregisters the extension) completes BEFORE the
        // incoming one starts — the exactly-one-started invariant's switch rule (`upload-lifecycle`).
        assertEquals(listOf("app.stop", "os.start", "os.stop", "app.start"), both.log)
    }

    @Test
    fun a_limited_to_full_flip_switches_back_stop_then_start() = runTest {
        val both = BothProducers()
        var permission = PermissionStatus.LIMITED
        val a = armBoth(both, permission = { permission })

        a.onProvision() // LIMITED → the app-driven mechanism runs
        permission = PermissionStatus.GRANTED
        a.onPermissionChanged()

        assertEquals(listOf("os.stop", "app.start", "app.stop", "os.start"), both.log)
    }

    @Test
    fun at_most_one_producer_is_ever_started_across_every_transition() = runTest {
        val both = BothProducers()
        var permission = PermissionStatus.NOT_DETERMINED
        var membership: Boolean? = null
        val a = armBoth(both, permission = { permission }, membershipIncludesUpload = { membership })

        // Walk the lifecycle table with permission flips interleaved.
        a.onPermissionChanged()                     // NOT_DETERMINED, no membership → nothing
        membership = true
        permission = PermissionStatus.GRANTED
        a.onProvision()                             // os runs
        permission = PermissionStatus.LIMITED
        a.onPermissionChanged()                     // switch → app runs
        a.onProvision()                             // re-provision under limited → app (idempotent)
        permission = PermissionStatus.GRANTED
        a.onPermissionChanged()                     // switch back → os runs
        membership = false
        a.onProvision()                             // download-only → all stopped
        a.onLeave()

        // Replay the log: after every event, the number of started-but-not-stopped producers is ≤ 1.
        val started = mutableSetOf<String>()
        for (event in both.log) {
            val (who, verb) = event.split(".")
            if (verb == "start") started += who else started -= who
            assertTrue(started.size <= 1, "both producers started at once: ${both.log}")
        }
        assertTrue(started.isEmpty(), "leave must stop everything: ${both.log}")
    }

    // ---- no membership, no arm -----------------------------------------------------------------------

    /**
     * THE OTHER REGRESSION TEST. The join gate's photo-access explainer raises the system dialog **before**
     * the join is confirmed (capability `join-event`), so a grant now routinely lands with no event
     * configured. The root used to answer that with `?: true` — a two-valued "enabled" flag that could not
     * distinguish "no membership" from "a membership that uploads" — and started the producer for an event
     * that does not exist. That violates `join-event`'s own gate: "no config is saved and **no upload
     * producer is enabled** until the user confirms".
     */
    @Test
    fun a_grant_with_no_membership_fires_neither_verb() = runTest {
        val producer = FakeProducer()

        arm(producer, granted = true, membershipIncludesUpload = null).onPermissionChanged()

        assertTrue(
            producer.verbs.isEmpty(),
            "a grant with no event joined armed a producer for nothing: ${producer.verbs}",
        )
    }

    /**
     * The flow the explainer actually creates: grant first (no config yet), confirm the join second. The
     * producer is armed at the **join**, which is the only moment there is an event to upload to.
     */
    @Test
    fun the_join_after_a_membership_less_grant_is_what_arms_the_producer() = runTest {
        val producer = FakeProducer()
        var membership: Boolean? = null // no event joined — the user is on the explainer
        val a = UploadArm(
            resolve = { resolveUploadMechanism(false, PermissionStatus.GRANTED) },
            mechanismFor = { kind -> if (kind == UploadMechanism.IDLE) IdleUploadMechanism else producer },
            membershipIncludesUpload = { membership },
        )

        a.onPermissionChanged() // "I understand" → the system dialog → GRANTED, still no config
        assertTrue(producer.verbs.isEmpty(), "nothing may be armed before the user confirms the join")

        membership = true // the user confirms; config is provisioned
        a.onProvision()

        assertEquals(listOf("start"), producer.verbs)
    }

    // ---- join-then-grant, the ordering that used to work by accident ---------------------------------

    @Test
    fun joining_before_the_grant_still_starts_once_access_arrives() = runTest {
        val producer = FakeProducer()
        var granted = false
        val a = UploadArm(
            resolve = {
                resolveUploadMechanism(
                    backgroundUploadSupported = false,
                    permission = if (granted) PermissionStatus.GRANTED else PermissionStatus.NOT_DETERMINED,
                )
            },
            mechanismFor = { kind -> if (kind == UploadMechanism.IDLE) IdleUploadMechanism else producer },
            membershipIncludesUpload = { true },
        )

        a.onProvision() // no access yet → nothing
        granted = true
        a.onPermissionChanged()

        assertEquals(listOf("start"), producer.verbs)
    }

    // ---- leave ---------------------------------------------------------------------------------------

    @Test
    fun leaving_stops_the_producer_and_destroys_nothing() = runTest {
        val producer = FakeProducer()

        arm(producer, granted = true).onLeave()

        // `stop()` is the ONLY verb reachable on leave. The seam exposes no way to clear the ledger or the
        // discovery cursor, so cross-event dedup survives — a later join re-uploads nothing already stored.
        assertEquals(listOf("stop"), producer.verbs)
    }

    @Test
    fun leaving_stops_every_composed_producer() = runTest {
        val both = BothProducers()
        val a = armBoth(both, permission = { PermissionStatus.GRANTED })

        a.onProvision()
        a.onLeave()

        assertEquals("stop", both.osDriven.verbs.last())
        assertEquals("stop", both.appDriven.verbs.last())
    }

    // ---- the shape of the seam itself ----------------------------------------------------------------

    @Test
    fun no_transition_can_reach_a_destructive_verb() = runTest {
        val both = BothProducers()
        var permission = PermissionStatus.GRANTED
        var membershipIncludesUpload: Boolean? = true
        val a = armBoth(both, permission = { permission }, membershipIncludesUpload = { membershipIncludesUpload })

        // Drive every transition the arm has, in every membership shape, across permission flips.
        a.onProvision()
        a.onPermissionChanged()
        permission = PermissionStatus.LIMITED
        a.onPermissionChanged()
        membershipIncludesUpload = false
        a.onProvision()
        a.onPermissionChanged()
        permission = PermissionStatus.NOT_DETERMINED
        a.onProvision()
        a.onLeave()

        // Every verb the arm can ever emit is start or stop. `UploadProducer` has no third verb to call,
        // so no lifecycle transition — provision, switch, grant, direction change, leave — can wipe durable
        // dedup state. The bug is unrepresentable, not merely absent.
        assertTrue(
            both.log.all { it.endsWith(".start") || it.endsWith(".stop") },
            "the arm emitted something other than start/stop: ${both.log}",
        )
    }
}
