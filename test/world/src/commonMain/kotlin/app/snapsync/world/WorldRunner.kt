package app.snapsync.world

import kotlinx.coroutines.CoroutineScope

/**
 * Runs a world integration test on a **real-time** dispatcher (JVM/native `runBlocking`), NOT
 * `runTest`'s virtual clock. The composed `ExtensionReconciler` wraps its device-listing fetch in
 * `withTimeoutOrNull(30s)`, and the mini-edge `MockEngine` executes on a real background dispatcher — a
 * combination that fires the timeout prematurely under `runTest` (the virtual clock advances to 30s
 * while the real HTTP hop is in flight). Real time avoids that race, so the REAL reconcile → cycle path
 * actually runs. Provided as a `commonMain` helper so BOTH `:test:world`'s own tests and
 * `:test:integration` reuse it; runs on JVM and `iosSimulatorArm64` per testing rule 1.
 */
expect fun worldTest(body: suspend CoroutineScope.() -> Unit)
