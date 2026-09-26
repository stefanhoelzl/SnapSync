package app.snapsync.feature.membership

import app.snapsync.model.ReconfigureOutcome
import app.snapsync.model.Direction
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.EventConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest


/** Every membership carries a concrete capture-date ceiling (capability `join-event`). */
private val FIXTURE_CEILING = captureCeiling("2099-01-01T00:00:00Z")

class ReconfigureEventTest {

    /** The membership persisted before the reconfigure runs. */
    private class Membership(val config: EventConfig?)

    /** The membership file's writes; [fails] makes every save fail at the port, as a full disk does. */
    private fun ConfigWrites(fails: Boolean = false) = app.snapsync.feature.support.ConfigWrites().also { it.files.failWrites = fails }

    // A joined membership on event E1, started 2026-07-06T12:00:00Z, cutoff already at the floor.
    private fun current(
        eventId: String = "E1",
        direction: Direction = Direction.Both,
        saveToAlbum: Boolean = false,
        minPhotoDate: CaptureCutoff = captureCutoff("2026-07-06T12:00:00Z"),
    ) = EventConfig(
        eventId = eventId,
        name = "Anna's Birthday",
        minPhotoDate = minPhotoDate,
        startsAt = eventStart("2026-07-06T12:00:00Z"),
        maxPhotoDate = FIXTURE_CEILING,
        direction = direction,
        saveToAlbum = saveToAlbum,
    )

    private fun make(
        source: Membership,
        store: app.snapsync.feature.support.ConfigWrites,
        order: MutableList<String> = mutableListOf(),
        gatherAlbum: suspend (EventConfig) -> Unit = { order += "gather" },
        bumpManifestVersion: suspend () -> Unit = {},
    ) = ReconfigureEvent(
        configSource = store.service(source.config),
        refreshStatus = { order += "refresh" },
        armUpload = { order += "arm" },
        ensureAlbum = { order += "album" },
        gatherAlbum = gatherAlbum,
        startDownloads = { id -> order += "reconcile:$id" },
        cancelDownloads = { order += "cancelDownloads" },
        bumpManifestVersion = bumpManifestVersion,
    )

    @Test
    fun `a saved reconfigure advances the manifest version after the config is saved`() = runTest {
        val store = ConfigWrites()
        var savedWhenBumped: EventConfig? = null
        var bumps = 0
        make(Membership(current()), store, bumpManifestVersion = {
            savedWhenBumped = store.saved
            bumps++
        }).reconfigure(
            eventId = "E1",
            direction = Direction.Both,
            chosenCutoff = captureCutoff("2026-07-06T18:00:00Z"),
            chosenUpper = FIXTURE_CEILING,
            saveToAlbum = false,
        )
        assertEquals(1, bumps)
        // Bumped BEFORE the save, a cycle could read the new version and then the old config, and publish
        // the old policy under a version nothing later exceeds.
        assertEquals(captureCutoff("2026-07-06T18:00:00Z"), savedWhenBumped?.minPhotoDate)
    }

    @Test
    fun `a failed save does not advance the manifest version`() = runTest {
        var bumps = 0
        make(Membership(current()), ConfigWrites(fails = true), bumpManifestVersion = { bumps++ })
            .reconfigure(
                eventId = "E1",
                direction = Direction.Both,
                chosenCutoff = captureCutoff("2026-07-06T18:00:00Z"),
                chosenUpper = FIXTURE_CEILING,
                saveToAlbum = false,
            )
        assertEquals(0, bumps)
    }

    /**
     * B5: the save used to be swallowed by a best-effort step, and every effect after it ran anyway on the
     * settings that never landed — the album created and filled, downloads cancelled, uploads re-armed. The save
     * is required now: a failure stops the reconfigure and is answered as SaveFailed.
     */
    @Test
    fun `a failed save runs none of the later steps and answers SaveFailed`() = runTest {
        val order = mutableListOf<String>()
        val outcome = make(Membership(current()), ConfigWrites(fails = true), order)
            .reconfigure(
                eventId = "E1",
                direction = Direction.UploadOnly, // would cancel downloads
                chosenCutoff = captureCutoff("2026-07-06T18:00:00Z"),
                chosenUpper = FIXTURE_CEILING,
                saveToAlbum = true, // would create and fill the album
            )
        assertEquals(ReconfigureOutcome.SaveFailed, outcome)
        assertEquals(emptyList(), order, "a step ran on settings that were never saved")
    }

    @Test
    fun `a landed save answers Saved and a stale surface answers NotCurrent`() = runTest {
        val saved = make(Membership(current()), ConfigWrites())
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T18:00:00Z"), FIXTURE_CEILING, false)
        assertEquals(ReconfigureOutcome.Saved, saved)
        val stale = make(Membership(current(eventId = "OTHER")), ConfigWrites())
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T18:00:00Z"), FIXTURE_CEILING, false)
        assertEquals(ReconfigureOutcome.NotCurrent, stale)
    }

    @Test
    fun `a no-op reconfigure does not advance the manifest version`() = runTest {
        var bumps = 0
        make(Membership(current(eventId = "OTHER")), ConfigWrites(), bumpManifestVersion = { bumps++ })
            .reconfigure(
                eventId = "E1",
                direction = Direction.Both,
                chosenCutoff = captureCutoff("2026-07-06T18:00:00Z"),
                chosenUpper = FIXTURE_CEILING,
                saveToAlbum = false,
            )
        assertEquals(0, bumps)
    }

