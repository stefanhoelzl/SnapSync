package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A dev/test control channel binds the loopback address only** (`docs/architecture.md`).
 *
 * The control channel forces operating-system entry points and exposes event state on its app host, and pulls
 * the world's failure levers on its JVM host. Widening its bind is a one-token edit that reads as fixing a
 * connectivity problem and looks nothing like a security decision, so it is held here rather than by review.
 *
 * `RigServer` cited this guard for as long as the channel has existed, and it did not exist. The JVM host added a
 * second host serving the same routes, which is when it was written.
 */
class ControlChannelLoopbackTest {

    private val channel = SourceScan.kotlinFiles().filter { it.path.startsWith("/test/rig/src/") }

    @Test
    fun `every server the control channel starts binds the loopback constant`() {
        val binds = channel.flatMap { source ->
            SERVER.findAll(source.text).map { source.path to it.value }.toList()
        }
        assertTrue(
            binds.isNotEmpty(),
            "no `embeddedServer(` found under test/rig/src (${channel.size} files) — the channel moved or " +
                "was renamed, and this guard would pass while checking nothing",
        )
        val wide = binds.filterNot { (_, call) -> "host = LOOPBACK" in call }
        assertEquals(
            emptyList(),
            wide,
            "\nA control-channel server is bound to something other than the `LOOPBACK` constant.\n" +
                "  The channel must be reachable only from the machine it runs on (capability\n" +
                "  `docs/architecture.md`, \"A dev/test control channel binds the loopback address only\").\n",
        )
    }

    @Test
    fun `the control channel names no other network address`() {
        val literals = channel.flatMap { source ->
            ADDRESS.findAll(source.text).map { "${source.path}: ${it.value}" }.toList()
        }
        assertEquals(
            listOf("/test/rig/src/commonMain/kotlin/app/snapsync/rig/RigServer.kt: 127.0.0.1"),
            literals,
            "\nThe control channel's source names an address other than its one loopback constant.\n",
        )
    }

    private companion object {
        /** A server construction, up to its first closing parenthesis — where the bind host is named. */
        val SERVER = Regex("""embeddedServer\([^)]*\)""")

        /** An IPv4 literal, or a name that binds every interface. */
        val ADDRESS = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b|"0\.0\.0\.0"|"::"|"localhost"""")
    }
}
