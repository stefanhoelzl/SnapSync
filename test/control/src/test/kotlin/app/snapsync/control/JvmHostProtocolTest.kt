package app.snapsync.control

import app.snapsync.model.Layer
import app.snapsync.rig.JvmRigHost
import app.snapsync.rig.RigVocabulary
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The control protocol's JVM host, driven the way any caller drives either host: through [RigClient] and nothing
 * else (`docs/testing.md`, "One control protocol, served by two hosts").
 *
 * What this proves is the PROTOCOL's fidelity to the application — the routes, the compiler-generated state
 * encoding, the vocabulary's advertisement and refusals, the lanes entry points run on, the client — over both of
 * the world's backends. It proves no new app behaviour: the in-process integration surface remains the
 * behavioural suite.
 */
class JvmHostProtocolTest {

    @Test
    fun the_host_classifies_every_vocabulary_entry() = onHost { client ->
        val ad = client.device()
        assertEquals("JVM", ad.host)
        assertEquals(emptyList(), ad.unclassified, "every vocabulary entry is honoured or refused")
        assertEquals(emptyList(), ad.outsideVocabulary, "every wired verb is in the vocabulary")
        assertEquals(RigVocabulary.entries, (ad.honoured + ad.refused.keys).toSet())
        assertTrue(RigVocabulary.worldLevers.all { it in ad.honoured }, "the JVM host honours every world lever")
        assertTrue(RigVocabulary.deviceFacts.all { it in ad.honoured }, "the JVM host reads every device fact")
    }

    /**
     * The host a test drives is the phone's: its status host observes the version refusal, which the JVM host once
     * left out when it assembled its own (`docs/architecture.md`, "One shared composition").
     */
    @Test
    fun a_refused_build_reaches_the_update_required_screen() = onHost { client ->
        client.deviceVerb("backend/min-app-version", mapOf("minimum" to "100.0")).done()
        // Any backend call carries the version; a create is the first a person makes.
        client.user("create", mapOf("name" to "Old", "startsAt" to "2026-05-25T00:00:00", "endsAt" to "2026-06-20T00:00:00"))
        val refused = client.awaitState { it.ui.layer is Layer.UpdateRequired }
        assertEquals("100.0", (refused.ui.layer as Layer.UpdateRequired).minimumVersion)
    }

    /** ...and it registers for pushes, which the JVM host once never did. */
    @Test
    fun a_delivered_push_token_is_registered_on_the_backend() = onHost { client ->
        client.os("app", "onPushToken", arg = "DEADBEEF").done()
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + kotlin.time.Duration.parse("10s")
        while ("DEADBEEF" !in client.deviceVerb("backend/device-config").done()) {
            check(deadline.hasNotPassedNow()) { "the delivered token never reached the backend" }
            kotlinx.coroutines.delay(50)
        }
    }

    @Test
    fun a_user_command_the_build_cannot_honour_is_refused_not_accepted() = onHost { client ->
        // The world's reporter is configured, so the send is honoured here; the refusal path is exercised by the
        // rename that names no event while nothing is joined.
        val rename = assertIs<Reply.Refused>(client.user("rename", mapOf("name" to "x")))
        assertTrue("no joined event" in rename.reason, rename.reason)
    }

    @Test
    fun a_refused_verb_answers_409_with_the_advertised_reason() = onHost { client ->
        val advertised = client.device().refused
        val wipe = assertIs<Reply.Refused>(client.deviceVerb("gallery/wipe", mapOf("scope" to "all")))
        assertEquals(advertised.getValue("device/gallery/wipe"), wipe.reason)
    }

    @Test
    fun the_contract_verb_refuses_naming_the_JVM_host() = onHost { client ->
        val run = assertIs<Reply.Refused>(client.contract("SecureStore"))
        assertTrue("JVM" in run.reason, run.reason)
        assertTrue(run.body.startsWith("refused: "), "the contract verb's refusal carries its marker")
        assertIs<Reply.Refused>(client.contracts(), "the listing refuses rather than answering empty")
    }

    @Test
    fun a_verb_outside_the_vocabulary_is_unknown_not_refused() = onHost { client ->
        assertEquals(404, assertIs<Reply.Failed>(client.deviceVerb("no/such/verb")).status)
    }

    @Test
    fun health_reports_the_port_actually_bound() = onHost { client, host ->
        assertTrue("port=${host.port}" in client.health())
    }

    @Test
    fun create_join_upload_round_trip_over_the_mini_edge() = roundTrip("mini")

    @Test
    fun create_join_upload_round_trip_over_the_real_backend() = roundTrip("deno")

    @Test
    fun a_backend_lever_the_real_backend_cannot_honour_is_refused() = onHost("deno") { client ->
        val offline = assertIs<Reply.Refused>(client.deviceVerb("backend/offline"))
        assertTrue("unavailable on this backend" in offline.reason, offline.reason)
    }

    /**
     * The whole path a person takes, over the protocol: create an event, confirm the join the create opens, add a
     * photo, run the extension's cycle, let the "OS" finish the transfer, and see the object on the backend.
     */
    private fun roundTrip(backend: String) = onHost(backend) { client ->
        client.user(
            "create",
            mapOf("name" to "Rig Trip", "startsAt" to "2026-05-25T00:00:00", "endsAt" to "2026-06-20T00:00:00"),
        ).done()
        client.awaitState { (it.ui.layer as? Layer.JoiningEvent)?.range != null }
        client.user("confirmJoin").done()
        val joined = client.awaitState { it.ready.configResolved }
        assertIs<Layer.Joined>(joined.ui.layer)

        client.deviceVerb("gallery/seed", mapOf("n" to "1", "kind" to "policy")).done()
        val cycle = client.os("photokit-ext", "processRawValue").done()
        assertTrue("\"created\":1" in cycle, cycle)

        client.deviceVerb("jobs/complete").done()
        client.os("photokit-ext", "processRawValue").done()
        val objects = client.deviceVerb("backend/objects").done()
        assertTrue("-primary" in objects, "the backend lists the transferred object: $objects")
        client.awaitState { it.ledger.completed == 1 }
    }

    private fun onHost(backend: String = "mini", block: suspend (RigClient) -> Unit) =
        onHost(backend) { client, _ -> block(client) }

    private fun onHost(backend: String = "mini", block: suspend (RigClient, JvmRigHost) -> Unit) = runBlocking {
        val host = JvmRigHost.start(backend)
        try {
            RigClient("http://127.0.0.1:${host.port}").use { block(it, host) }
        } finally {
            host.close()
        }
    }
}
