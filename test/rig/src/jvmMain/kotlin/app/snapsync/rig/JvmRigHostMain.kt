package app.snapsync.rig

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/**
 * `./gradlew :test:rig:runJvmHost` — the JVM host for an agent to drive by hand (the `rig-channel` skill).
 *
 * `snapsync.rigBackend` picks the world's backend (`mini`, the default, or `deno`); `snapsync.rigPort` the loopback
 * port (`0`, the default, asks the OS). Prints exactly one `RIG-JVM READY <port>` line once bound — the line a
 * caller waits for — and serves until the process is killed.
 */
fun main(): Unit = runBlocking {
    val backend = System.getProperty("snapsync.rigBackend") ?: "mini"
    val port = System.getProperty("snapsync.rigPort")?.toIntOrNull() ?: 0
    val host = JvmRigHost.start(backend, port)
    Runtime.getRuntime().addShutdownHook(Thread { host.close() })
    System.out.write("RIG-JVM READY ${host.port}\n".toByteArray())
    System.out.flush()
    awaitCancellation()
}
