package app.snapsync.control

import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.AttestationRefusal
import app.snapsync.levers.BackendCall
import app.snapsync.levers.BackendFault
import app.snapsync.levers.BackendLevers
import app.snapsync.levers.ClockLevers
import app.snapsync.levers.LeverRefused
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PublicBackendLevers
import app.snapsync.levers.RigLevers
import app.snapsync.levers.UploadQueueLevers
import app.snapsync.rig.JvmRigHost
import app.snapsync.rig.RigVocabulary
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The control protocol's JVM host, driven the way any caller drives either host: through [RigClient] and nothing
 * else (`docs/testing.md`, "One control protocol, served by two hosts").
 *
 * What this proves is the PROTOCOL's fidelity to the application — the routes, the compiler-generated state
 * encoding, the vocabulary's advertisement and refusals, the lanes entry points run on, the client — over both of
 * the host's backends (the in-memory backend mock and the real `api/`). It proves no new app behaviour: the
 * in-process integration surface remains the behavioural suite.
 */
class JvmHostProtocolTest {

    @Test
    fun the_host_classifies_every_vocabulary_entry() = onHost { client ->
        val ad = client.device()
        assertEquals("JVM", ad.host)
        assertEquals(emptyList(), ad.unclassified, "every vocabulary entry is honoured or refused")
        assertEquals(emptyList(), ad.outsideVocabulary, "every wired verb is in the vocabulary")
        assertEquals(RigVocabulary.entries, (ad.honoured + ad.refused.keys).toSet())
        assertEquals(
            emptyList(),
            RigVocabulary.worldLevers.filterNot {
                it in ad.honoured
            },
            "the JVM host honours every world lever",
        )
        assertEquals(
            emptyList(),
            RigVocabulary.deviceFacts.filterNot {
                it in ad.honoured
            },
            "the JVM host reads every device fact",
        )
    }

    /**
     * The host a test drives is the phone's: its status host observes the version refusal, which the JVM host once
     * left out when it assembled its own (`docs/architecture.md`, "One shared composition").
     */
    @Test
    fun a_refused_build_reaches_the_update_required_screen() = onHost { client ->
        client.levers(BackendLevers).minAppVersion("100.0")
        // Any backend call carries the version; a create is the first a person makes.
        client.user(
            "create",
            mapOf("name" to "Old", "startsAt" to "2026-05-25T00:00:00", "endsAt" to "2026-06-20T00:00:00"),
        )
        val refused = client.awaitState { it.ui.layer is Layer.UpdateRequired }
        assertEquals("100.0", (refused.ui.layer as Layer.UpdateRequired).minimumVersion)
    }

    /** ...and it registers for pushes, which the JVM host once never did. */
    @Test
    fun a_delivered_push_token_is_registered_on_the_backend() = onHost { client ->
        client.os("app", "onPushToken", arg = "DEADBEEF").done()
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + kotlin.time.Duration.parse("10s")
        while (client.levers(BackendLevers).deviceConfig().token != "DEADBEEF") {
            check(deadline.hasNotPassedNow()) { "the delivered token never reached the backend" }
            kotlinx.coroutines.delay(50)
        }
    }

    /**
     * The network service moves what the screen says, through the network the app watches while in front (capability
     * `sync-status`): every value reaches the reduced state, a missing network once the watch's grace — the phone's five
     * seconds, on mocked time — has passed.
     */
    @Test
    fun the_network_service_reaches_the_screen_in_every_value() = behaviour {
        given { step("in front", act = { os("app", "onForeground") }, until = { settled { true } }) }
        whenever {
            network.set(NetworkState.OFFLINE)
            advance(5.seconds)
        }
        then { screen<Layer.CreateEvent> { it.network == NetworkNotice.OFFLINE } }
        whenever { network.set(NetworkState.BLOCKED) }
        then { screen<Layer.CreateEvent> { it.network == NetworkNotice.BLOCKED } }
        whenever { network.set(NetworkState.ONLINE) }
        then { screen<Layer.CreateEvent> { it.network == null } }
    }

    /**
     * The JVM host's loop owns the clock, so only the world moves it — with the timers due on the way; a lever that set
     * it alone would leave them behind, and is refused with the reason.
     */
    @Test
    fun the_world_moves_the_clock_and_the_clock_service_is_refused() = onHost { client ->
        val ad = client.levers(RigLevers).advertise()
        assertTrue("world.advanceTo" in ad.honoured, "honoured: ${ad.honoured}")
        assertTrue("owns the clock" in ad.refused.getValue("clock.set"), ad.refused.getValue("clock.set"))
        assertFailsWith<LeverRefused> { client.levers(ClockLevers).set("2026-01-01T00:00:00Z") }
    }

