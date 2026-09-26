package app.snapsync.compose

import app.snapsync.feature.upload.UploadCycle
import app.snapsync.model.CycleResult
import app.snapsync.ports.Backend
import app.snapsync.ports.DeviceIdentity
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.ExtensionHost
import app.snapsync.ports.EntryContext
import app.snapsync.ports.invocation
import app.snapsync.services.upload.runProcessCycle
import app.snapsync.services.backend.BackendServices
import app.snapsync.services.backend.CredentialedBackend
import app.snapsync.services.trust.CachedAttestStore
import app.snapsync.services.trust.ExtensionCredential

/**
 * The upload extension's composition of its one entry port (`docs/architecture.md`, "Events arrive through
 * `listen`"): the [ExtensionHost]'s handlers over the cycle [uploadCore] built from the same [ports], registered on
 * [host] — here, because the extension has no host zone.
 *
 * [ports] and [cycle] are providers, resolved per invocation, so the root can register at its own initialization
 * without building the cycle before the operating system asks for one.
 */
fun snapSyncExtension(
    host: ExtensionHost,
    ports: () -> UploadPorts,
    cycle: () -> UploadCycle,
    entryContext: EntryContext = EntryContext.NoOp,
    /**
     * Drop this process's in-memory copy of the device token, so the invocation reads the one the app last
     * stored (capability `privacy-security`). The app renews into the shared Keychain item, which the
     * extension's copy cannot see; re-reading at every OS invocation bounds that copy's staleness to one.
     */
    rereadCredential: () -> Unit = {},
) {
    host.listen(extensionHandlers(ports, cycle, entryContext, rereadCredential))
}

/** The extension's handlers: one upload cycle per invocation, and the invocation's end recorded. */
internal fun extensionHandlers(
    ports: () -> UploadPorts,
    cycle: () -> UploadCycle,
    entryContext: EntryContext,
    rereadCredential: () -> Unit,
): ExtensionHandlers = ExtensionHandlers(
    // The cycle, the pending → PROCESSING requeue and the never-throw guard around both are `runProcessCycle`: a
    // throwable escaping here would cross the ObjC boundary and abort the extension process.
    onProcess = {
        val log = ports().log
        log.invocation(entryContext, "process", result = { "$it" }) {
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
    },
    // The OS's `notifyTermination` marks the END of an invocation, not a kill (see [ExtensionHandlers.onTerminate]).
    // So this records an ordinary end at `Info`. A KILLED call is the one that reads as a `→ process` with no
    // `← process` and no line from here — which is how to tell the two apart.
    onTerminate = {
        val log = ports().log
        log.invocation(entryContext, "onTerminate") {
            log.i {
                "the OS ended this invocation — notifyTermination follows a normal return; a killed call gets none"
            }
        }
    },
)

/**
 * The upload extension's backend services (capability `privacy-security`): the same need-shaped services the app
 * composes, over an authenticated backend whose credential only DROPS a rejected token ([ExtensionCredential]) —
 * the extension cannot attest, so it never retries a rejected call — and with no version gate, because the
 * extension has no screen to show a refusal on.
 *
 * [attestStore] is the root's one in-memory copy of the shared token, the one it re-reads at every invocation.
 */
fun extensionBackend(backend: Backend, attestStore: CachedAttestStore, identity: DeviceIdentity): BackendServices =
    BackendServices(CredentialedBackend(backend, ExtensionCredential(attestStore), versionGate = null), identity)
