package app.snapsync.compose

import app.snapsync.feature.upload.UploadCycle
import app.snapsync.ports.CycleResult
import app.snapsync.ports.ExtensionEntries
import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import app.snapsync.ports.runProcessCycle

/**
 * The core's implementation of the upload extension's inbound port (spec `module-architecture`, "OS entry points
 * cross an inbound port") over the cycle [uploadCore] built from the same [ports].
 *
 * Both are providers, resolved per call, so the root can delegate from its own initializer without building the
 * cycle before the operating system asks for one.
 *
 * The extension root implements [ExtensionEntries] by delegating to this. What stays in the root is what only the
 * root can do: block its thread on [ExtensionEntries.process] (the operating system invokes it synchronously and the
 * process does not outlive it) and hand Swift the platform's raw value for the result.
 */
fun extensionEntries(
    ports: () -> UploadPorts,
    cycle: () -> UploadCycle,
    logScope: LogScope = LogScope.NoOp,
): ExtensionEntries =
    object : ExtensionEntries {
        private val log get() = ports().log

        // The cycle, the pending → PROCESSING requeue and the never-throw guard around both are `runProcessCycle`
        // (`ports/`, tested beside the raw-value mapping): a throwable escaping here would cross the ObjC boundary
        // and abort the extension process.
        override suspend fun process(): CycleResult = log.invocation(logScope, "process", result = { "$it" }) {
            runProcessCycle(
                run = { cycle().run() },
                pending = { ports().ledger.aggregates().pending },
                onCycleFinished = { log.i { "process: cycle finished — $it" } },
                onCycleFailed = { log.e(it) { "process cycle failed" } },
                onRequeue = { open -> log.i { "process: $open pending — requesting re-invocation" } },
                onLateFailure = { log.e(it) { "process failed after the cycle — reporting FAILED" } },
            )
        }

        // Nothing to interrupt or persist — `process()` holds its thread until the cycle ends — but a termination
        // must still be RECORDED: without this line a killed cycle reads as a `→ process` with no `← process`,
        // indistinguishable from a hang (capability `diagnostic-logging`).
        override fun onTerminate() = log.invocation(logScope, "onTerminate") {
            log.w { "the OS terminated this cycle — nothing in flight to persist (process() is synchronous)" }
        }
    }
