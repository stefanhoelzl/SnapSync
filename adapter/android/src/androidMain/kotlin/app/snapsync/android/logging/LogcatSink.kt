package app.snapsync.android.logging

import android.util.Log
import app.snapsync.inbound.LogLevel
import app.snapsync.ports.LogSink

/**
 * The platform log: every line into logcat under one tag, at its own priority, so
 * `adb logcat -s SnapSync` reads the process's whole log. Logcat does not redact, and is read only over a debugging
 * connection; the process's own log file, the one a bug report carries, is [FileLogSink]'s.
 */
class LogcatSink : LogSink {
    override fun write(level: LogLevel, tag: String, line: String) {
        Log.println(priorityOf(level), TAG, line)
    }

    private companion object {
        const val TAG = "SnapSync"

        fun priorityOf(level: LogLevel): Int = when (level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
    }
}