    @Test
    fun `saves the whole config with only the three participation fields changed`() = runTest {
        val store = ConfigWrites()
        make(Membership(current()), store).reconfigure(
            eventId = "E1",
            direction = Direction.UploadOnly,
            chosenCutoff = captureCutoff("2026-07-06T18:00:00Z"),
            chosenUpper = FIXTURE_CEILING,
            saveToAlbum = true,
        )

        val saved = store.saved!!
        // eventId / name / startsAt preserved; the three participation fields changed.
        assertEquals("E1", saved.eventId)
        assertEquals("Anna's Birthday", saved.name)
        assertEquals(eventStart("2026-07-06T12:00:00Z"), saved.startsAt)
        assertEquals(Direction.UploadOnly, saved.direction)
        assertEquals(captureCutoff("2026-07-06T18:00:00Z"), saved.minPhotoDate)
        assertTrue(saved.saveToAlbum)
    }

    @Test
    fun `a chosen cutoff below the floor is clamped up to startsAt`() = runTest {
        val store = ConfigWrites()
        make(Membership(current()), store).reconfigure(
            eventId = "E1",
            direction = Direction.Both,
            chosenCutoff = captureCutoff("2026-07-01T00:00:00Z"), // before the event start
            chosenUpper = FIXTURE_CEILING,
            saveToAlbum = false,
        )
        assertEquals(captureCutoff("2026-07-06T12:00:00Z"), store.saved!!.minPhotoDate)
    }

    @Test
    fun `a mismatched eventId is a no-op with no write and no effects`() = runTest {
        val store = ConfigWrites()
        val order = mutableListOf<String>()
        // The surface was opened for E9, but the current membership is E1 (a switch landed).
        make(Membership(current(eventId = "E1")), store, order).reconfigure(
            eventId = "E9",
            direction = Direction.DownloadOnly,
            chosenCutoff = captureCutoff("2026-07-06T18:00:00Z"),
            chosenUpper = FIXTURE_CEILING,
            saveToAlbum = true,
        )
        assertNull(store.saved)
        assertTrue(order.isEmpty())
    }

    @Test
    fun `no config is a no-op`() = runTest {
        val store = ConfigWrites()
        make(Membership(null), store).reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T18:00:00Z"), FIXTURE_CEILING, false)
        assertNull(store.saved)
    }

    @Test
    fun `enabling upload arms the producer`() = runTest {
        val order = mutableListOf<String>()
        make(Membership(current(direction = Direction.DownloadOnly)), ConfigWrites(), order)
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T12:00:00Z"), FIXTURE_CEILING, false)
        assertTrue("arm" in order)
    }

    @Test
    fun `disabling upload still only kicks the arm whose transition stops nothing`() = runTest {
        val order = mutableListOf<String>()
        make(Membership(current(direction = Direction.Both)), ConfigWrites(), order)
            .reconfigure("E1", Direction.DownloadOnly, captureCutoff("2026-07-06T12:00:00Z"), FIXTURE_CEILING, false)
        // The reconfigure transition never deregisters or cancels (UploadTransitionsTest); the cycle's policy is
        // what stops new work, so in-flight uploads drain.
        assertTrue("arm" in order)
    }

    @Test
    fun `enabling download reconciles`() = runTest {
        val order = mutableListOf<String>()
        make(Membership(current(direction = Direction.UploadOnly)), ConfigWrites(), order)
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T12:00:00Z"), FIXTURE_CEILING, false)
        assertTrue("reconcile:E1" in order)
        assertTrue("cancelDownloads" !in order)
    }

    @Test
    fun `disabling download cancels in-flight downloads`() = runTest {
        val order = mutableListOf<String>()
        make(Membership(current(direction = Direction.Both)), ConfigWrites(), order)
            .reconfigure("E1", Direction.UploadOnly, captureCutoff("2026-07-06T12:00:00Z"), FIXTURE_CEILING, false)
        assertTrue("cancelDownloads" in order)
        assertTrue(order.none { it.startsWith("reconcile") })
    }

    // ---- the cutoff-lowering backfill fix (capability `manage-membership`) -----------------------

    @Test
    fun `always ensures the album and refreshes status`() = runTest {
        val order = mutableListOf<String>()
        make(Membership(current()), ConfigWrites(), order)
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T12:00:00Z"), FIXTURE_CEILING, true)
        assertTrue("refresh" in order)
        assertTrue("album" in order)
    }

    @Test
    fun `the album gather starts after the album is ensured`() = runTest {
        val order = mutableListOf<String>()
        make(Membership(current()), ConfigWrites(), order)
            .reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T12:00:00Z"), FIXTURE_CEILING, true)
        assertTrue("gather" in order)
        assertTrue(order.indexOf("album") < order.indexOf("gather"), "the gather never ensures the album itself")
    }

    @Test
    fun `a failing gather does not abort the remaining effects`() = runTest {
        val order = mutableListOf<String>()
        make(
            Membership(current(direction = Direction.UploadOnly)), ConfigWrites(), order,
            gatherAlbum = { error("boom") },
        ).reconfigure("E1", Direction.Both, captureCutoff("2026-07-06T12:00:00Z"), FIXTURE_CEILING, true)
        assertTrue("reconcile:E1" in order, "the download effect after the gather still ran")
    }
}
