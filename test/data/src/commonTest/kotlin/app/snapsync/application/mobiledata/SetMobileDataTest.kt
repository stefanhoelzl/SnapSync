package app.snapsync.application.mobiledata

import app.snapsync.application.EntryPointCall
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.mobiledata.MobileDataOutcome
import app.snapsync.domain.request.RequestId
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.ports.Preferences
import app.snapsync.ports.WriteOutcome
import app.snapsync.query.RequestStatus
import app.snapsync.query.mobiledata.MobileDataLookup
import app.snapsync.query.mobiledata.MobileDataStatuses
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A change of the mobile-data choice, and the menu's read of it. */
@OptIn(EntryPointCall::class)
class SetMobileDataTest {

    private val values = mutableMapOf<String, String>()

    @Test
    fun `a change that lands is in effect`() = runTest {
        val store = MobileDataStore(inMemoryPreferences(values))
        assertEquals(
            MobileDataOutcome.Saved(MobileDataChoice.WIFI_ONLY),
            SetMobileData(store)(MobileDataChoice.WIFI_ONLY),
        )
        assertEquals(MobileDataChoice.WIFI_ONLY, store.read())
    }

    @Test
    fun `a change that cannot be saved answers the choice still in effect`() = runTest {
        val refusing = object : Preferences by inMemoryPreferences(values) {
            override fun set(key: String, value: String): WriteOutcome = WriteOutcome.Failed("locked")
        }
        val outcome = SetMobileData(MobileDataStore(refusing))(MobileDataChoice.WIFI_ONLY)
        assertEquals(MobileDataOutcome.NotSaved(MobileDataChoice.ALLOWED), outcome)
    }

    @Test
    fun `the menu reads the choice now and a change's answer under its id`() {
        val store = MobileDataStore(inMemoryPreferences(values))
        val statuses = MobileDataStatuses()
        val lookup = MobileDataLookup(store, statuses)
        assertEquals(MobileDataChoice.ALLOWED, lookup.current())

        store.write(MobileDataChoice.WIFI_ONLY)
        assertEquals(MobileDataChoice.WIFI_ONLY, lookup.current(), "read at every call")

        statuses.change.begun(RequestId(1))
        statuses.change.done(RequestId(1), MobileDataOutcome.Saved(MobileDataChoice.WIFI_ONLY))
        assertEquals(
            RequestStatus.Done(RequestId(1), MobileDataOutcome.Saved(MobileDataChoice.WIFI_ONLY)),
            lookup.changes.value,
        )
    }
}