    @Test
    fun a_user_command_the_build_cannot_honour_is_refused_not_accepted() = onHost { client ->
        // The host's build reports, so the send is honoured here; the refusal path is exercised by the
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
    fun create_join_upload_round_trip_over_the_backend_mock() = roundTrip("mock")

    @Test
    fun create_join_upload_round_trip_over_the_real_backend() = roundTrip("deno")

    /** The backend refusing this phone as not genuine, for a reason the app tells (capability `privacy-security`). */
    @Test
    fun the_attestation_refusal_lever_takes_a_reason_and_none_lifts_it() = onHost { client ->
        assertTrue("backend.refuseAttestation" in client.device().honoured)
        val backend = client.levers(BackendLevers)
        backend.refuseAttestation(AttestationRefusal.DEVICE_MODIFIED)
        assertEquals(AttestationRefusal.DEVICE_MODIFIED, backend.switches().refusingAttestation)
        // Without one, the phone is genuine again.
        backend.refuseAttestation()
        assertEquals(null, backend.switches().refusingAttestation)
    }

    @Test
    fun a_backend_lever_the_real_backend_cannot_honour_is_refused() = onHost("deno") { client ->
        val refused = assertFailsWith<LeverRefused> { client.levers(BackendLevers).offline(true) }
        assertTrue("unavailable on this backend" in refused.message.orEmpty(), refused.message)
        val ad = client.levers(RigLevers).advertise()
        assertTrue("backend.offline" in ad.refused, "and the host advertises the refusal: ${ad.refused.keys}")
        assertTrue(
            "publicBackend.union" !in ad.refused && "public-backend.union" in ad.honoured,
            "the public surface is served",
        )
    }

    /**
     * Every typed service's method is an entry the host classifies — derived from its interface, never listed — served
     * over kRPC (`docs/testing.md`, "Typed services"); the advertisement is itself a typed service, and `GET /device`
     * answers the same.
     */
    @Test
    fun the_typed_services_are_advertised_and_resolve_the_joined_event() = onHost { client ->
        val ad = client.levers(RigLevers).advertise()
        assertEquals(client.device(), ad, "GET /device and the typed advertisement agree")
        assertTrue(
            "backend.refuseNextCredential" in ad.honoured,
            "an entry is the service and the method: ${ad.honoured}",
        )
        assertTrue("public-backend.foreignDeviceLeave" in ad.honoured)

        val backend = client.levers(BackendLevers)
        backend.hold(BackendCall.LEAVE)
        assertEquals(listOf(BackendCall.LEAVE), backend.switches().held)
        backend.release(BackendCall.LEAVE)
        assertEquals(emptyList(), backend.switches().held)
        val unjoined = assertFailsWith<IllegalArgumentException> { backend.membership() }
        assertTrue(
            "no joined event" in unjoined.message.orEmpty(),
            "an omitted event is the joined one: ${unjoined.message}",
        )
    }

    /**
     * A fellow member is named by a device id: any other name is refused before anything reaches the backend — no
     * event minted for it, no join to the event named.
     */
    @Test
    fun a_foreign_device_that_is_no_device_id_is_refused_with_nothing_done() = onHost { client ->
        val public = client.levers(PublicBackendLevers)
        val backend = client.levers(BackendLevers)

        backend.nextEventId(MINTED)
        val unjoined = assertFailsWith<IllegalArgumentException> { public.foreignDevice("F1", listOf("a1")) }
        assertTrue("canonical UUID" in unjoined.message.orEmpty(), unjoined.message)
        assertEquals(false, public.event(MINTED).registered, "no event was minted for it")

        val event = public.foreignDevice(MEMBER, listOf("a1"))
        assertFailsWith<IllegalArgumentException> { public.foreignDevice("F1", listOf("a2"), event = event) }
        assertEquals(null, backend.membership(event, "F1").manifest, "it was not joined to the event named")
        assertFailsWith<IllegalArgumentException> { public.foreignDeviceLeave("F1", event) }
        assertFailsWith<IllegalArgumentException> { public.foreignDeviceReceived("F1", event) }
    }

    /**
     * The backend mock's per-route faults, reachable over the channel: a fault on the create route reaches the screen,
     * and the next create — the fault spent — goes through. An operation the device API does not declare is refused,
     * since a fault on it would never fire.
     */
    @Test
    fun a_route_fault_reaches_the_screen_once() = onHost { client ->
        val backend = client.levers(BackendLevers)
        val unknown =
            assertFailsWith<IllegalArgumentException> { backend.fail("createEvents", BackendFault.MAINTENANCE) }
        assertTrue("createEvent" in unknown.message.orEmpty(), unknown.message)

        backend.fail("createEvent", BackendFault.MAINTENANCE)
        val create = mapOf("name" to "Faulty", "startsAt" to "2026-05-25T00:00:00", "endsAt" to "2026-06-20T00:00:00")
        client.user("create", create).done()
        client.awaitState { (it.ui.layer as? Layer.CreateEvent)?.error != null }
        client.user("create", create).done()
        client.awaitState { (it.ui.layer as? Layer.JoiningEvent)?.range != null }
    }

    /**
     * The device's mobile-data choice (capability `mobile-data`; decision record `changes/archive/2026-10-07-mobile-data-per-device`) is
     * flipped from the menu over the channel — before any event, and it carries into the one joined after.
     */
    @Test
    fun the_mobile_data_choice_is_the_devices_and_driven_from_the_menu() = onHost { client ->
        client.user("mobileData", mapOf("on" to "false")).done()
        client.awaitState { !it.ui.mobileData.on }

        client.user(
            "create",
            mapOf("name" to "Data", "startsAt" to "2026-05-25T00:00:00", "endsAt" to "2026-06-20T00:00:00"),
        ).done()
        client.awaitState { (it.ui.layer as? Layer.JoiningEvent)?.range != null }
        client.user("confirmJoin").done()
        val joined = client.awaitState { it.ready.configResolved }
        assertEquals(false, joined.ui.mobileData.on, "the joined event follows the device's choice")

        client.user("mobileData", mapOf("on" to "true")).done()
        client.awaitState { it.ui.mobileData.on }
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

        // The extension's cycle alone: the app's uploader, running beside it as on a phone, would take the same photo.
        client.deviceVerb("uploaders", mapOf("app" to "off")).done()
        client.deviceVerb("gallery/seed", mapOf("n" to "1", "kind" to "policy")).done()
        val cycle = client.os("photokit-ext", "processRawValue").done()
        assertTrue("\"created\":1" in cycle, cycle)

        client.levers(UploadQueueLevers).completeJob()
        client.os("photokit-ext", "processRawValue").done()
        val backend = client.levers(PublicBackendLevers)
        val objects = backend.objects()
        assertTrue(objects.any { "-primary" in it }, "the backend lists the transferred object: $objects")
        val union = backend.union()
        assertTrue(
            union.any { "primary" in it.roles },
            "the second cycle published the photo, and the event serves it: $union",
        )
    }

    /**
     * A relaunch is process death (`docs/testing.md`, "The JVM root"): the new app finds exactly what the device keeps
     * — the membership, the photo library, the operating system's upload jobs — and composes everything else anew.
     */
    @Test
    fun a_relaunch_keeps_what_the_device_keeps() = onHost { client ->
        client.user(
            "create",
            mapOf("name" to "Relaunch", "startsAt" to "2026-05-25T00:00:00", "endsAt" to "2026-06-20T00:00:00"),
        ).done()
        client.awaitState { (it.ui.layer as? Layer.JoiningEvent)?.range != null }
        client.user("confirmJoin").done()
        val event = client.awaitState { it.ready.configResolved }.ready.eventId
        // The extension's cycle alone: the app's uploader, running beside it as on a phone, would take the same photo.
        client.deviceVerb("uploaders", mapOf("app" to "off")).done()
        client.deviceVerb("gallery/seed", mapOf("n" to "1", "kind" to "policy")).done()
        client.os("photokit-ext", "processRawValue").done()

        client.deviceVerb("relaunch").done()

        assertEquals(event, client.awaitState { it.ready.configResolved }.ready.eventId, "the membership survives")
        assertEquals(1L, client.gallery(cutoff = "1970-01-01T00:00:00Z").census.total, "the library survives")
        assertEquals(1, client.levers(UploadQueueLevers).queue().created.size, "the OS's upload job survives")
    }

    private fun onHost(backend: String = "mock", block: suspend (RigClient) -> Unit) =
        onHost(backend) { client, _ -> block(client) }

    private fun onHost(backend: String = "mock", block: suspend (RigClient, JvmRigHost) -> Unit) = runBlocking {
        val host = JvmRigHost.start(backend)
        try {
            RigClient("http://127.0.0.1:${host.port}").use { block(it, host) }
        } finally {
            host.close()
        }
    }

    private companion object {
        const val MINTED = "0b0e5f2a-7c1d-4e8a-9f3b-2d6c8a1e4f70"
        const val MEMBER = "5d2c9e1a-3b4f-4a6d-8e7c-1f0a2b3c4d5e"
    }
}
