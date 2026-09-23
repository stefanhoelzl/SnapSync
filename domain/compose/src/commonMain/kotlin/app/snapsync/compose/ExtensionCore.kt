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
    /**
     * Drop this process's in-memory copy of the device token, so the invocation reads the one the app last
     * stored (capability `device-attestation`). The app renews into the shared Keychain item, which the
     * extension's copy cannot see; re-reading at every OS invocation bounds that copy's staleness to one.
     */
    rereadCredential: () -> Unit = {},
): ExtensionEntries =
    object : ExtensionEntries {
        private val log get() = ports().log

        // The cycle, the pending → PROCESSING requeue and the never-throw guard around both are `runProcessCycle`
        // (`ports/`, tested beside the raw-value mapping): a throwable escaping here would cross the ObjC boundary
        // and abort the extension process.
        override suspend fun process(): CycleResult = log.invocation(logScope, "process", result = { "$it" }) {
            runProcessCycle(
                // Inside the guarded run, so nothing the re-read could raise escapes across the ObjC boundary.
                run = {
                    rereadCredential()
                    cycle().run()
                },
                pending = { ports().ledger.aggregates().pending },
                onCycleFinished = { log.i { "process: cycle finished — $it" } },
                onCycleFailed = { log.e(it) { "process cycle failed" } },
                onRequeue = { open -> log.i { "process: $open pending — requesting re-invocation" } },
                onLateFailure = { log.e(it) { "process failed after the cycle — reporting FAILED" } },
            )
        }

        // The OS's `notifyTermination` marks the END of an invocation, not a kill: measured on an SE2 (iOS 26.6,
        // 2026-09-23), it arrives ~55 ms after every normal return of `process()`, and a call killed at its ~60 s
        // budget receives nothing (capability `ios-photokit-upload`, "How the operating system invokes the extension
        // is recorded as measured"). So this records an ordinary end at `Info`. A KILLED call is the one that reads
        // as a `→ process` with no `← process` and no line from here — which is how to tell the two apart.
        override fun onTerminate() = log.invocation(logScope, "onTerminate") {
            log.i {
                "the OS ended this invocation — notifyTermination follows a normal return; a killed call gets none"
            }
        }
    }
