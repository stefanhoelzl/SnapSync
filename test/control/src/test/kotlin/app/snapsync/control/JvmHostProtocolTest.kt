package app.snapsync.control

import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.AttestationRefusal
import app.snapsync.levers.BackendCall
import app.snapsync.levers.BackendFault
import app.snapsync.levers.BackendLevers
import app.snapsync.levers.BackgroundTimeLevers
import app.snapsync.levers.ClockLevers
import app.snapsync.levers.ContractLevers
import app.snapsync.levers.DevControlsLevers
import app.snapsync.levers.EntryOutcomeKind
import app.snapsync.levers.ExtensionAnswer
import app.snapsync.levers.ExtensionHostLevers
import app.snapsync.levers.ExtensionRegistryLevers
import app.snapsync.levers.GalleryLevers
import app.snapsync.levers.HostLevers
import app.snapsync.levers.LeverRefused
import app.snapsync.levers.LifecycleLevers
import app.snapsync.levers.LinksLevers
import app.snapsync.levers.NetworkState
import app.snapsync.levers.OsLevers
import app.snapsync.levers.PlayedOsLevers
import app.snapsync.levers.PublicBackendLevers
import app.snapsync.levers.PushLevers
import app.snapsync.levers.RigLevers
import app.snapsync.levers.ScreenLevers
import app.snapsync.levers.SeedKind
import app.snapsync.levers.UploadQueueLevers
import app.snapsync.levers.WakeLevers
import app.snapsync.levers.WipeScope
import app.snapsync.levers.WorldEvent
import app.snapsync.rig.JvmRigHost
import app.snapsync.rig.RigVocabulary
import app.snapsync.rig.UPLOAD_HEARTBEAT_TASK
import app.snapsync.rig.UPLOAD_TRANSFER_CHANNEL
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.UiIntent
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
    }

    /**
     * The host a test drives is the phone's: its status host observes the version refusal, which the JVM host once
     * left out when it assembled its own (`docs/architecture.md`, "One shared composition").
     */
    @Test
    fun a_refused_build_reaches_the_update_required_screen() = onHost { client ->
        client.levers(BackendLevers).minAppVersion("100.0")
        // Any backend call carries the version; a create is the first a person makes.
        client.person().createEvent("Old", "2026-05-25T00:00:00", "2026-06-20T00:00:00")
        val refused = client.awaitState { it.ui.layer is Layer.UpdateRequired }
        assertEquals("100.0", (refused.ui.layer as Layer.UpdateRequired).minimumVersion)
    }

    /**
     * The JVM host plays the whole operating system: it delivers every entry point, expires what it handed over, invokes
     * the extension, and serves every OS system's face — no method of them is refused here.
     */
    @Test
    fun the_operating_systems_services_are_all_honoured_here() = onHost { client ->
        val advertised = client.levers(RigLevers).advertise()
        val os = listOf(
            OsLevers, PlayedOsLevers, ExtensionHostLevers, LifecycleLevers, LinksLevers, PushLevers, WakeLevers,
            BackgroundTimeLevers, ExtensionRegistryLevers,
        ).flatMap { it.entries }
        assertEquals(emptyList(), os - advertised.honoured.toSet(), "refused: ${advertised.refused}")
    }

    /**
     * Whether the played OS carries iOS's own uploader is a fact of the device that the app reads at launch, so the
     * build's reported tier moves at the next launch and not before.
     */
    @Test
    fun the_os_carrying_its_uploader_reaches_the_next_launch() = onHost { client ->
        val host = client.levers(HostLevers)
        assertEquals("app", host.state().build["uploadTier"], "the JVM host's OS carries none by default")
        client.levers(ExtensionRegistryLevers).carried(true)
        assertEquals("app", host.state().build["uploadTier"], "the running launch keeps what it composed with")
        host.relaunch(scene = true)
        assertEquals("app+extension", host.state().build["uploadTier"])
    }

    /**
     * After a force-quit the played OS launches the app for nothing — each delivery that would launch it refused with
     * the reason — while it keeps invoking iOS's own uploader; the member opening the app ends it, and the wakes the
     * app asked for before are still there.
     */
    @Test
    fun a_force_quit_app_is_launched_for_nothing_until_it_is_opened() = onHost { client ->
        val os = client.levers(OsLevers)
        val wakes = client.levers(WakeLevers).wakes()
        client.levers(PlayedOsLevers).forceQuit()

        val refused = listOf<suspend () -> Unit>(
            { os.backgroundTask(UPLOAD_HEARTBEAT_TASK) },
            { os.silentPush() },
            { os.backgroundTransfers(UPLOAD_TRANSFER_CHANNEL) },
            { os.background() },
            { os.pushToken("BEEF") },
        )
        refused.forEach { delivery ->
            val why = assertFailsWith<LeverRefused> { delivery() }
            assertTrue("force-quit" in why.message.orEmpty(), why.message)
        }
        client.levers(ExtensionHostLevers).process()
        assertEquals(wakes, client.levers(WakeLevers).wakes(), "the wakes the app asked for are kept")

        os.foreground()
        os.backgroundTask(UPLOAD_HEARTBEAT_TASK)
    }

    /** ...and it registers for pushes, which the JVM host once never did. */
    @Test
    fun a_delivered_push_token_is_registered_on_the_backend() = onHost { client ->
        client.levers(OsLevers).pushToken("DEADBEEF")
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
        given { step("in front", act = { os.foreground() }, until = { settled { true } }) }
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
     * The app's runtime tells each entry point's work on the world stream (`docs/testing.md`, "How a test waits"): a tap
     * and an operating-system delivery the channel plays are each delivered, answered and done — every one tagged with
     * the act that made it, so a test waits on its own act's `done`.
     */
    @Test
    fun every_entry_point_s_work_reaches_the_stream_tagged_with_its_act() = behaviour {
        given { step("in front", act = { os.foreground() }, until = { settled { true } }) }
        whenever { tap(UiIntent.MenuOpen) }
        then {
            inOrder {
                event<WorldEvent.Delivered> { it.entry == "OnTap" && it.about == "MenuOpen" && it.tag != null }
                event<WorldEvent.Answered> { it.entry == "OnTap" && it.tag != null }
                event<WorldEvent.Done> {
                    it.entry == "OnTap" && it.outcome == EntryOutcomeKind.COMPLETED && it.tag != null
                }
            }
        }
        whenever { os.silentPush() }
        then {
            inOrder {
                event<WorldEvent.Delivered> { it.entry == "OnSilentPush" && it.tag != null }
                event<WorldEvent.Done> { it.entry == "OnSilentPush" && it.tag != null }
            }
        }
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

    /**
     * A person's one verb is a tap (`ScreenLevers`): any intent reaches the app as the screen hands it over, and what the
     * screen shows is read back as the host's state reports it. The JVM host's screen is the mock's, so it plays the
     * live screen too.
     */
    @Test
    fun a_tap_reaches_the_app_and_the_screen_shows_what_it_did() = onHost { client ->
        val ad = client.device()
        assertTrue(
            listOf("screen.tap", "screen.shown", "screen.live").all { it in ad.honoured },
            ad.honoured.toString(),
        )
        val screen = client.levers(ScreenLevers)
        screen.tap(UiIntent.CreateName("Tapped"))
        val shown = client.awaitState { (it.ui.layer as? Layer.CreateEvent)?.form?.name == "Tapped" }
        assertEquals(shown.ui.layer, screen.shown()?.layer, "the screen shows what the host's state reports")
    }

    /**
     * A host's own service is classified method by method (`LeverNeed.HOST`): a method this host refuses throws its
     * advertised reason, and is never invoked — the JVM host's library is a mock, which no PhotoKit wipe reaches.
     */
    @Test
    fun a_refused_host_verb_answers_the_advertised_reason() = onHost { client ->
        val advertised = client.device().refused
        val wipe = assertFailsWith<LeverRefused> { client.levers(GalleryLevers).wipe(WipeScope.ALL) }
        assertTrue(advertised.getValue("gallery.wipe") in wipe.message.orEmpty(), wipe.message)
        assertTrue("gallery.seed" in client.device().honoured, "the same service honours what the host can do")
    }

    @Test
    fun the_contracts_are_refused_naming_the_JVM_host() = onHost { client ->
        val contract = client.levers(ContractLevers)
        val run = assertFailsWith<LeverRefused> { contract.run("SecureStore") }
        assertTrue("JVM" in run.message.orEmpty(), run.message)
        assertFailsWith<LeverRefused>("the listing refuses rather than answering empty") { contract.list() }
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
        val person = client.person()
        person.createEvent("Faulty", "2026-05-25T00:00:00", "2026-06-20T00:00:00")
        client.awaitState { (it.ui.layer as? Layer.CreateEvent)?.error != null }
        person.createEvent("Faulty", "2026-05-25T00:00:00", "2026-06-20T00:00:00")
        client.awaitState { (it.ui.layer as? Layer.JoiningEvent)?.range != null }
    }

    /**
     * The device's mobile-data choice (capability `mobile-data`; decision record `changes/archive/2026-10-07-mobile-data-per-device`) is
     * flipped from the menu over the channel — before any event, and it carries into the one joined after.
     */
    @Test
    fun the_mobile_data_choice_is_the_devices_and_driven_from_the_menu() = onHost { client ->
        client.person().tap(UiIntent.MobileData(false))
        client.awaitState { !it.ui.mobileData.on }

        client.person().createEvent("Data", "2026-05-25T00:00:00", "2026-06-20T00:00:00")
        client.awaitState { (it.ui.layer as? Layer.JoiningEvent)?.range != null }
        client.person().confirmJoin()
        val joined = client.awaitState { it.ready.configResolved }
        assertEquals(false, joined.ui.mobileData.on, "the joined event follows the device's choice")

        client.person().tap(UiIntent.MobileData(true))
        client.awaitState { it.ui.mobileData.on }
    }

    /**
     * The whole path a person takes, over the protocol: create an event, confirm the join the create opens, add a
     * photo, run the extension's cycle, let the "OS" finish the transfer, and see the object on the backend.
     */
    private fun roundTrip(backend: String) = onHost(backend) { client ->
        client.person().createEvent("Rig Trip", "2026-05-25T00:00:00", "2026-06-20T00:00:00")
        client.awaitState { (it.ui.layer as? Layer.JoiningEvent)?.range != null }
        client.person().confirmJoin()
        val joined = client.awaitState { it.ready.configResolved }
        assertIs<Layer.Joined>(joined.ui.layer)

        // The extension's cycle alone: the app's uploader, running beside it as on a phone, would take the same photo.
        client.levers(DevControlsLevers).pin(app = false, extension = true)
        client.levers(GalleryLevers).seed(1, SeedKind.POLICY)
        val extension = client.levers(ExtensionHostLevers)
        assertEquals(ExtensionAnswer.PROCESSING, extension.process(), "the cycle waits on the upload it started")
        assertEquals(1, client.levers(UploadQueueLevers).queue().created.size, "the cycle created the photo's job")

        client.levers(UploadQueueLevers).completeJob()
        extension.process()
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
        client.person().createEvent("Relaunch", "2026-05-25T00:00:00", "2026-06-20T00:00:00")
        client.awaitState { (it.ui.layer as? Layer.JoiningEvent)?.range != null }
        client.person().confirmJoin()
        val event = client.awaitState { it.ready.configResolved }.ready.eventId
        // The extension's cycle alone: the app's uploader, running beside it as on a phone, would take the same photo.
        client.levers(DevControlsLevers).pin(app = false, extension = true)
        client.levers(GalleryLevers).seed(1, SeedKind.POLICY)
        client.levers(ExtensionHostLevers).process()

        client.levers(HostLevers).relaunch()

        assertEquals(event, client.awaitState { it.ready.configResolved }.ready.eventId, "the membership survives")
        assertEquals(
            1L,
            client.levers(GalleryLevers).read(cutoff = "1970-01-01T00:00:00Z").census.total,
            "the library survives",
        )
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
