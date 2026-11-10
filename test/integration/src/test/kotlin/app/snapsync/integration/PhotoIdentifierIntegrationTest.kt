package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A photo is shared whatever its library calls it.** A platform names a photo as it likes — PhotoKit does not
 * document its identifier's shape — so a photo whose identifier holds characters today's platforms do not use (a space, a
 * `~`, an `_`, a `..`, a letter outside ASCII) is no reason to leave it out: the app spells it in its own canonical form
 * on the wire, and finds the photo again under the platform's name to read its bytes.
 */
class PhotoIdentifierIntegrationTest {

    @Test
    @Verifies(spec = "photo-sharing", requirement = "When in doubt, a photo is shared")
    fun a_photo_named_with_any_characters_is_shared_under_its_canonical_spelling() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos(PLATFORM_ID)
        }

        whenever { uploadAll() }
        then {
            event<WorldEvent.ManifestPublished> { CANONICAL_ID in it.added }
            event<WorldEvent.BytesStored> { it.asset == CANONICAL_ID }
        }
    }

    @Test
    @Verifies(spec = "photo-sharing", requirement = "When in doubt, a photo is shared")
    fun a_photo_named_with_spaces_and_letters_outside_ascii_reaches_the_event_under_that_name() = behaviour {
        given {
            creatingPlain()
            extensionUploadsOnly()
            joinedEvent()
            step("a saved photo, named as its sender named it", act = {
                library.add(SAVED_PHOTO, date = Defaults.PHOTO_DATE, filename = SAVED_NAME)
            }, until = { settled { true } })
        }

        whenever { uploadAll() }
        then {
            // A plain event's upload names the photo in its query, every byte outside RFC 3986's unreserved set escaped.
            event<WorldEvent.BackendReceived> { it.operation == UPLOAD && it.url?.endsWith(ESCAPED_NAME) == true }
            event<WorldEvent.BytesStored> { it.asset == SAVED_PHOTO }
        }
    }

    private companion object {
        const val UPLOAD = "uploadResource"

        const val SAVED_PHOTO = "SAVED"

        /** A name as a person or a messenger gives a saved photo: a space, and a letter outside ASCII. */
        const val SAVED_NAME = "Bild 1 ä.jpg"

        /** [SAVED_NAME] as the query carries it: UTF-8, percent-encoded. */
        const val ESCAPED_NAME = "filename=Bild%201%20%C3%A4.jpg"

        /** A library's identifier holding every character the canonical form spells otherwise. */
        const val PLATFORM_ID = "a b/c~d_e..fä"

        /**
         * Its canonical spelling: a space and `ä` as `~` and their four hex digits, `/` as `_`, `~` and `_` escaped, and
         * the second of two dots escaped.
         */
        const val CANONICAL_ID = "a~0020b_c~~d~_e.~.f~00e4"
    }
}
