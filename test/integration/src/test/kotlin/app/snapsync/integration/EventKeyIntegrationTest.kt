package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.format.EncryptedFileFormat
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * **Only the whole invite opens an encrypted event's photos**, read where the outside can read it: the objects the
 * storage holds, and every request as the service received it. The app's part is what it sends — sealed bytes, and no
 * key; the service's sealing at the edge of what an iPhone's system uploads, and what it keeps of the key it is handed
 * for it, are the api's.
 */
@Verifies(spec = "invite-link", requirement = "Only the whole invite opens an event's photos")
class EventKeyIntegrationTest {

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Only the whole invite opens an event's photos",
        scenario = "The stored photos cannot be opened without the invite",
    )
    fun what_the_app_stores_of_an_encrypted_event_opens_only_with_the_invites_key() = behaviour {
        // The app's part: what its own uploads store is sealed under the invite's key, and opens with no other.
        val event = given {
            appUploadsOnly()
            joinedEvent().also {
                photos("A", "B")
                step("both uploading", act = { os.foreground() }, until = {
                    settled { it.state.inFlight?.sessionTransfers?.size == 2 }
                })
            }
        }
        val key = keyOfInvite(given { inviteFor(event) })

        lateinit var a: Seen<WorldEvent.BytesStored>
        lateinit var b: Seen<WorldEvent.BytesStored>
        whenever { uploadSession.complete() }
        then {
            a = event<WorldEvent.BytesStored> { it.asset == "A" }
            b = event<WorldEvent.BytesStored> { it.asset == "B" }
        }

        listOf(a.value, b.value).forEach { stored ->
            val photo = assertNotNull(stored.openedWith(key), "the invite's key opens ${stored.asset}")
            assertNull(stored.openedWith(Random(OTHER_KEY_SEED).nextBytes(KEY_LENGTH)), "no other key opens it")
            assertFalse(stored.storedBytes().holds(photo), "${stored.asset} is not stored in the clear")
        }
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Only the whole invite opens an event's photos",
        scenario = "The stored photos cannot be opened without the invite",
    )
    fun a_camera_sized_photo_is_stored_sealed_segment_by_segment_and_opens_whole_with_the_invites_key() = behaviour {
        // A camera's photo is megabytes: its file runs past the format's first segment, so every later one is sealed too.
        val event = given {
            appUploadsOnly()
            joinedEvent().also {
                step("a camera-sized photo taken", act = {
                    library.add(CAMERA_SIZED, date = Defaults.PHOTO_DATE, size = CAMERA_SIZE)
                }, until = { settled { true } })
                step("uploading", act = { os.foreground() }, until = {
                    settled { it.state.inFlight?.sessionTransfers?.size == 1 }
                })
            }
        }
        val key = keyOfInvite(given { inviteFor(event) })

        lateinit var stored: Seen<WorldEvent.BytesStored>
        whenever { uploadSession.complete() }
        then { stored = event<WorldEvent.BytesStored> { it.asset == CAMERA_SIZED } }

        val photo = assertNotNull(stored.value.openedWith(key), "the invite's key opens every segment")
        assertEquals(CAMERA_SIZE, photo.size, "the whole photo, every segment of it")
        assertNull(stored.value.openedWith(Random(OTHER_KEY_SEED).nextBytes(KEY_LENGTH)), "no other key opens it")
    }

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Only the whole invite opens an event's photos",
        scenario = "The service never learns the event's key",
    )
    fun no_request_carries_the_key_but_an_os_upload_carries_its_photos_own() = behaviour {
        // The app's part: no request it makes carries the key, and an upload iOS performs carries only that photo's
        // own.
        given { extensionUploadsOnly() }

        lateinit var gate: Seen<Layer.JoiningEvent>
        lateinit var created: Seen<WorldEvent.BackendReceived>
        whenever { createEvent(Defaults.EVENT_NAME, Defaults.WINDOW_START, Defaults.WINDOW_END) }
        then {
            gate = screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed }
            created = event<WorldEvent.BackendReceived> { it.operation == CREATE }
            settled { true }
        }
        val event = gate.value.eventId
        val key = keyOfInvite(given { inviteFor(event) })
        assertFalse(carries(created.value, key), "the create names the key by its id only")

        whenever { confirmJoin() }
        then {
            screen<Layer.Joined>()
            never<WorldEvent.BackendReceived> { carries(it, key) }
            settled { true }
        }

        // Receiving: another member's photo is read from the list, downloaded and saved.
        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            os.foreground()
        }
        then {
            event<WorldEvent.DownloadStarted>()
            never<WorldEvent.BackendReceived> { carries(it, key) }
            settled { true }
        }
        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { it.asset == "imported-$DEV_F-FQ" }
            never<WorldEvent.BackendReceived> { carries(it, key) }
            settled { true }
        }

        // Sharing, by iOS's own uploader: the one request that carries a key carries that photo's.
        lateinit var upload: Seen<WorldEvent.BackendReceived>
        whenever {
            takePhoto("A")
            uploadAll()
        }
        then {
            upload = event<WorldEvent.BackendReceived> { it.operation == UPLOAD && it.header(FILE_KEY) != null }
            never<WorldEvent.BackendReceived> { carries(it, key) }
            never<WorldEvent.BytesStored> { it.bytes != null && it.storedBytes().holds(key) }
            settled { true }
        }

        val request = upload.value
        val binding = bindingOf(checkNotNull(request.url))
        val head = base64UrlDecoded(checkNotNull(request.header(FILE_HEAD)))
        val fileKey = base64UrlDecoded(checkNotNull(request.header(FILE_KEY)))
        assertContentEquals(fileKeyOf(key, head, binding), fileKey, "the photo's own key, derived for it")
        assertFalse(fileKey.contentEquals(key), "never the event's key")
    }

    private companion object {
        const val CREATE = "createEvent"
        const val UPLOAD = "uploadResource"
        const val FILE_KEY = EncryptedFileFormat.FILE_KEY_HEADER
        const val FILE_HEAD = EncryptedFileFormat.FILE_HEAD_HEADER
        const val KEY_LENGTH = 32
        const val OTHER_KEY_SEED = 38

        const val CAMERA_SIZED = "CAMERA"

        /** Past three of the format's 64 KiB segments, so the photo is sealed as four. */
        const val CAMERA_SIZE = 200 * 1024
    }
}
