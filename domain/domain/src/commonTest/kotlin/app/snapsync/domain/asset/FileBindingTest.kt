package app.snapsync.domain.asset

import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a sealed file is bound to: its event, the sharing device, its asset and its role. */
class FileBindingTest {

    @Test
    fun `a binding names the resource it is, and a resource is its primary or its motion`() {
        val binding = FileBinding(
            EventId.parse("0f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f60")!!,
            DeviceId.parse("7c9e6679-7425-40de-944b-e07fc1f90ae7")!!,
            AssetId.parse("a1")!!,
            ResourceRole.LIVE,
        )
        assertEquals(
            "FileBinding(0f0e8a5c-3f1b-4c2d-9e7a-1b2c3d4e5f60/7c9e6679-7425-40de-944b-e07fc1f90ae7/a1/LIVE)",
            binding.toString(),
        )
        assertEquals(listOf(ResourceRole.PRIMARY, ResourceRole.LIVE), ResourceRole.entries)
    }
}
