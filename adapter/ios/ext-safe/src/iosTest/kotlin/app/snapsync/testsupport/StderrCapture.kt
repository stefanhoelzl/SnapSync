@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.testsupport

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.STDERR_FILENO
import platform.posix.close
import platform.posix.dup
import platform.posix.dup2
import platform.posix.fflush
import platform.posix.open
import platform.posix.stderr

/** This process's stderr, redirected into [path] until [restore]: what a channel writing there wrote meanwhile. */
internal class StderrCapture(private val path: String) {
    private val saved = dup(STDERR_FILENO)

    init {
        val file = open(path, O_WRONLY or O_CREAT or O_TRUNC, 0x1A4)
        check(file >= 0) { "cannot open $path" }
        fflush(stderr)
        dup2(file, STDERR_FILENO)
        close(file)
    }

    fun read(): String {
        fflush(stderr)
        return readTextFile(path).orEmpty()
    }

    fun restore() {
        fflush(stderr)
        dup2(saved, STDERR_FILENO)
        close(saved)
    }
}
