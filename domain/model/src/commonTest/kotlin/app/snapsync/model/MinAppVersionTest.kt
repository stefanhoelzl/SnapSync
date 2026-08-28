package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `426` body codec (capability `min-app-version`).
 *
 * Every failure to read the body collapses to `null`, and the tests below are the enumeration of what
 * "every" means — because the collapse is only safe while each cause really does share the one
 * consequence the KDoc claims: no version to print, on a screen whose refusal is already carried by the
 * status. A cause that needed a different answer would have to be pulled back out of this null.
 */
class MinAppVersionTest {

    @Test
    fun the_minimum_is_read_from_the_backend_body() {
        assertEquals("0.4", minAppVersionFromRefusal("""{"error":"app too old","minAppVersion":"0.4"}"""))
    }

    @Test
    fun unknown_keys_do_not_defeat_the_read() {
        // The backend may add fields; a refusal that stopped being readable because of one would put the
        // device on an update screen naming no version, for no reason.
        assertEquals("1.10", minAppVersionFromRefusal("""{"minAppVersion":"1.10","retryAfter":30}"""))
    }

    @Test
    fun every_unreadable_body_collapses_to_no_version() {
        for (body in listOf(
            "",                                   // empty
            "not json at all",                    // not JSON
            "[]",                                 // JSON, but not an object
            "\"0.4\"",                            // JSON, but a bare string
            "{}",                                 // an object with no such key
            """{"minAppVersion":null}""",         // the key, explicitly null
            """{"minAppVersion":""}""",           // present but blank — a version nobody could install
            """{"minAppVersion":"   "}""",        // whitespace only
            """{"minAppVersion":4}""",            // the right key, the wrong type
        )) {
            assertNull(minAppVersionFromRefusal(body), "body was: $body")
        }
    }
}
