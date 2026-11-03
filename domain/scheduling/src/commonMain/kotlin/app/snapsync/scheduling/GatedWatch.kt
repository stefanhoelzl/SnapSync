package app.snapsync.scheduling

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * One watch the entry points start and stop — the network while the app is in front: a **latest-wins** collection of
 * a cold flow, each value applied once the wait it names has passed. A newer value cancels a pending wait, so a value
 * that did not last is never applied. Stopping cancels the collection, which stops whatever the flow runs (a platform
 * monitor). It knows nothing of what it watches: the wait and what applying means are the caller's.
 */
class GatedWatch(private val runtime: Runtime) {
    @Volatile
    private var job: Job? = null

    /** Begin watching [values]; a no-op while a previous start's watch is still running. */
    fun <T> start(values: Flow<T>, wait: (T) -> Duration, apply: (T) -> Unit) {
        if (job?.isActive == true) return
        job = runtime.scope.launch {
            values.collectLatest { value ->
                delay(wait(value))
                apply(value)
            }
        }
    }

    /** Stop watching. Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
    }
}
