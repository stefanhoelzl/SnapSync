package app.snapsync.model

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

/**
 * Emit [message] at [severity] — a level a caller holds as a value, without a branch of its own (the
 * `:app:*` complexity gate counts one).
 */
inline fun Logger.logAt(severity: Severity, message: () -> String) = when (severity) {
    Severity.Verbose -> v { message() }
    Severity.Debug -> d { message() }
    Severity.Info -> i { message() }
    Severity.Warn -> w { message() }
    Severity.Error -> e { message() }
    Severity.Assert -> a { message() }
}
