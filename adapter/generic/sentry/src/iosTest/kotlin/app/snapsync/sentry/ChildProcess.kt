@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.sentry

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSProcessInfo
import platform.posix._exit
import platform.posix.execv
import platform.posix.fork
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import platform.posix.waitpid

/**
 * This test executable run again as a process of its own, filtered to one [test] (`--ktest_filter`), with [env] added to
 * its environment — how the process-death binding gets the app's processes and the next launch on the simulator, where
 * a test executable is an ordinary process (`fork` + `execv`; iOS declares no `posix_spawn`). Measured 2026-10-25: the
 * simulator runs it, and its Caches directory is the parent's. Its tests log plainly (`--ktest_logger=SIMPLE`), so the
 * parent's own test report, read by Gradle, is not mistaken for theirs. Blocks until it ends.
 */
internal fun runSelf(test: String, env: Map<String, String>): ChildEnd {
    val self = NSProcessInfo.processInfo.arguments.first() as String
    return memScoped {
        val argv = allocArray<CPointerVar<ByteVar>>(ARGS + 1)
        argv[0] = self.cstr.ptr
        argv[1] = "--ktest_filter=$test".cstr.ptr
        argv[2] = "--ktest_logger=SIMPLE".cstr.ptr
        argv[ARGS] = null
        env.forEach { (name, value) -> setenv(name, value, 1) }
        val pid = fork()
        if (pid == 0) {
            execv(self, argv)
            _exit(EXEC_FAILED)
        }
        env.keys.forEach { unsetenv(it) }
        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        ChildEnd(signal = status.value and SIGNAL_MASK, exit = (status.value shr EXIT_SHIFT) and EXIT_MASK)
    }
}

/** How a child ended: the [signal] that ended it (0: none), else its [exit] code. */
internal data class ChildEnd(val signal: Int, val exit: Int) {
    val passed: Boolean get() = signal == 0 && exit == 0
}

/** The role this process plays in the process-death binding, as its parent named it. */
internal fun childRole(name: String): String? = getenv(name)?.toKString()

private const val ARGS = 3
private const val EXEC_FAILED = 127
private const val SIGNAL_MASK = 0x7f
private const val EXIT_SHIFT = 8
private const val EXIT_MASK = 0xff
