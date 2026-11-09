package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A received photo keeps the name its sender gave it, whatever shape that name has** (capability
 * `receiving-photos`), read off the photo the library creates: the sender's name with SnapSync's mark added before its
 * extension — and where there is no extension to put it before, at its end.
 */
@Verifies(
    spec = "receiving-photos",
    requirement = "Received photos arrive in full fidelity",
    scenario = "The sender's filename is kept",
)
class SenderNameIntegrationTest {

    @Test
    fun a_sender_name_without_an_extension_is_kept_with_the_mark_at_its_end() =
        arrivesNamed("IMG_4471", "IMG_4471\\.$MARK")

    @Test
    fun a_sender_name_ending_in_its_dot_is_kept_with_the_mark_after_it() =
        arrivesNamed("IMG_4471.", "IMG_4471\\.$MARK")

    @Test
    fun a_sender_name_that_is_only_an_earlier_mark_carries_only_this_photos_mark() =
        arrivesNamed("snapsync-aaaaaaaaaa.HEIC", "(?!snapsync-aaaaaaaaaa)$MARK\\.HEIC")

    /** The fellow member's photo, named [sent] on its device, is created in the library under a name matching [kept]. */
    private fun arrivesNamed(sent: String, kept: String) = behaviour {
        given {
            joinedEvent()
            namedPhotoOnItsWay(sent)
        }

        whenever { downloadSession.finish() }
        then {
            event<WorldEvent.LibraryAssetCreated> { created ->
                created.names.size == 1 && Regex(kept).matches(created.names.single())
            }
        }
    }

    private companion object {
        /** SnapSync's mark: a token of the photo's source, ten base32 characters. */
        const val MARK = "snapsync-[a-z2-7]{10}"
    }
}
