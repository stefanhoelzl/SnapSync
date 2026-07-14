package app.snapsync.upload

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
 * These run on JVM **and** `iosSimulatorArm64` (`commonTest`), against a fake producer.
 */
class UploadArmTest {

    /** Records the verb sequence. There is no destructive verb to record — that is the point. */
    private class FakeProducer : UploadProducer {
        val verbs = mutableListOf<String>()
        override suspend fun start() { verbs += "start" }
        override suspend fun stop() { verbs += "stop" }
    }

    /** [membershipIncludesUpload] is three-valued: `null` = **no event joined**. */
    private fun arm(
        producer: FakeProducer,
        granted: Boolean,
        membershipIncludesUpload: Boolean? = true,
    ) = UploadArm(producer, isGranted = { granted }, membershipIncludesUpload = { membershipIncludesUpload })

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

    // ---- permission grant ----------------------------------------------------------------------------

    @Test
    fun a_grant_starts_the_producer() = runTest {
        val producer = FakeProducer()

        arm(producer, granted = true, membershipIncludesUpload = true).onPermissionGranted()

        assertEquals(listOf("start"), producer.verbs)
    }

    @Test
    fun a_grant_on_a_download_only_membership_starts_nothing() = runTest {
        val producer = FakeProducer()

        arm(producer, granted = true, membershipIncludesUpload = false).onPermissionGranted()

        assertTrue(producer.verbs.isEmpty(), "photo access is needed for import, but upload stays off")
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

        arm(producer, granted = true, membershipIncludesUpload = null).onPermissionGranted()

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
        val a = UploadArm(producer, isGranted = { true }, membershipIncludesUpload = { membership })

        a.onPermissionGranted() // "I understand" → the system dialog → GRANTED, still no config
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
        val a = UploadArm(producer, isGranted = { granted }, membershipIncludesUpload = { true })

        a.onProvision() // no access yet → nothing
        granted = true
        a.onPermissionGranted()

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

    // ---- the shape of the seam itself ----------------------------------------------------------------

    @Test
    fun no_transition_can_reach_a_destructive_verb() = runTest {
        val producer = FakeProducer()
        var granted = true
        var membershipIncludesUpload: Boolean? = true
        val a = UploadArm(producer, isGranted = { granted }, membershipIncludesUpload = { membershipIncludesUpload })

        // Drive every transition the arm has, in every membership shape.
        a.onProvision()
        a.onPermissionGranted()
        membershipIncludesUpload = false
        a.onProvision()
        a.onPermissionGranted()
        granted = false
        a.onProvision()
        a.onLeave()

        // Every verb the arm can ever emit is start or stop. `UploadProducer` has no third verb to call,
        // so no lifecycle transition — provision, switch, grant, direction change, leave — can wipe durable
        // dedup state. The bug is unrepresentable, not merely absent.
        assertTrue(
            producer.verbs.all { it == "start" || it == "stop" },
            "the arm emitted something other than start/stop: ${producer.verbs}",
        )
    }
}
