package app.snapsync.integration

import app.snapsync.model.Arrow
import app.snapsync.model.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Photos kept off mobile data wait for Wi-Fi** (capability `mobile-data`), over the real composed stack, driven
 * through the control protocol.
 *
 * The operator plays the device's network (`network?access=restricted` — mobile data, a hotspot, Low Data Mode) and the
 * operating system's transfers: it lands what the platform would run, and the platform runs no transfer held to an
 * unrestricted network while the network is restricted. The app's own uploader is the one under test — the OS-driven
 * queue holds every job on a restricted network whatever the member chose (measured; `UploadQueueMock`), so it is
 * switched off here.
 */
class MobileDataIntegrationTest {

    private suspend fun Rig.appUploadsOnly() {
        device("uploaders", "extension" to "off")
    }

    private suspend fun Rig.network(access: String) {
        device("network", "access" to access)
    }

    /** The app's uploader picks up [photo]: the foreground's tail creates its transfer. */
    private suspend fun Rig.shareFromForeground(photo: String) {
        addPhoto(photo)
        foreground()
    }

    private suspend fun Rig.landed(photo: String): Boolean = objects().any { it.startsWith(photo) }

    @Test
    fun an_untouched_choice_shares_over_mobile_data() = rigTest {
        appUploadsOnly()
        createAndJoin()
        network("restricted")
        shareFromForeground("P1")
        awaitAppUploads(1)
        completeAppUploads()
        eventually<Boolean>(read = { landed("P1") }) { it }
    }

    @Test
    fun with_mobile_data_off_an_upload_waits_for_wifi_and_says_so() = rigTest {
        appUploadsOnly()
        createAndJoin("mobileData" to "false")
        network("restricted")
        shareFromForeground("P1")
        awaitAppUploads(1)

        completeAppUploads()
        assertEquals(1, appUploads().live.size, "the platform runs no held transfer on a restricted network")
        assertTrue(!landed("P1"))
        assertEquals(
            SyncHealth.Syncing(Arrow.STATIC, Arrow.HIDDEN, waitingForWifi = true),
            awaitHealth { it is SyncHealth.Syncing && it.waitingForWifi },
            "still arrows, and the line says the photo waits for Wi-Fi",
        )

        network("online")
        completeAppUploads()
        eventually<Boolean>(read = { landed("P1") }) { it }
        awaitHealth { it !is SyncHealth.Syncing || !it.waitingForWifi }
    }

    @Test
    fun with_mobile_data_off_a_received_photo_waits_for_wifi() = rigTest {
        createAndJoin("mobileData" to "false", "direction" to "download")
        network("restricted")
        foreignDevice("DEV-F", "FA")
        reconcile()
        eventually(read = { state().download.inFlight }) { inFlight: Int -> inFlight == 1 }

        stage()
        assertEquals(0, state().download.downloaded, "the platform runs no held download on a restricted network")

        network("online")
        stage()
        eventually<Int>(read = { state().download.downloaded }) { it == 1 }
    }

    @Test
    fun a_change_governs_only_the_transfers_that_start_after_it() = rigTest {
        appUploadsOnly()
        createAndJoin("mobileData" to "false")
        network("restricted")
        shareFromForeground("P1")
        awaitAppUploads(1)

        user("reconfigure", "mobileData" to "true")
        awaitState { it.joined?.membership?.mobileData == true }
        shareFromForeground("P2")
        awaitAppUploads(2)
        completeAppUploads()

        eventually<Boolean>(read = { landed("P2") }) { it }
        assertTrue(!landed("P1"), "the transfer started under the old rule keeps it and still waits")
    }

    @Test
    fun joining_and_renaming_work_on_mobile_data_with_photos_kept_off_it() = rigTest {
        network("restricted")
        val event = createAndJoin("mobileData" to "false")
        assertEquals(false, state().joined?.membership?.mobileData)
        user("rename", "name" to "Off the plan")
        eventually<String?>(read = { state().joined?.membership?.name }) { it == "Off the plan" }
        assertEquals(event, state().ready.eventId)
    }
}
