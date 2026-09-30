package app.snapsync.buildlogic

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The loopback peer the upload contract exchanges bytes with (`scripts/transfer-fixture.py`), served on the HOST for
 * the Android device tests while they run — started by the first device-test task that asks for it, stopped when the
 * build ends. An emulator reaches the host's loopback as `10.0.2.2`, so no `adb reverse` is needed, on a managed
 * device or a connected one alike ([FIXTURE_ADDRESS]).
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

    /** Starts the fixture once per build and waits until it answers; later calls return at once. */
    @Synchronized
    fun ensureStarted() {
        if (process?.isAlive == true) return
        val log = parameters.log.get().asFile.apply { parentFile.mkdirs() }
        val started = ProcessBuilder(
            "python3", parameters.script.get().asFile.path,
            "--port", parameters.port.get().toString(), "--log", log.path,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        process = started
        val health = URI("http://127.0.0.1:${parameters.port.get()}/_health").toURL()
        repeat(HEALTH_ATTEMPTS) {
            check(started.isAlive) { "the transfer fixture exited at start — see ${log.path}" }
            val up = runCatching {
                (health.openConnection() as HttpURLConnection).run {
                    connectTimeout = HEALTH_TIMEOUT_MS
                    readTimeout = HEALTH_TIMEOUT_MS
                    responseCode == HttpURLConnection.HTTP_OK
                }
            }.getOrDefault(false)
            if (up) return
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
        const val PORT = 8123

        /** Where a device-test APK on an emulator reaches the fixture: the host's loopback, as the emulator names it. */
        const val FIXTURE_ADDRESS = "http://10.0.2.2:$PORT"

        private const val HEALTH_ATTEMPTS = 50
        private const val HEALTH_POLL_MS = 200L
        private const val HEALTH_TIMEOUT_MS = 1_000
        private const val STOP_SECONDS = 5L
    }
}
