package app.snapsync.feature.membership

import app.snapsync.model.EventConfig
import app.snapsync.model.JoinLoad
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Config seam + store as one fake: save writes the cell, exactly as the real Keychain adapter behaves.
private class FakeConfig(initial: EventConfig?) : ConfigSource, ConfigStore {
    private val flow = MutableStateFlow(initial)
    override val config: StateFlow<EventConfig?> = flow
    var saved: EventConfig? = null
    override suspend fun save(config: EventConfig) {
        saved = config
        flow.value = config
    }
    override suspend fun clear() {
        flow.value = null
    }
}

private const val STARTS = "2026-07-06T14:32:11Z"
private const val ENDS = "2026-07-13T14:32:11Z"

class EventNameTest {

    // A NON-legacy membership: it already carries the event window, so a details refresh only touches the
    // name (backfill is a no-op). This keeps the name-refresh assertions isolated from the backfill.
    private val joined = EventConfig(
        eventId = "E",
        name = "",
        minPhotoDate = STARTS,
        startsAt = STARTS,
        endsAt = ENDS,
        maxPhotoDate = ENDS,
    )

    private fun found(name: String, endsAt: String = ENDS) = JoinLoad.Found(name, STARTS, endsAt)

    @Test
    fun `stores a changed name as the whole config with the cutoff preserved`() = runTest {
        val config = FakeConfig(joined)
        EventName(config, config).storeRefreshedDetails("E", found("Anna's Birthday"))
        // The WHOLE config is saved with only `name` replaced — the cutoff (and every other
        // membership field) rides along untouched (capability `photo-selection-policy`).
        assertEquals(joined.copy(name = "Anna's Birthday"), config.saved)
    }

    @Test
    fun `an unchanged name saves nothing`() = runTest {
        val config = FakeConfig(joined.copy(name = "Anna's Birthday"))
        EventName(config, config).storeRefreshedDetails("E", found("Anna's Birthday"))
        assertNull(config.saved)
    }

    @Test
    fun `a fetch resolving for a different event saves nothing`() = runTest {
        // A stale fetch landing after a switch must not resurrect the departed membership's name.
        val config = FakeConfig(joined)
        EventName(config, config).storeRefreshedDetails("OTHER", found("Someone Else's Party"))
        assertNull(config.saved)
    }

    @Test
    fun `no membership saves nothing`() = runTest {
        val config = FakeConfig(null)
        EventName(config, config).storeRefreshedDetails("E", found("Anna's Birthday"))
        assertNull(config.saved)
    }

    @Test
    fun `a fetch that resolved nothing stores nothing`() = runTest {
        // The best-effort fetch's sealed no-result (offline / 404 / parse) is part of this rule
        // since the migration finale, so the flows' fetch-then-store is one straight-line step.
        val config = FakeConfig(joined)
        EventName(config, config).storeRefreshedDetails("E", null)
        assertNull(config.saved)
    }

    @Test
    fun `a legacy config missing the window is backfilled from the fetched details`() = runTest {
        // capability `event-rejoin-reconciliation`: endsAt/maxPhotoDate absent (joined before the window
        // existed) → filled from the fetched details, in the SAME save as any name refresh.
        val legacy = EventConfig(eventId = "E", name = "Anna's Birthday", minPhotoDate = STARTS, startsAt = STARTS)
        val config = FakeConfig(legacy)
        EventName(config, config).storeRefreshedDetails("E", found("Anna's Birthday"))
        assertEquals(legacy.copy(endsAt = ENDS, maxPhotoDate = ENDS), config.saved)
    }

    @Test
    fun `backfill and name refresh ride in one save`() = runTest {
        val legacy = EventConfig(eventId = "E", name = "", minPhotoDate = STARTS, startsAt = STARTS)
        val config = FakeConfig(legacy)
        EventName(config, config).storeRefreshedDetails("E", found("Anna's Birthday"))
        assertEquals(
            legacy.copy(name = "Anna's Birthday", endsAt = ENDS, maxPhotoDate = ENDS),
            config.saved,
        )
    }

    @Test
    fun `an already-set window is never overwritten by a backfill`() = runTest {
        // The member already chose a window; a later details fetch must not clobber their ceiling.
        val config = FakeConfig(joined.copy(name = "Anna's Birthday", maxPhotoDate = STARTS))
        EventName(config, config).storeRefreshedDetails("E", found("Anna's Birthday", endsAt = ENDS))
        assertNull(config.saved) // name unchanged AND endsAt already present → nothing to write
    }

    @Test
    fun `fetchNeed is MISSING only for a nameless membership`() {
        val eventName = EventName(FakeConfig(null), FakeConfig(null))
        assertEquals(TitleNeed.MISSING, eventName.fetchNeed(""))
        assertEquals(TitleNeed.PRESENT, eventName.fetchNeed("Anna's Birthday"))
    }
}
