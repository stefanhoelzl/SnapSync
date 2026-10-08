@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.presentation

import app.snapsync.feature.membership.readmodel.RenameStatus
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.GalleryAccess
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.JoinChoice
import app.snapsync.model.JoinCommit
import app.snapsync.model.JoinLoad
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.SyncStatus
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeEventKey
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.step
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
import kotlinx.datetime.LocalDateTime
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
private val LINK_KEY = encodeEventKey(ByteArray(32) { it.toByte() })

private val FOUND = JoinLoad.Found(
    "Lake Weekend",
    eventStart("2026-07-06T00:00:00Z"),
    eventEnd("2026-07-13T00:00:00Z"),
    deletesAt("2026-08-05T00:00:00Z"),
)

/** A membership of a DIFFERENT event — what a switch leaves. */
private val OTHER_MEMBERSHIP = EventConfig(
    eventId = OTHER_EVENT_ID,
    name = "Anna's Birthday",
    minPhotoDate = captureCutoff("2026-07-01T00:00:00Z"),
    endsAt = eventEnd("2099-12-31T00:00:00Z"),
    maxPhotoDate = captureCeiling("2099-12-31T00:00:00Z"),
    deletesAt = deletesAt("2099-12-31T00:00:00Z"),
)

/**
 * The join gate's less-travelled roads (capability `join-event`): an invite link's dev hints, honoured or not, a
 * Retry or a switch confirm with nothing to act on, a details load torn down or overtaken, and a commit refused for
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
        var load: suspend (String) -> JoinLoad = { FOUND }
        var commit: suspend () -> JoinCommit = { JoinCommit.Committed }
        val rename = MutableStateFlow<RenameStatus>(RenameStatus.Idle)
        var loads = 0
    }

    private fun host(
        world: World,
        scope: CoroutineScope,
        hints: InviteLinkHints = InviteLinkHints.Ignored,
    ) = StatusContainerHost(
        StatusSources(
            FixedSync(SyncStatus.Loading),
            MutableStateFlow(GalleryAccess.GRANTED),
            world.config,
            rename = world.rename,
            pending = world.pending,
        ),
        scope,
        cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-09T12:00:00Z") }, zone = TimeZone.UTC),
        commands = testCommands(
            leave = {
                world.acts += "leave"
                world.config.value = null
            },
            commitJoin = { choice ->
                world.acts += "commit"
                world.commits += choice
                world.commit()
            },
            resetRename = {
                world.acts += "resetRename"
                world.rename.value = RenameStatus.Idle
            },
        ),
        queries = testQueries(
            load = { id, _ ->
                world.loads++
                world.load(id)
            },
        ),
        diagnostics = testDiagnostics(log = { world.logged += it }, onIntentError = { world.errors += it }),
        inviteLinkHints = hints,
    )

    private suspend fun TestScope.settle(job: Job) {
        job.join()
        runCurrent()
    }

    // ---- an invite link's dev hints ---------------------------------------------------------------

    @Test
    fun `an honoured autoJoin link while joined elsewhere leaves first and joins with the link's own choices`() = runTest {
        val world = World(OTHER_MEMBERSHIP)
        val host = host(world, backgroundScope, InviteLinkHints.Honoured)
        val link = EventLinkPayload(
            EVENT_ID,
            autoJoin = true,
            maxPhotoDate = "2026-07-10T00:00:00Z",
            direction = "upload",
            saveToAlbum = true,
        )

        settle(host.onOpenUrl(encodeEventUrl(link)))

        assertEquals(listOf("leave", "commit"), world.acts, "the old membership is left before the new one commits")
        val choice = world.commits.single()
        assertEquals(EVENT_ID, choice.eventId)
        assertEquals(captureCutoff(FOUND.startsAt.at.iso), choice.minPhotoDate, "no explicit cutoff: the event start")
        assertEquals(captureCeiling("2026-07-10T00:00:00Z"), choice.maxPhotoDate)
        assertEquals(Direction.UploadOnly, choice.direction)
        assertTrue(choice.saveToAlbum)
        assertNull(world.pending.value, "a headless join opens no surface")
    }

    @Test
    fun `an honoured autoJoin link for the first event leaves nothing`() = runTest {
        val world = World()
        settle(host(world, backgroundScope, InviteLinkHints.Honoured).onOpenUrl(autoJoinLink()))

        assertEquals(listOf("commit"), world.acts)
    }

    @Test
    fun `an autoJoin that does not commit names the reason in the log`() = runTest {
        val world = World().apply { commit = { JoinCommit.Full } }
        settle(host(world, backgroundScope, InviteLinkHints.Honoured).onOpenUrl(autoJoinLink()))

        assertTrue("autoJoin aborted: join full for $EVENT_ID" in world.logged, "logged: ${world.logged}")
    }

    @Test
    fun `an autoJoin link whose hints are ignored opens the ordinary gate and says so`() = runTest {
        val world = World()
        settle(host(world, backgroundScope).onOpenUrl(autoJoinLink()))

        assertTrue("join gate: ignoring the invite-link hints of $EVENT_ID" in world.logged, "logged: ${world.logged}")
        assertEquals(JoinPhase.Detailed.Step.Ready, world.pending.value?.phase?.step, "the member is asked")
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
        val world = World().apply { load = { JoinLoad.Failed } }
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
            commands = testCommands(),
            queries = testQueries(
                load = { _, key ->
                    keys += key
                    JoinLoad.Failed
                },
            ),
            diagnostics = testDiagnostics(),
        )

        settle(host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID, key = LINK_KEY))))
        assertEquals(JoinPhase.LoadFailed, world.pending.value?.phase)
        settle(host.onRetryLoad())

        assertEquals(listOf<String?>(LINK_KEY, LINK_KEY), keys)
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
    fun `a switch confirm before the event's details loaded leaves nothing`() = runTest {
        val world = World(OTHER_MEMBERSHIP)
        world.pending.value = PendingJoin(EVENT_ID, JoinPhase.Loading)
        val host = host(world, backgroundScope)
        host.onConfirmSwitch()
        graceForAnIntentThatShouldDoNothing()

        assertTrue(world.acts.isEmpty())
        assertEquals(OTHER_MEMBERSHIP, world.config.value)
    }

    @Test
    fun `an autoJoin whose event was joined while its details loaded leaves nothing`() = runTest {
        val world = World()
        world.load = {
            // Another road joined this very event while the headless details were in flight.
            world.config.value = OTHER_MEMBERSHIP.copy(eventId = EVENT_ID)
            FOUND
        }
        settle(host(world, backgroundScope, InviteLinkHints.Honoured).onOpenUrl(autoJoinLink()))

        assertEquals(listOf("commit"), world.acts, "its own membership was left")
    }

    @Test
    fun `a join that begins while a finished rename is latched clears the latch`() = runTest {
        // The rename dialog reads a terminal status as its own outcome; a new membership must not inherit one.
        val world = World().apply { rename.value = RenameStatus.Succeeded }
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID))))

        settle(host.onConfirmJoin())

        assertEquals(listOf("resetRename", "commit"), world.acts)
        assertEquals(RenameStatus.Idle, world.rename.value)
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
        val opening = host(world, scope).onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
        loading.await()
        assertEquals(JoinPhase.Loading, world.pending.value?.phase)

        scope.cancel()
        opening.join()

        assertEquals(JoinPhase.Loading, world.pending.value?.phase, "teardown rewrote the gate to a failure")
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
        val opening = host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
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
        val opening = host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
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
                    JoinLoad.NotFound
                } else {
                    FOUND
                }
            }
        }
        val host = host(world, backgroundScope)
        val first = host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID)))
        firstLoading.await()
        settle(host.onOpenUrl(encodeEventUrl(EventLinkPayload(OTHER_EVENT_ID))))

        release.complete(Unit)
        settle(first)

        assertEquals(OTHER_EVENT_ID, world.pending.value?.eventId)
        assertEquals(JoinPhase.Detailed.Step.Ready, world.pending.value?.phase?.step, "the newer link's details stand")
    }

    @Test
    fun `a link refused at load for its key lands on the wrong-link phase`() = runTest {
        val world = World().apply { load = { JoinLoad.WrongLink } }
        settle(host(world, backgroundScope).onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID))))

        assertEquals(JoinPhase.WrongLink, world.pending.value?.phase)
    }

    // ---- a commit refused for the link, or overtaken ------------------------------------------

    @Test
    fun `a commit refused for the link's key lands on the wrong-link phase with no retry`() = runTest {
        val world = World().apply { commit = { JoinCommit.WrongLink } }
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID))))

        settle(host.onConfirmJoin())

        assertEquals(JoinPhase.WrongLink, world.pending.value?.phase)
    }

    @Test
    fun `a confirm with no join open commits nothing`() = runTest {
        val world = World()
        settle(host(world, backgroundScope).onConfirmJoin())

        assertTrue(world.commits.isEmpty())
    }

    @Test
    fun `a confirm on a full event commits nothing`() = runTest {
        // A full event offers no confirm; one that arrives anyway is not a second try.
        val world = World().apply { commit = { JoinCommit.Full } }
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID))))
        settle(host.onConfirmJoin())
        assertEquals(JoinPhase.Detailed.Step.EventFull, world.pending.value?.phase?.step)

        settle(host.onConfirmJoin())

        assertEquals(1, world.commits.size)
    }

    @Test
    fun `a commit's outcome lands only on the join that started it`() = runTest {
        // A newer link may open, or the join be cancelled, while a commit is in flight; whatever the commit answers,
        // what the member did since stands.
        val outcomes: List<suspend () -> JoinCommit> = listOf(
            { JoinCommit.Committed },
            { JoinCommit.Full },
            { error("the commit threw") },
        )
        val since: Map<String, suspend (StatusContainerHost) -> Job> = mapOf(
            "overtaken" to { it.onOpenUrl(encodeEventUrl(EventLinkPayload(OTHER_EVENT_ID))) },
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
                settle(host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID))))
                val confirming = host.onConfirmJoin()
                committing.await()
                settle(act(host))

                release.complete(Unit)
                settle(confirming)

                if (what == "overtaken") {
                    assertEquals(OTHER_EVENT_ID, world.pending.value?.eventId, what)
                    assertEquals(JoinPhase.Detailed.Step.Ready, world.pending.value?.phase?.step, what)
                } else {
                    assertNull(world.pending.value, what)
                }
            }
        }
    }

    // ---- what the gate resolves and announces ----------------------------------------------------

    @Test
    fun `event dates that do not parse resolve to the widest safe window rather than failing`() = runTest {
        // The window starts now (2026-07-09T12:00Z) and ends at the far-future sentinel; the bounds only narrow from here.
        val world = World().apply {
            load = { FOUND.copy(startsAt = eventStart("not a date"), endsAt = eventEnd("not a date either")) }
        }
        val host = host(world, backgroundScope)
        settle(host.onOpenUrl(encodeEventUrl(EventLinkPayload(EVENT_ID))))

        val layer = host.container.stateFlow.first { (it.layer as? Layer.JoiningEvent)?.range != null }.layer
        val range = assertIs<Layer.JoiningEvent>(layer).range!!
        assertEquals(LocalDateTime(2026, 7, 9, 12, 0), range.windowStart)
        assertEquals(LocalDateTime(2026 + NO_CEILING_YEARS, 1, 1, 0, 0), range.windowEnd)
        assertTrue(world.errors.isEmpty(), "${world.errors}")
    }

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

    private fun autoJoinLink() = encodeEventUrl(EventLinkPayload(EVENT_ID, autoJoin = true))
}
