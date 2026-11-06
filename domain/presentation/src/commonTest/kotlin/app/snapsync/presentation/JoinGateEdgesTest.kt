@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.domain.album.AlbumContents
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.event.MakesEventKey
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.domain.sharing.SharingProgressRead
import app.snapsync.query.ScreenLookup
import app.snapsync.query.membership.JoinDetails
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.step
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val EVENT_ID = "22222222-2222-4222-8222-222222222222"
private const val OTHER_EVENT_ID = "33333333-3333-4333-8333-333333333333"

/** The test's clock: 2026-07-09 12:00 UTC, in UTC. */
private val CLOCK = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC)
private val NOW = LocalDateTime(2026, 7, 9, 12, 0)

/** An encrypted event's key, as an invite link carries it. */
@OptIn(MakesEventKey::class)
private val LINK_KEY = EventKey.of(ByteArray(EventKey.LENGTH) { it.toByte() })!!

private val FOUND = found(
    "Lake Weekend",
    EventStart(timestamp("2026-07-06T00:00:00Z")),
    EventEnd(timestamp("2026-07-13T00:00:00Z")),
    DeletesAt(timestamp("2026-08-05T00:00:00Z")),
) as JoinDetails.Found

/** A membership of a DIFFERENT event — what a switch leaves. */
private val OTHER_MEMBERSHIP = TestMembership(
    eventId = testEventId(OTHER_EVENT_ID),
    name = "Anna's Birthday",
    minPhotoDate = CaptureCutoff(timestamp("2026-07-01T00:00:00Z")),
    endsAt = EventEnd(timestamp("2099-12-31T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(timestamp("2026-07-31T00:00:00Z")),
    deletesAt = DeletesAt(timestamp("2099-12-31T00:00:00Z")),
)

/**
 * The join gate's less-travelled roads (capability `join-event`): an older invite link's hints, an invite whose key
 * cannot be read, a Retry or a switch confirm with nothing to act on, a details load torn down or overtaken, and a commit refused for
 * the link. `StatusContainerHostTest` owns the gate's main roads.
 *
 * Driven on a real container over the test's own scheduler, and observed at what the gate writes — the pending join
 * cell, the commands it fires and the log lines a headless run reads.
 */
class JoinGateEdgesTest {

    private class World(config: TestMembership? = null) {
        val config = MutableStateFlow(config)
        val pending = MutableStateFlow<PendingJoin?>(null)
        val acts = mutableListOf<String>()
        val commits = mutableListOf<JoinChoice>()
        val log = TestLog()
        var load: suspend (String) -> JoinDetails = { FOUND }
        var commit: suspend () -> JoinCommit = { JoinCommit.Committed }
        var loads = 0
        var losesNothing = false
        var asked = 0
        var whileAsked: () -> Unit = {}

        /** The invite the app was opened with, waiting for the screen; and each one the screen told opened. */
        val invites = MutableStateFlow<InviteLinkVerdict?>(null)

        val opened = mutableListOf<InviteLinkVerdict>()

        /** The commands the gate fires, as this world answers them. */
        val commands = TestCommands(
            leave = {
                acts += "leave"
                this.config.value = null
            },
            commitJoin = { choice ->
                acts += "commit"
                commits += choice
                commit()
            },
            switch = {
                asked++
                whileAsked()
                if (losesNothing) SwitchVerdict.Quiet else SwitchVerdict.Ask
            },
        ).apply {
            handedOver = {
                if (it is InviteLinkVerdict) opened += it
            }
        }
    }

    private fun gate(
        world: World,
        scope: CoroutineScope,
        details: ScreenLookup<InviteLink, JoinDetails> = joinDetails { id ->
            world.loads++
            world.load(id)
        },
    ): JoinGate {
        val commands = world.commands
        val ranges = RangeResolver(CLOCK, AlbumContents.SharedAndReceived)
        val local = MutableStateFlow(
            Local(form = ranges.freshForm, create = CreateDraft.fresh(CreateDraftSession(), NOW)),
        )
        return JoinGate(
            ScreenCells(local, world.pending, RequestIds(), GateRunner(scope), scope),
            GateReads(
                world.config.held(),
                MutableStateFlow(PhotoGrant.FULL),
                MutableStateFlow(KeyPresence.NotNeeded),
                MutableStateFlow(null),
                MutableStateFlow(null),
            ),
            ranges,
            commands.door,
            commands.requests.flows,
            details,
            scope,
        )
    }

    /** The whole screen over [world], for the one road the gate is reached by from the container: a waiting invite. */
    private fun host(world: World, scope: CoroutineScope): StatusContainerHost = testHost(
        testQueries(
            FixedSync(SharingProgressRead.NotYetRead),
            MutableStateFlow(PhotoGrant.FULL),
            world.config.held(),
            requests = world.commands.requests.flows,
            invites = world.invites,
        ),
        scope,
        Dispatchers.Default,
        CLOCK,
        commands = world.commands.door,
        joinDetails = joinDetails { id -> world.load(id) },
    )

    /** The gate's work run as the container runs it: each act launched on its own, off the test's thread. */
    private class GateRunner(private val scope: CoroutineScope) : IntentRunner {
        override fun intent(block: suspend () -> Unit): Job = scope.launch(Dispatchers.Default) { block() }

        override fun guarded(command: Guarded, block: suspend () -> Unit) {
            intent(block)
        }

        override fun current(): ScreenModel = error("the gate reads no screen")
    }

    private suspend fun TestScope.settle(job: Job) {
        job.join()
        runCurrent()
    }

    // ---- an invite the app was opened with ------------------------------------------------------

    @Test
    fun `an invite waiting for the screen opens the gate, and is told opened`() = runTest {
        val world = World()
        val invite = invited(testEventId(EVENT_ID), null)
        world.invites.value = invite
        val host = host(world, backgroundScope)
        val opening = withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { host.screen.first { it.layer is Layer.JoiningEvent } }
        }
        assertEquals(EVENT_ID, (opening.layer as Layer.JoiningEvent).eventId, "the gate opened on it")
        assertEquals(listOf(invite), world.opened, "and the core was told it no longer waits")
    }

    // ---- an invite link's dev hints ---------------------------------------------------------------

    // ---- acts with nothing to act on -------------------------------------------------------------

    @Test
    fun `a retry with no join open loads nothing`() = runTest {
        val world = World()
        settle(gate(world, backgroundScope).onRetryLoad())

        assertEquals(0, world.loads)
        assertNull(world.pending.value)
    }

    @Test
    fun `a retry re-sends the link's key with the load`() = runTest {
        val world = World().apply { load = { JoinDetails.Unanswered(ServiceFault.UNREACHABLE) } }
        val keys = mutableListOf<EventKey?>()
        val host = gate(
            world,
            backgroundScope,
            lookup { invite: InviteLink ->
                keys += invite.key
                JoinDetails.Unanswered(ServiceFault.UNREACHABLE)
            },
        )

        settle(host.onInvite(invited(testEventId(EVENT_ID), LINK_KEY)))
        assertEquals(JoinProgress.LoadFailed, world.pending.value?.phase)
        settle(host.onRetryLoad())

        assertEquals(listOf<EventKey?>(LINK_KEY, LINK_KEY), keys)
    }

    // ---- the switch without a question (capability `invite-link`; `changes/back-dated-events`, D8) ----------------

    @Test
    fun `another invite leaves without asking when the joined event loses nothing by it`() = runTest {
        val world = World(OTHER_MEMBERSHIP).apply { losesNothing = true }
        val host = gate(world, backgroundScope)
        settle(host.onInvite(invited(testEventId(EVENT_ID), null)))

        assertEquals(listOf("leave"), world.acts, "left without the switch question, and nothing committed yet")
        assertNull(world.config.value)
        // The join that follows is a first join's surface: the new event's details, ready to be confirmed.
        assertEquals(JoinPhase.Detailed.Step.Ready, world.pending.value?.phase?.shows?.step)
        assertEquals(testEventId(EVENT_ID), world.pending.value?.eventId)

        // Its Cancel leaves the device in no event, as after a confirmed switch.
        settle(host.onCancelJoin())
        assertNull(world.pending.value)
        assertNull(world.config.value)
    }

    @Test
    fun `another invite asks as before when the joined event could lose something`() = runTest {
        val world = World(OTHER_MEMBERSHIP) // a member still sharing, offline, a failed read: all answer `false`
        val host = gate(world, backgroundScope)
        settle(host.onInvite(invited(testEventId(EVENT_ID), null)))

        assertEquals(1, world.asked)
        assertTrue(world.acts.isEmpty(), "nothing was left before the member answered")
        assertEquals(OTHER_MEMBERSHIP, world.config.value)
        assertEquals(JoinPhase.Detailed.Step.Ready, world.pending.value?.phase?.shows?.step)
    }

    @Test
    fun `the join screen carries how many members the event has`() = runTest {
        val world = World().apply { load = { FOUND.copy(event = FOUND.event.copy(members = MemberCounts(3, 1))) } }
        settle(
            gate(
                world,
                backgroundScope,
            ).onInvite(invited(testEventId(EVENT_ID), null)),
        )
        assertEquals(3, assertIs<JoinPhase.Detailed>(world.pending.value?.phase?.shows).members)
    }

    @Test
    fun `an invite backed out of while the switch was being weighed leaves nothing`() = runTest {
        val world = World(OTHER_MEMBERSHIP).apply {
            losesNothing = true
            whileAsked = { pending.value = null } // the member backed out while the event was read
        }
        settle(
            gate(
                world,
                backgroundScope,
            ).onInvite(invited(testEventId(EVENT_ID), null)),
        )

        assertTrue(world.acts.isEmpty(), "a switch the member no longer wants left their event")
        assertEquals(OTHER_MEMBERSHIP, world.config.value)
    }

    @Test
    fun `a first join never asks whether a switch loses nothing`() = runTest {
        val world = World()
        settle(
            gate(
                world,
                backgroundScope,
            ).onInvite(invited(testEventId(EVENT_ID), null)),
        )
        assertEquals(0, world.asked)
    }

    @Test
    fun `a switch confirm with no switch open leaves nothing`() = runTest {
        val world = World(OTHER_MEMBERSHIP)
        val host = gate(world, backgroundScope)
        host.onConfirmSwitch()
        graceForAnIntentThatShouldDoNothing()

        assertTrue(world.acts.isEmpty())
        assertEquals(OTHER_MEMBERSHIP, world.config.value)
    }

    @Test
    fun `a switch confirm with nothing joined leaves nothing`() = runTest {
        val world = World()
        val host = gate(world, backgroundScope)
        settle(host.onInvite(invited(testEventId(EVENT_ID), null)))
        host.onConfirmSwitch()
        graceForAnIntentThatShouldDoNothing()

        assertTrue(world.acts.isEmpty())
    }

    @Test
    fun `an invite backed out of while it loaded asks nothing of the joined event`() = runTest {
        val world = World(OTHER_MEMBERSHIP)
        world.load = {
            world.pending.value = null // the member backed out while the invite loaded
            FOUND
        }
        settle(
            gate(
                world,
                backgroundScope,
            ).onInvite(invited(testEventId(EVENT_ID), null)),
        )

        assertEquals(0, world.asked)
        assertTrue(world.acts.isEmpty())
    }

    @Test
    fun `a confirm with no event loaded commits nothing`() = runTest {
        val world = World()
        world.pending.value = PendingJoin(
            testEventId(EVENT_ID),
            JoinProgress.Detailed(
                EventDetails("Lake Weekend", FOUND.event.range.start, FOUND.event.range.end, FOUND.event.deletesAt),
                JoinPhase.Detailed.Step.Ready,
            ),
        )
        val host = gate(world, backgroundScope)
        host.onConfirmJoin()
        graceForAnIntentThatShouldDoNothing()

        assertTrue(world.commits.isEmpty())
    }

    @Test
    fun `a commit carries the invite's key`() = runTest {
        val world = World()
        val host = gate(world, backgroundScope)
        settle(host.onInvite(invited(testEventId(EVENT_ID), LINK_KEY)))
        settle(host.onConfirmJoin())

        assertEquals(LINK_KEY, world.commits.single().invite.key)
    }

    @Test
    fun `a switch confirm before the event's details loaded leaves nothing`() = runTest {
        val world = World(OTHER_MEMBERSHIP)
        world.pending.value = PendingJoin(testEventId(EVENT_ID), JoinProgress.Loading)
        val host = gate(world, backgroundScope)
        host.onConfirmSwitch()
        graceForAnIntentThatShouldDoNothing()

        assertTrue(world.acts.isEmpty())
        assertEquals(OTHER_MEMBERSHIP, world.config.value)
    }

    @Test
    fun `a back-dated event's open-until date rides from the loaded details into the commit`() = runTest {
        // Without it the joined screen would say until when others can join only after the next refresh.
        val openUntil = OpenUntil(timestamp("2026-07-16T12:00:00Z"))
        val world = World().apply { load = { FOUND.copy(event = FOUND.event.copy(openUntil = openUntil)) } }
        val host = gate(world, backgroundScope)
        settle(host.onInvite(invited(testEventId(EVENT_ID), null)))

        settle(host.onConfirmJoin())

        assertEquals(openUntil, world.commits.single().event.openUntil)
    }

    // ---- a details load that does not finish normally --------------------------------------------

    @Test
    fun `a details load torn down mid-fetch is not read as a failure`() = runTest {
        val loading = CompletableDeferred<Unit>()
        val world = World().apply {
            load = {
                loading.complete(Unit)
                awaitCancellation()
            }
        }
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val opening = gate(
            world,
            scope,
        ).onInvite(invited(testEventId(EVENT_ID), null))
        loading.await()
        assertEquals(JoinProgress.Loading, world.pending.value?.phase)

        scope.cancel()
        opening.join()

        assertEquals(JoinProgress.Loading, world.pending.value?.phase, "teardown rewrote the gate to a failure")
        assertTrue(world.log.errors.isEmpty(), "teardown was reported as an error: ${world.log.errors}")
    }

    @Test
    fun `a details load that answers after its join was cancelled leaves nothing behind`() = runTest {
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val world = World().apply {
            load = {
                loading.complete(Unit)
                release.await()
                FOUND
            }
        }
        val host = gate(world, backgroundScope)
        val opening = host.onInvite(
            invited(testEventId(EVENT_ID), null),
        )
        loading.await()
        settle(host.onCancelJoin())

        release.complete(Unit)
        settle(opening)

        assertNull(world.pending.value, "a cancelled join came back")
    }

    @Test
    fun `a details load overtaken by another link does not overwrite it`() = runTest {
        val firstLoading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val world = World().apply {
            load = { id ->
                if (id == EVENT_ID) {
                    firstLoading.complete(Unit)
                    release.await()
                    JoinDetails.NotFound
                } else {
                    FOUND
                }
            }
        }
        val host = gate(world, backgroundScope)
        val first = host.onInvite(invited(testEventId(EVENT_ID), null))
        firstLoading.await()
        settle(
            host.onInvite(invited(testEventId(OTHER_EVENT_ID), null)),
        )

        release.complete(Unit)
        settle(first)

        assertEquals(testEventId(OTHER_EVENT_ID), world.pending.value?.eventId)
        assertEquals(
            JoinPhase.Detailed.Step.Ready,
            (world.pending.value?.phase as? JoinProgress.Detailed)?.step,
            "the newer link's details stand",
        )
    }

    @Test
    fun `a link whose key cannot be read reads its event first, and its event there opens the wrong-link phase`() =
        runTest {
            val world = World()
            settle(gate(world, backgroundScope).onInvite(InviteLinkVerdict.KeyUnreadable(testEventId(EVENT_ID))))

            assertEquals(JoinProgress.WrongLink, world.pending.value?.phase)
            assertEquals(1, world.loads)
        }

    @Test
    fun `a link whose key cannot be read tells a missing, closed or unanswered event as itself`() = runTest {
        val answers = mapOf(
            JoinDetails.NotFound to JoinProgress.NotFound,
            JoinDetails.Found(FOUND.event, completed = true) to JoinProgress.Closed,
            JoinDetails.Unanswered(ServiceFault.UNREACHABLE) to JoinProgress.LoadFailed,
        )
        for ((answer, phase) in answers) {
            val world = World().apply { load = { answer } }
            settle(gate(world, backgroundScope).onInvite(InviteLinkVerdict.KeyUnreadable(testEventId(EVENT_ID))))
            assertEquals(phase, world.pending.value?.phase, "$answer")
        }
    }

    @Test
    fun `a retry of a link whose key cannot be read still cannot open its event`() = runTest {
        var answer: JoinDetails = JoinDetails.Unanswered(ServiceFault.UNREACHABLE)
        val world = World().apply { load = { answer } }
        val gate = gate(world, backgroundScope)
        settle(gate.onInvite(InviteLinkVerdict.KeyUnreadable(testEventId(EVENT_ID))))
        assertEquals(JoinProgress.LoadFailed, world.pending.value?.phase)

        answer = FOUND
        settle(gate.onRetryLoad())
        assertEquals(JoinProgress.WrongLink, world.pending.value?.phase, "found again, the invite still opens nothing")
    }

    @Test
    fun `a link whose key cannot be read changes nothing while its event's join is open or joined`() = runTest {
        val cut = InviteLinkVerdict.KeyUnreadable(testEventId(EVENT_ID))
        val pending = World()
        val host = gate(pending, backgroundScope)
        settle(host.onInvite(invited(testEventId(EVENT_ID), null)))
        val opened = pending.pending.value
        settle(host.onInvite(cut))
        assertEquals(opened, pending.pending.value, "the open join stays as it is")

        val joined = World(OTHER_MEMBERSHIP.copy(eventId = testEventId(EVENT_ID)))
        settle(gate(joined, backgroundScope).onInvite(cut))
        assertNull(joined.pending.value, "the joined event's own invite opens nothing")
    }

    @Test
    fun `a link refused at load for its key lands on the wrong-link phase`() = runTest {
        val world = World().apply { load = { JoinDetails.WrongLink } }
        settle(
            gate(
                world,
                backgroundScope,
            ).onInvite(invited(testEventId(EVENT_ID), null)),
        )

        assertEquals(JoinProgress.WrongLink, world.pending.value?.phase)
    }

    // ---- a commit refused for the link, or overtaken ------------------------------------------

    @Test
    fun `a commit refused for the link's key lands on the wrong-link phase with no retry`() = runTest {
        val world = World().apply { commit = { JoinCommit.WrongLink } }
        val host = gate(world, backgroundScope)
        settle(host.onInvite(invited(testEventId(EVENT_ID), null)))

        settle(host.onConfirmJoin())

        assertEquals(JoinProgress.WrongLink, world.pending.value?.phase)
    }

    @Test
    fun `a confirm while the commit is in flight commits nothing more`() = runTest {
        // Committing is no step a confirm is taken from: a second tap is not a second join.
        val committing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val world = World().apply {
            commit = {
                committing.complete(Unit)
                release.await()
                JoinCommit.Committed
            }
        }
        val host = gate(world, backgroundScope)
        settle(host.onInvite(invited(testEventId(EVENT_ID), null)))
        val first = host.onConfirmJoin()
        committing.await()

        settle(host.onConfirmJoin())
        release.complete(Unit)
        settle(first)

        assertEquals(1, world.commits.size)
    }

    @Test
    fun `a confirm with no join open commits nothing`() = runTest {
        val world = World()
        settle(gate(world, backgroundScope).onConfirmJoin())

        assertTrue(world.commits.isEmpty())
    }

    @Test
    fun `a commit's outcome lands only on the join that started it`() = runTest {
        // A newer link may open, or the join be cancelled, while a commit is in flight; whatever the commit answers,
        // what the member did since stands.
        val outcomes: List<suspend () -> JoinCommit> = listOf(
            { JoinCommit.Committed },
            { JoinCommit.EventOver },
            { error("the commit threw") },
        )
        val since: Map<String, suspend (JoinGate) -> Job> = mapOf(
            "overtaken" to {
                it.onInvite(invited(testEventId(OTHER_EVENT_ID), null))
            },
            "cancelled" to { it.onCancelJoin() },
        )
        for (outcome in outcomes) {
            for ((what, act) in since) {
                val committing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val world = World().apply {
                    commit = {
                        committing.complete(Unit)
                        release.await()
                        outcome()
                    }
                }
                val host = gate(world, backgroundScope)
                settle(
                    host.onInvite(invited(testEventId(EVENT_ID), null)),
                )
                val confirming = host.onConfirmJoin()
                committing.await()
                settle(act(host))

                release.complete(Unit)
                settle(confirming)

                if (what == "overtaken") {
                    assertEquals(testEventId(OTHER_EVENT_ID), world.pending.value?.eventId, what)
                    assertEquals(
                        JoinPhase.Detailed.Step.Ready,
                        (world.pending.value?.phase as? JoinProgress.Detailed)?.step,
                        what,
                    )
                } else {
                    assertNull(world.pending.value, what)
                }
            }
        }
    }

    // ---- what the gate resolves and announces ----------------------------------------------------

    @Test
    fun `the confirm announces the access dialog only for a first join on a phone never asked`() {
        // A switch's previous event, while still configured, is not yet a join this rule speaks for.
        for (permission in PhotoGrant.entries) {
            assertEquals(permission == PhotoGrant.UNDECIDED, asksAccessOnJoin(null, permission), "$permission")
            assertFalse(asksAccessOnJoin(OTHER_MEMBERSHIP.held(), permission), "joined, $permission")
        }
    }

    /**
     * A command that should do nothing leaves nothing to await, and the container runs its intents off the test's
     * scheduler — so a negative assertion waits a bounded real-time grace first, as the menu tests do.
     */
    private suspend fun graceForAnIntentThatShouldDoNothing() = withContext(Dispatchers.Default) { delay(200) }
}
