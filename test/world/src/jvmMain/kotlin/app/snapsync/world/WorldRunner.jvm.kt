package app.snapsync.world

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

// The receiver is a CANCELLED-AT-EXIT child scope (migration step 10): the world composes the real
// `AppCore` on this scope, whose status collectors are infinite — on the bare `runBlocking` scope
// they would keep the test alive forever. Cancelling the child on the way out is exactly what the
// desktop harness's window teardown does to its scope.
actual fun worldTest(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
    val scope = CoroutineScope(coroutineContext + Job())
    try {
        scope.body()
    } finally {
        scope.cancel()
    }
}
