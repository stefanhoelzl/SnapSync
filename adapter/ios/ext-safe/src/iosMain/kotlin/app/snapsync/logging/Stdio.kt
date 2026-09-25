@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.logging

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.fcntl

/**
 * Make this process's stdout and stderr **non-blocking**, so that writing a log line can never park the
 * thread that writes it (capability `privacy-security`). Each composition root calls it first, before the
 * first line is logged.
 *
 * **Why.** [PublicNSLogWriter] calls `NSLog`. `NSLog` sends the line to the unified log, and ALSO to stderr
 * whenever stderr is a pipe, a tty or a file (CoreFoundation's `also_do_stderr`). It does that with one
 * `writev` inside a process-wide CoreFoundation lock. Ktor's server logger also `println`s to stdout. A
 * process launched by SpringBoard has both on `/dev/null`, so nothing blocks there. A process launched by
 * Xcode or by DVT's process control (`pymobiledevice3 developer dvt launch`, which every device harness in
 * this repo uses) has both on a pipe that `DTServiceHub` reads on the host's behalf. When that reader stops
 * draining, the pipe fills (about 8 KiB), and the next `writev` blocks forever while holding the lock. Every
 * other thread that logs then queues behind it. The main thread, the rig's HTTP handlers and the cycle all
 * log, so the whole app looks deadlocked. It keeps no error and no crash, and the log simply stops mid-flow.
 *
 * Measured (2026-09-23/25, SE2, iOS 26.6.2). Five consecutive `dvt launch`es of three different builds each
 * went silent after 7.76 to 7.99 KB of log output. Each stopped at a different code point (MainViewController,
 * onSceneActive, onPushToken, reconcile), which a code deadlock would not do. At the time `DTServiceHub`'s
 * workqueue sat at its thread limit (71 to 73 blocked workers, wqState 17). The 2026-09-25 Camera-photo hang
 * stopped about 8 KB after the app returned to the foreground, too. The same wedge reproduces on a simulator
 * launched with `simctl launch --stdout=<fifo> --stderr=<fifo>` once the FIFO's reader is stopped: `sample`
 * shows one thread in `writev` under `__CFLogCString`, and every other logging thread waits on CoreFoundation's
 * lock in the same function. The rig stops answering, and it recovers the moment the reader resumes. With this
 * call in place the same run stays healthy through 15 foregrounds.
 *
 * **What it costs.** With `O_NONBLOCK`, a write to a full pipe returns `EAGAIN` instead of waiting, and
 * `NSLog`'s stderr copy of that line is dropped. The unified-log copy and the file log ([FileLogWriter], the
 * canonical channel) are unaffected. `/dev/null` never fills, so a shipped process loses nothing.
 */
fun neverBlockOnStdio() {
    makeNonBlocking(STDOUT_FILENO)
    makeNonBlocking(STDERR_FILENO)
}

/** Set `O_NONBLOCK` on [fd]'s open file description; `false` when the descriptor could not be read or set. */
internal fun makeNonBlocking(fd: Int): Boolean {
    val flags = fcntl(fd, F_GETFL)
    if (flags < 0) return false
    return flags and O_NONBLOCK != 0 || fcntl(fd, F_SETFL, flags or O_NONBLOCK) == 0
}
