@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.MemberCounts
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.invite.InviteKey
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.membership.JoinChoice
import app.snapsync.domain.membership.JoinCommit
import app.snapsync.domain.membership.SwitchVerdict
import app.snapsync.domain.request.RequestId
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.format.EventKeyText
import app.snapsync.format.InviteLinkText
import app.snapsync.format.LINK_ORIGIN
import app.snapsync.format.TimestampText
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserCommands
import app.snapsync.query.membership.JoinDetails
import app.snapsync.query.membership.JoinDetailsQuery
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.JoinPhase
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val EVENT_ID = "22222222-2222-4222-8222-222222222222"
private const val OTHER_EVENT_ID = "33333333-3333-4333-8333-333333333333"

/** An encrypted event's key as an invite link carries it: 32 bytes, base64url. */
private val LINK_KEY = EventKeyText.encode(ByteArray(32) { it.toByte() })

private val FOUND = found(
    "Lake Weekend",
    EventStart(TimestampText.parse("2026-07-06T00:00:00Z")),
    EventEnd(TimestampText.parse("2026-07-13T00:00:00Z")),
    DeletesAt(TimestampText.parse("2026-08-05T00:00:00Z")),
) as JoinDetails.Found

/** A membership of a DIFFERENT event — what a switch leaves. */
private val OTHER_MEMBERSHIP = EventConfig(
    eventId = testEventId(OTHER_EVENT_ID),
    name = "Anna's Birthday",
    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-01T00:00:00Z")),
    endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(TimestampText.parse("2099-12-31T00:00:00Z")),
    deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
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

    private class World(config: EventConfig? = null) {
        val config = MutableStateFlow(config)
        val pending = MutableStateFlow<PendingJoin?>(null)
        val acts = mutableListOf<String>()
        val commits = mutableListOf<JoinChoice>()
        val logged = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        var load: suspend (String) -> JoinDetails = { FOUND }
        var commit: suspend () -> JoinCommit = { JoinCommit.Committed }
        var loads = 0
        var losesNothing = false
        var asked = 0
        var whileAsked: () -> Unit = {}

        /** Whether the door itself refuses a join as it is handed over — a throw the gate must survive. */
        var doorFails = false
    }

    private fun host(world: World, scope: CoroutineScope): StatusContainerHost {
        val commands = TestCommands(
            leave = {
                world.acts += "leave"
                world.config.value = null
            },
            commitJoin = { choice ->
                world.acts += "commit"
                world.commits += choice
                world.commit()
            },
            switch = {
                world.asked++
                world.whileAsked()
                if (world.losesNothing) SwitchVerdict.Quiet else SwitchVerdict.Ask
            },
        )
        val door = object : UserCommands by commands {
            override fun commitJoin(choice: JoinChoice): RequestId = when (world.doorFails) {
                true -> error("the door refused the join")
                false -> commands.commitJoin(choice)
            }
        }
        return StatusContainerHost(
            StatusSources(
                FixedSync(SyncStatus.Loading),
                MutableStateFlow(GalleryAccess.GRANTED),
                world.config,
                requests = commands.requests.flows,
                pending = world.pending,
            ),
            scope,
            cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
            commands = door,
            queries = noQueries,
            joinDetails = joinDetails { id ->
                world.loads++
                world.load(id)
            },
            diagnostics = testDiagnostics(log = { world.logged += it }, onIntentError = { world.errors += it }),
            reduction = Dispatchers.Default,
        )
    }

    private suspend fun TestScope.settle(job: Job) {
        job.join()
        runCurrent()
    }

    // ---- an invite link's dev hints ---------------------------------------------------------------

    @Test
    fun `an older link carrying hints opens the ordinary gate`() = runTest {
        val world = World()
        settle(host(world, backgroundScope).onOpenUrl(hintedLink()))

        assertEquals(
            JoinPhase.Detailed.Step.Ready,
            (world.pending.value?.phase as? JoinProgress.Detailed)?.step,
            "the member is asked",
        )
        assertTrue(world.commits.isEmpty(), "nothing joined without a tap")
    }

    // ---- acts with nothing to act on -------------------------------------------------------------

    @Test
    fun `a retry with no join open loads nothing`() = runTest {
        val world = World()
        settle(host(world, backgroundScope).onRetryLoad())

        assertEquals(0, world.loads)
        assertNull(world.pending.value)
    }

    @Test
    fun `a retry re-sends the link's key with the load`() = runTest {
        val world = World().apply { load = { JoinDetails.Unanswered(ServiceFault.UNREACHABLE) } }
        val keys = mutableListOf<String?>()
        val host = StatusContainerHost(
            StatusSources(
                FixedSync(SyncStatus.Loading),
                MutableStateFlow(GalleryAccess.GRANTED),
                world.config,
                pending = world.pending,
            ),
            backgroundScope,
            cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
            commands = TestCommands(),
            queries = noQueries,
            joinDetails = object : JoinDetailsQuery {
                override suspend fun load(invite: InviteLink): JoinDetails {
                    keys += invite.key?.text
                    return JoinDetails.Unanswered(ServiceFault.UNREACHABLE)
                }
            },
            diagnostics = testDiagnostics(),
            reduction = Dispatchers.Default,
        )

        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), InviteKey(LINK_KEY)))))
        assertEquals(JoinProgress.LoadFailed, world.pending.value?.phase)
        settle(host.onRetryLoad())

        assertEquals(listOf<String?>(LINK_KEY, LINK_KEY), keys)
    }

    // ---- the switch without a question (capability `invite-link`; `changes/back-dated-events`, D8) ----------------

    @Test
    fun `another invite leaves without asking when the joined event loses nothing by it`() = runTest {
        val world = World(OTHER_MEMBERSHIP).apply { losesNothing = true }
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))

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
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))

        assertEquals(1, world.asked)
        assertTrue(world.acts.isEmpty(), "nothing was left before the member answered")
        assertEquals(OTHER_MEMBERSHIP, world.config.value)
        assertEquals(JoinPhase.Detailed.Step.Ready, world.pending.value?.phase?.shows?.step)
    }

    @Test
    fun `the join screen carries how many members the event has`() = runTest {
        val world = World().apply { load = { FOUND.copy(event = FOUND.event.copy(members = MemberCounts(3, 1))) } }
        settle(host(world, backgroundScope).onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))
        assertEquals(3, assertIs<JoinPhase.Detailed>(world.pending.value?.phase?.shows).members)
    }

    @Test
    fun `an invite backed out of while the switch was being weighed leaves nothing`() = runTest {
        val world = World(OTHER_MEMBERSHIP).apply {
            losesNothing = true
            whileAsked = { pending.value = null } // the member backed out while the event was read
        }
        settle(host(world, backgroundScope).onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))

        assertTrue(world.acts.isEmpty(), "a switch the member no longer wants left their event")
        assertEquals(OTHER_MEMBERSHIP, world.config.value)
    }

    @Test
    fun `a first join never asks whether a switch loses nothing`() = runTest {
        val world = World()
        settle(host(world, backgroundScope).onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))
        assertEquals(0, world.asked)
    }

    @Test
    fun `a switch confirm with no switch open leaves nothing`() = runTest {
        val world = World(OTHER_MEMBERSHIP)
        val host = host(world, backgroundScope)
        host.onConfirmSwitch()
        graceForAnIntentThatShouldDoNothing()

        assertTrue(world.acts.isEmpty())
        assertEquals(OTHER_MEMBERSHIP, world.config.value)
    }

    @Test
    fun `a switch confirm with nothing joined leaves nothing`() = runTest {
        val world = World()
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))
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
        settle(host(world, backgroundScope).onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))

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
        val host = host(world, backgroundScope)
        host.onConfirmJoin()
        graceForAnIntentThatShouldDoNothing()

        assertTrue(world.commits.isEmpty())
    }

    @Test
    fun `a commit carries the invite's key`() = runTest {
        val world = World()
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), InviteKey(LINK_KEY)))))
        settle(host.onConfirmJoin())

        assertEquals(LINK_KEY, world.commits.single().invite.key?.text)
    }

    @Test
    fun `a join the door refuses is reported and stays retryable`() = runTest {
        val world = World().apply { doorFails = true }
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))
        settle(host.onConfirmJoin())

        assertEquals(JoinPhase.Detailed.Step.CommitFailed, (world.pending.value?.phase as? JoinProgress.Detailed)?.step)
        assertIs<IllegalStateException>(world.errors.single())
    }

    @Test
    fun `a switch confirm before the event's details loaded leaves nothing`() = runTest {
        val world = World(OTHER_MEMBERSHIP)
        world.pending.value = PendingJoin(testEventId(EVENT_ID), JoinProgress.Loading)
        val host = host(world, backgroundScope)
        host.onConfirmSwitch()
        graceForAnIntentThatShouldDoNothing()

        assertTrue(world.acts.isEmpty())
        assertEquals(OTHER_MEMBERSHIP, world.config.value)
    }

    @Test
    fun `a back-dated event's open-until date rides from the loaded details into the commit`() = runTest {
        // Without it the joined screen would say until when others can join only after the next refresh.
        val openUntil = OpenUntil(TimestampText.parse("2026-07-16T12:00:00Z"))
        val world = World().apply { load = { FOUND.copy(event = FOUND.event.copy(openUntil = openUntil)) } }
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))

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
        val opening = host(world, scope).onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null)))
        loading.await()
        assertEquals(JoinProgress.Loading, world.pending.value?.phase)

        scope.cancel()
        opening.join()

        assertEquals(JoinProgress.Loading, world.pending.value?.phase, "teardown rewrote the gate to a failure")
        assertTrue(world.errors.isEmpty(), "teardown was reported as an error: ${world.errors}")
    }

    @Test
    fun `a details load that throws after its join was cancelled leaves nothing behind`() = runTest {
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val world = World().apply {
            load = {
                loading.complete(Unit)
                release.await()
                error("the details client threw")
            }
        }
        val host = host(world, backgroundScope)
        val opening = host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null)))
        loading.await()
        settle(host.onCancelJoin())
        assertNull(world.pending.value)

        release.complete(Unit)
        settle(opening)

        assertNull(world.pending.value, "a cancelled join came back as a failed one")
        assertIs<IllegalStateException>(world.errors.single(), "the throw is still reported")
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
        val host = host(world, backgroundScope)
        val opening = host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null)))
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
        val host = host(world, backgroundScope)
        val first = host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null)))
        firstLoading.await()
        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(OTHER_EVENT_ID), null))))

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
    fun `a link whose key cannot be read lands on the wrong-link phase and loads nothing`() = runTest {
        val world = World()
        settle(host(world, backgroundScope).onOpenUrl("$LINK_ORIGIN/join/$EVENT_ID#k=cut-short"))

        assertEquals(JoinProgress.WrongLink, world.pending.value?.phase)
        assertEquals(0, world.loads, "an invite that cannot be read whole asks the server nothing")
    }

    @Test
    fun `a link whose key cannot be read changes nothing while its event's join is open or joined`() = runTest {
        val cut = "$LINK_ORIGIN/join/$EVENT_ID#k=cut-short"
        val pending = World()
        val host = host(pending, backgroundScope)
        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))
        val opened = pending.pending.value
        settle(host.onOpenUrl(cut))
        assertEquals(opened, pending.pending.value, "the open join stays as it is")

        val joined = World(OTHER_MEMBERSHIP.copy(eventId = testEventId(EVENT_ID)))
        settle(host(joined, backgroundScope).onOpenUrl(cut))
        assertNull(joined.pending.value, "the joined event's own invite opens nothing")
    }

    @Test
    fun `a link refused at load for its key lands on the wrong-link phase`() = runTest {
        val world = World().apply { load = { JoinDetails.WrongLink } }
        settle(host(world, backgroundScope).onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))

        assertEquals(JoinProgress.WrongLink, world.pending.value?.phase)
    }

    // ---- a commit refused for the link, or overtaken ------------------------------------------

    @Test
    fun `a commit refused for the link's key lands on the wrong-link phase with no retry`() = runTest {
        val world = World().apply { commit = { JoinCommit.WrongLink } }
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))

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
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))
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
        settle(host(world, backgroundScope).onConfirmJoin())

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
        val since: Map<String, suspend (StatusContainerHost) -> Job> = mapOf(
            "overtaken" to { it.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(OTHER_EVENT_ID), null))) },
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
                val host = host(world, backgroundScope)
                settle(host.onOpenUrl(InviteLinkText.format(InviteLink(testEventId(EVENT_ID), null))))
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
        for (permission in GalleryAccess.entries) {
            assertEquals(permission == GalleryAccess.NOT_DETERMINED, asksAccessOnJoin(null, permission), "$permission")
            assertFalse(asksAccessOnJoin(OTHER_MEMBERSHIP, permission), "joined, $permission")
        }
    }

    /**
     * A command that should do nothing leaves nothing to await, and the container runs its intents off the test's
     * scheduler — so a negative assertion waits a bounded real-time grace first, as the menu tests do.
     */
    private suspend fun graceForAnIntentThatShouldDoNothing() = withContext(Dispatchers.Default) { delay(200) }

    /** An invite as older links carried it, with `autoJoin` and a direction in the path form's fragment. */
    private fun hintedLink() = "$LINK_ORIGIN/join/$EVENT_ID#autoJoin=true&direction=upload"
}
