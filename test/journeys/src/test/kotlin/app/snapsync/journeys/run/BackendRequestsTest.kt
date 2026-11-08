package app.snapsync.journeys.run

import app.snapsync.journeys.run.BackendRequests.Request
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackendRequestsTest {

    private val log = Files.createTempDirectory("backend").resolve("backend.log").toFile().apply { deleteOnExit() }
    private val requests = BackendRequests(log)

    @Test
    fun a_line_reads_as_the_request_the_backend_answered() {
        // As the local backend printed it (2026-11-07).
        val line = "2026-11-07T18:09:05.704Z [2425a3] PUT http://127.0.0.1:8080/api/v2/events/e/files/devices/d/657/" +
            "primary?filename=PXL_swipe.jpg 201 154ms v=0.4 in=60754 out=0"
        assertEquals(
            Request(
                "PUT",
                "http://127.0.0.1:8080/api/v2/events/e/files/devices/d/657/primary?filename=PXL_swipe.jpg",
                201,
            ),
            Request.parse(line),
        )
        assertEquals(null, Request.parse("Listening on http://127.0.0.1:8080/"))
    }

    @Test
    fun a_wait_finds_a_request_told_after_its_mark_and_none_before() {
        log.writeText(line("PUT", "/files/old", 201))
        val from = requests.mark()
        val writer = thread {
            log.appendText(line("GET", "/files/new", 200))
            // An unfinished line is not a request yet: its end comes in the next write.
            log.appendText(line("PUT", "/files/new", 201).dropLast(1))
            log.appendText("\n")
        }
        val found = requests.await(10.seconds, "the new upload", from) { it.method == "PUT" }
        writer.join()
        assertEquals(Request("PUT", "/files/new", 201), found)
    }

    @Test
    fun a_wait_that_ends_unanswered_names_what_it_waited_for_and_what_it_saw() {
        log.writeText(line("GET", "/files/x", 200))
        val failed = assertFailsWith<AssertionError> {
            requests.await(200.milliseconds, "the upload", 0) { it.method == "PUT" }
        }
        val message = failed.message.orEmpty()
        assertTrue("the upload: not within 200ms" in message && "GET /files/x 200" in message, message)
    }

    private fun line(method: String, url: String, status: Int) = "2026-11-07T18:09:05.704Z [ab12cd] $method $url $status 1ms v=-\n"
}
