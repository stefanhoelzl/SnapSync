package app.snapsync.services.identity

import app.snapsync.data.identity.StoredDeviceIdentity
import app.snapsync.model.DeviceIdentityAbsent
import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.testids.testDeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The old zones' throwing device-id read, as they read it before trust moved: an id, or out of the step. */
class RequireDeviceIdTest {
    @Test
    fun an_id_is_answered_and_its_absence_or_an_unreadable_store_is_thrown() {
        assertEquals(testDeviceId("D"), identityOf("D").requireDeviceId())
        assertFailsWith<DeviceIdentityAbsent> { StoredDeviceIdentity(MapSecureStore()).requireDeviceId() }
        val locked = assertFailsWith<SecureStoreUnavailable> {
            StoredDeviceIdentity(MapSecureStore(unavailable = true)).requireDeviceId()
        }
        assertEquals("locked", locked.detail)
    }
}
