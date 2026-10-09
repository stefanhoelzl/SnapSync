package app.snapsync.android.logging

import android.util.Log
import app.snapsync.ports.LogSink
import co.touchlab.kermit.Severity

/**
 * The platform log: every line into logcat under one tag, at its own priority, so
 * `adb logcat -s SnapSync` reads the process's whole log. Logcat does not redact, and is read only over a debugging
 * connection; the process's own log file, the one a bug report carries, is [FileLogSink]'s.
 */
class LogcatSink : LogSink {
    override fun write(severity: Severity, tag: String, line: String) {
        Log.println(priorityOf(severity), TAG, line)
    }

    private companion object {
        const val TAG = "SnapSync"

        fun priorityOf(severity: Severity): Int = when (severity) {
            Severity.Verbose -> Log.VERBOSE
            Severity.Debug -> Log.DEBUG
            Severity.Info -> Log.INFO
            Severity.Warn -> Log.WARN
            Severity.Error -> Log.ERROR
            Severity.Assert -> Log.ASSERT
        }
    }
}
