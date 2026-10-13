package app.snapsync.buildlogic

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The loopback peer the transfer and HTTP contracts exchange bytes with (`scripts/transfer-fixture.py`), served on the
 * HOST for the test tasks that ask for it (the Android device tests; `snapsync.transfer-fixture`) while they run — started by the first device-test task that asks for it, stopped when the
 * build ends. An emulator reaches the host's loopback as `10.0.2.2`, so no `adb reverse` is needed, on a managed
 * device or a connected one alike ([emulatorAddress]).
 *
 * Its port is a free one, chosen once per build ([freePort]) and handed to every consumer: the host and its emulators
 * are shared by every checkout's builds, and a fixed port let one build's tests exchange with another build's fixture
 * until that build ended and took it away. Only a fixture that answers `/_health` with this build's own process id
 * counts as started, so a port taken between configuration and the run fails here, never as the clauses' transfers.
 *
 * It is a service, not a script around the build, so `./gradlew androidPlatformTest` alone is the whole run — in CI
 * and on this box.
 */
abstract class TransferFixtureService : BuildService<TransferFixtureService.Params>, AutoCloseable {

    interface Params : BuildServiceParameters {
        val script: RegularFileProperty
        val log: RegularFileProperty
        val port: Property<Int>
    }

    private var process: Process? = null

    /** Starts the fixture once per build and waits until it answers as itself; later calls return at once. */
    @Synchronized
    fun ensureStarted() {
        if (process?.isAlive == true) return
        val log = parameters.log.get().asFile.apply { parentFile.mkdirs() }
        val started = ProcessBuilder(
            "python3", parameters.script.get().asFile.path,
            "--port", parameters.port.get().toString(), "--log", log.path,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        process = started
        val health = URI("${loopbackAddress(parameters.port.get())}/_health").toURL()
        repeat(HEALTH_ATTEMPTS) {
            check(started.isAlive) { "the transfer fixture exited at start — see ${log.path}" }
            val answeredBy = runCatching {
                (health.openConnection() as HttpURLConnection).run {
                    connectTimeout = HEALTH_TIMEOUT_MS
                    readTimeout = HEALTH_TIMEOUT_MS
                    if (responseCode == HttpURLConnection.HTTP_OK) inputStream.use { it.readBytes().decodeToString() } else null
                }
            }.getOrNull()
            // Another process answering on the port is not this fixture: ours dies on its bind, and the check above says so.
            if (answeredBy == started.pid().toString()) return
            Thread.sleep(HEALTH_POLL_MS)
        }
        error("the transfer fixture never answered on $health — see ${log.path}")
    }

    override fun close() {
        process?.run {
            destroy()
            if (!waitFor(STOP_SECONDS, TimeUnit.SECONDS)) destroyForcibly()
        }
    }

    companion object {
        const val NAME = "transferFixture"

        /** Where a device-test APK on an emulator reaches the fixture: the host's loopback, as the emulator names it. */
        fun emulatorAddress(port: Int) = "http://10.0.2.2:$port"

        /** Where a JVM test, or a simulator test executable sharing the host's network, reaches it. */
        fun loopbackAddress(port: Int) = "http://127.0.0.1:$port"

        /** A port nothing on the host listens on now; the fixture binds it when the first consumer runs. */
        fun freePort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

        private const val HEALTH_ATTEMPTS = 50
        private const val HEALTH_POLL_MS = 200L
        private const val HEALTH_TIMEOUT_MS = 1_000
        private const val STOP_SECONDS = 5L
    }
}
