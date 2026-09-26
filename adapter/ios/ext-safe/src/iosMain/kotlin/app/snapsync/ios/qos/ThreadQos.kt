@file:OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)

package app.snapsync.ios.qos

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.newFixedThreadPoolContext
import platform.posix.QOS_CLASS_BACKGROUND
import platform.posix.QOS_CLASS_DEFAULT
import platform.posix.QOS_CLASS_UNSPECIFIED
import platform.posix.QOS_CLASS_USER_INITIATED
import platform.posix.QOS_CLASS_USER_INTERACTIVE
import platform.posix.QOS_CLASS_UTILITY
import platform.posix.pthread_set_qos_class_self_np
import platform.posix.qos_class_self
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The QoS class of the **calling thread**, as a short label for a log line (capability `privacy-security`):
 * `UX` user-interactive · `UI` user-initiated · `DEF` default · `UT` utility · `BG` background · `UNSPEC`
 * unspecified; anything else prints its raw value.
 *
 * Why a log line needs it: a PhotoKit call is an XPC round-trip into `assetsd`/`photolibraryd`, and the calling
 * thread's QoS **propagates** over it — measured on an SE2, commits made at `QOS_CLASS_BACKGROUND` took ~400 ms
 * against ~60 ms at a foreground class (6–7×). A slow read is otherwise indistinguishable from a busy daemon.
 */
fun qosLabel(qos: UInt = qos_class_self()): String = QOS_LABELS[qos] ?: "qos($qos)"

private val QOS_LABELS = mapOf(
    QOS_CLASS_USER_INTERACTIVE to "UX",
    QOS_CLASS_USER_INITIATED to "UI",
    QOS_CLASS_DEFAULT to "DEF",
    QOS_CLASS_UTILITY to "UT",
    QOS_CLASS_BACKGROUND to "BG",
    QOS_CLASS_UNSPECIFIED to "UNSPEC",
)

/**
 * A **single-thread** dispatcher whose thread is pinned to `QOS_CLASS_USER_INITIATED` at start, so every
 * blocking platform call it carries is issued — and propagated over XPC — at a foreground class rather than at
 * whatever class the thread happened to inherit.
 *
 * The pin is the first task ever dispatched to the lane: a one-thread pool runs its queue in order, so nothing
 * can run on the thread before it. It is never restored — the thread belongs to this lane alone, which is why a
 * dedicated lane rather than a pin inside a shared pool's worker (a `Dispatchers.Default` thread left at
 * USER_INITIATED would carry that class into unrelated work, and its prior class cannot always be restored:
 * `UNSPECIFIED` is not a settable class).
 *
 * What it does NOT do: lift the kernel's `darwinbg` clamp on a process the OS runs in the background. That role
 * caps the whole process; the pin decides only the class requested *within* it, which is the part PhotoKit
 * propagates to the daemon.
 *
 * `@DelicateCoroutinesApi` flags a context that is never closed. That is the requirement: each lane lives as long
 * as the process.
 */
fun newUserInitiatedLane(name: String, log: Logger = Logger.withTag("qos")): CoroutineDispatcher {
    val lane = newFixedThreadPoolContext(nThreads = 1, name = name)
    lane.dispatch(
        EmptyCoroutineContext,
        Runnable {
            val before = qosLabel()
            val rc = pthread_set_qos_class_self_np(QOS_CLASS_USER_INITIATED, 0)
            log.i { "qos: lane '$name' pinned to USER_INITIATED (was $before, now ${qosLabel()}, rc=$rc)" }
        },
    )
    return lane
}

/**
 * The lane the PhotoKit **reads** hop to (`IosGalleryReader`, the selection snapshot, the change token): one thread, pinned at
 * USER_INITIATED — in whichever process links it (the app, and the upload extension, whose own scope runs on
 * `Dispatchers.Default` threads of the class the OS launched it at).
 *
 * The hop's meaning is unchanged (`docs/architecture.md`, "Dispatcher lanes are fixed by the composition"):
 * **throughput**, so a synchronous `assetsd` round-trip does not hold the serial composition lane. What moved is
 * only which thread takes it — its own, at a known QoS, instead of a `Dispatchers.Default` worker at an
 * inherited one. One thread, deliberately: each caller's reads are sequential already, so what one thread costs
 * is only that two callers' reads (say, the status walk and a cycle's walk in the app) queue rather than overlap
 * — PhotoKit's own daemon is where they would meet anyway — and a wedged `assetsd` now parks this thread instead
 * of a worker of the pool presentation state reduces on.
 */
val photoKitReadLane: CoroutineDispatcher by lazy { newUserInitiatedLane("snapsync-photokit") }
