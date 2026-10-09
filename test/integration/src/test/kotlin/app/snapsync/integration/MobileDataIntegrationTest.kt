package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.model.Arrow
import app.snapsync.model.Layer
import app.snapsync.model.SyncHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Photos kept off mobile data wait for Wi-Fi**, over the real composed stack, driven through the control protocol.
 *
 * The operator plays the device's network (`network?access=restricted` — mobile data, a hotspot, Low Data Mode) and the
 * operating system's transfers: it lands what the platform would run, and the platform runs no transfer held to an
 * unrestricted network while the network is restricted. The app's own uploader is the one under test — the OS-driven
 * queue holds every job on a restricted network whatever the member chose (measured; `UploadQueueMock`), so it is
 * switched off here.
 *
 * The choice is the device's, flipped from the app menu (decision record `changes/archive/2026-10-07-mobile-data-per-device`).
 */
class MobileDataIntegrationTest {

    private suspend fun Rig.appUploadsOnly() {
        device("uploaders", "extension" to "off")
    }

    /** The app menu's switch: the device's choice, applied as it is flipped. */
    private suspend fun Rig.mobileData(on: Boolean) {
        user("mobileData", "on" to on.toString())
        awaitState { it.ui.mobileData.on == on }
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
    @Verifies(
        spec = "mobile-data",
        requirement = "The member chooses for the device whether photos may use mobile data",
        scenario = "Untouched, photos use any network",
    )
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
    @Verifies(spec = "mobile-data", requirement = "With mobile data off, photos wait for an unrestricted Wi-Fi")
    @Verifies(
        spec = "sync-status",
        requirement = "Direction arrows show remaining work and live transfer",
        scenario = "Photos waiting for Wi-Fi are named",
    )
    fun with_mobile_data_off_an_upload_waits_for_wifi_and_says_so() = rigTest {
        appUploadsOnly()
        createAndJoin()
        mobileData(false)
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
    @Verifies(spec = "mobile-data", requirement = "With mobile data off, photos wait for an unrestricted Wi-Fi")
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "Downloads wait for Wi-Fi when the member chose so",
    )
    fun with_mobile_data_off_a_received_photo_waits_for_wifi() = rigTest {
        createAndJoin("direction" to "download")
        mobileData(false)
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
    @Verifies(spec = "mobile-data", requirement = "A change applies to transfers that start afterwards")
    fun a_change_governs_only_the_transfers_that_start_after_it() = rigTest {
        appUploadsOnly()
        createAndJoin()
        mobileData(false)
        network("restricted")
        shareFromForeground("P1")
        awaitAppUploads(1)

        mobileData(true)
        shareFromForeground("P2")
        awaitAppUploads(2)
        completeAppUploads()

        eventually<Boolean>(read = { landed("P2") }) { it }
        assertTrue(!landed("P1"), "the transfer started under the old rule keeps it and still waits")
    }

    @Test
    @Verifies(spec = "mobile-data", requirement = "Everything but photo transfers keeps working on any network")
    fun joining_and_renaming_work_on_mobile_data_with_photos_kept_off_it() = rigTest {
        network("restricted")
        mobileData(false)
        val event = createAndJoin()
        assertEquals(false, state().ui.mobileData.on)
        user("rename", "name" to "Off the plan")
        eventually<String?>(read = { state().joined?.membership?.name }) { it == "Off the plan" }
        assertEquals(event, state().ready.eventId)
    }

    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "The member chooses for the device whether photos may use mobile data",
        scenario = "The choice carries to the next event",
    )
    fun the_choice_carries_to_the_next_event() = rigTest {
        appUploadsOnly()
        createAndJoin()
        mobileData(false)
        user("leave")
        awaitState { it.ui.layer is Layer.CreateEvent }

        createAndJoin()
        assertEquals(false, state().ui.mobileData.on, "the next event starts with the device's choice")
        network("restricted")
        shareFromForeground("P1")
        awaitAppUploads(1)
        completeAppUploads()
        assertTrue(!landed("P1"), "the new event's photo waits for Wi-Fi without choosing again")
    }

    @Test
    @Verifies(
        spec = "mobile-data",
        requirement = "The member chooses for the device whether photos may use mobile data",
        scenario = "Chosen before joining",
    )
    fun chosen_before_joining() = rigTest {
        appUploadsOnly()
        mobileData(false)
        network("restricted")
        createAndJoin()
        shareFromForeground("P1")
        awaitAppUploads(1)
        completeAppUploads()
        assertTrue(!landed("P1"), "no photo of the event travels over mobile data")
        network("online")
        completeAppUploads()
        eventually<Boolean>(read = { landed("P1") }) { it }
    }
}
