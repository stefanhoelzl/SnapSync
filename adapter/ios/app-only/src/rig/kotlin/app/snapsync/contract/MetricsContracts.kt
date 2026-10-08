@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.Divergence
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.InAppContract
import app.snapsync.contracts.ProcessMetricsContract
import app.snapsync.contracts.ProcessMetricsState
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.logging.documentsDirectory
import app.snapsync.metrics.MetricKitApi
import app.snapsync.metrics.MetricKitProcessMetrics
import app.snapsync.metrics.MetricQueue
import app.snapsync.metrics.SystemMetricKitApi
import app.snapsync.model.CALL_STACK_SEGMENTS
import app.snapsync.ports.ProcessMetrics
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_time

/*
 * MetricKit as TEXT — the subscription and the payloads it hands over — and the entitled device's binding of
 * `ProcessMetricsContract`, recorded by a run that waits for MetricKit's delivery across launches (`docs/testing.md`,
 * "Record and replay").
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (where the device records), and into `iosTest`
 * otherwise (where CI replays) — one file, so the recorder and the replayer cannot spell a call differently.
 */

private const val SUBSCRIBE = "subscribe()"

private fun MetricQueue.render() = "pastPayloads=$pastPayloads pastDiagnosticPayloads=$pastDiagnosticPayloads"

private fun String.parseQueue() = MetricQueue(
    substringAfter("pastPayloads=").substringBefore(' ').toInt(),
    substringAfter("pastDiagnosticPayloads=").toInt(),
)

/**
 * A payload as one line of JSON: maps and lists as they are, every leaf as the text it renders — the whole of what the
 * adapter reads of a leaf — and a call-stack branch, which the adapter drops unread and which runs to megabytes, masked.
 */
private fun json(value: Any?): String = when (value) {
    null -> "null"
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
        quote(k.toString()) + ":" + if (k.toString() in CALL_STACK_SEGMENTS) quote("<masked>") else json(v)
    }
    is List<*> -> value.joinToString(",", "[", "]") { json(it) }
    else -> quote(value.toString())
}

private fun quote(text: String) = buildString {
    append('"')
    text.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u").append(c.code.toString(HEX).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}

private const val HEX = 16

private fun payload(raw: Map<Any?, *>) = "payload(${json(raw)})"

private fun String.parsePayload(): Map<Any?, *> {
    val text = removePrefix("payload(").removeSuffix(")")
    val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding)
        ?: throw Divergence("a recorded payload is not text")
    @Suppress("UNCHECKED_CAST")
    return NSJSONSerialization.JSONObjectWithData(data, 0u, null) as? Map<Any?, *>
        ?: throw Divergence("a recorded payload is not a JSON object: $this")
}

/**
 * Passes the subscription to [real] and records it, with its answer, and every payload delivered after it as an event —
 * a delivery that races the subscription's answer is held until the call is recorded, so the block reads in order.
 */
internal class RecordingMetricKitApi(private val real: MetricKitApi, private val recorder: Recorder) : MetricKitApi {
    override fun subscribe(deliver: (raw: Map<Any?, *>) -> Unit): MetricQueue {
        val lock = NSLock()
        var answered = false
        val held = mutableListOf<Map<Any?, *>>()
        fun hand(raw: Map<Any?, *>) {
            recorder.event(payload(raw))
            deliver(raw)
        }
        val queue = real.subscribe { raw ->
            lock.lock()
            val now = answered
            if (!now) held += raw
            lock.unlock()
            if (now) hand(raw)
        }
        lock.lock()
        recorder.record(SUBSCRIBE, queue.render())
        answered = true
        val early = held.toList()
        lock.unlock()
        early.forEach(::hand)
        return queue
    }
}

/** Answers the subscription from one clause's recorded block and delivers its payloads after it, on another thread. */
internal class ReplayingMetricKitApi(private val replayer: Replayer) : MetricKitApi {
    override fun subscribe(deliver: (raw: Map<Any?, *>) -> Unit): MetricQueue {
        val queue = replayer.answer(SUBSCRIBE).parseQueue()
        val due = replayer.takeEvents().map { it.parsePayload() }
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { due.forEach(deliver) }
        return queue
    }
}

/**
 * MetricKit as the device run met it: subscribed when the run was armed, and holding what it delivered since. A
 * subscription through this answers what MetricKit answered then and hands over what it delivered, in order.
 */
private class DeliveredMetricKitApi(
    private val queue: MetricQueue,
    private val delivered: List<Map<Any?, *>>,
) : MetricKitApi {
    override fun subscribe(deliver: (raw: Map<Any?, *>) -> Unit): MetricQueue {
        dispatch_async(
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
        ) { delivered.forEach(deliver) }
        return queue
    }
}

/** The real [MetricKitProcessMetrics] on a device MetricKit delivered to, recording the subscription and the payloads. */
private class DeviceMetricsBinding(
    private val recorder: Recorder,
    private val metricKit: MetricKitApi,
) : Binding<ProcessMetricsState, ProcessMetrics> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(ProcessMetricsState.PROVIDER_DELIVERS)

    override fun create(state: ProcessMetricsState, clauseId: String): Entered<ProcessMetrics> {
        if (state !in reaches) return Entered.Unreachable("an iOS app has a provider; $state runs on another process")
        recorder.open(clauseId)
        return Entered.Ready(MetricKitProcessMetrics(RecordingMetricKitApi(metricKit, recorder)))
    }
}

// ---- the run, which waits -----------------------------------------------------------------------------------------

private fun armedPath() = documentsDirectory()?.let { "$it/contracts/ProcessMetrics.armed" }

/**
 * The waiting subscription: one per process, started by an arm or — while armed — by every launch, until a delivery is
 * recorded. Held in a field that is read ([MetricKitProcessMetrics]'s warning: a write-only holder went silent).
 */
private object MetricWait {
    private val lock = NSLock()
    private var metricKit: SystemMetricKitApi? = null
    private val delivered = mutableListOf<Map<Any?, *>>()

    fun start() {
        lock.lock()
        val waiting = metricKit != null
        val api = metricKit ?: SystemMetricKitApi().also { metricKit = it }
        lock.unlock()
        if (waiting) return
        var queue: MetricQueue? = null
        queue = api.subscribe { raw ->
            lock.lock()
            val first = delivered.isEmpty()
            delivered += raw
            lock.unlock()
            // A delivery comes in a burst — metric and diagnostic payloads in turn — so the run waits for it to end.
            if (first) {
                dispatch_after(
                    dispatch_time(DISPATCH_TIME_NOW, BURST_SECONDS * NSEC_PER_SEC.toLong()),
                    dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
                ) { record(checkNotNull(queue) { "a delivery before the subscription answered" }) }
            }
        }
    }

    private fun record(queue: MetricQueue) = whileHoldingBackgroundTime("contract.ProcessMetrics") {
        lock.lock()
        val payloads = delivered.toList()
        lock.unlock()
        recordAppOnDevice(
            ProcessMetricsContract,
            null,
        ) { DeviceMetricsBinding(it, DeliveredMetricKitApi(queue, payloads)) }
        armedPath()?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
        Unit
    }

    private const val BURST_SECONDS = 10L
}

/** Called as a rig build's adapter set is built: a run still armed waits again in this process. */
internal fun resumeMetricWait() {
    val armed = armedPath() ?: return
    if (NSFileManager.defaultManager.fileExistsAtPath(armed)) MetricWait.start()
}

/**
 * `POST /contract/ProcessMetrics` on the device app: `?step=arm` subscribes and waits — across launches — for MetricKit's
 * next delivery, about a day away; `?step=collect` answers the recording once one arrived.
 */
internal fun metricsContracts(): List<InAppContract> = listOf(
    InAppContract(ProcessMetricsContract.name, Host.IOS_DEVICE_APP) { params ->
        when (params["step"]) {
            "arm" -> armMetricWait()
            "collect" -> keptRecording(ProcessMetricsContract.name) ?: (
                CONTRACT_REFUSED + "MetricKit has delivered nothing since the arm; it delivers about daily — open the " +
                    "app now and then, and collect again.\n"
                )
            else ->
                CONTRACT_REFUSED +
                    "MetricKit's delivery is recorded in two steps: ?step=arm (the run waits, across launches, for the next " +
                    "delivery — about a day), then ?step=collect.\n"
        }
    },
)

private fun armMetricWait(): String {
    if (onSimulator()) return CONTRACT_REFUSED + "MetricKit delivers only on a device; arm there.\n"
    val armed = armedPath() ?: return CONTRACT_REFUSED + "no Documents directory\n"
    val dir = armed.substringBeforeLast('/')
    NSFileManager.defaultManager.createDirectoryAtPath(
        dir,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    keptPath(ProcessMetricsContract.name)?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    NSString.create(
        string = "armed",
    ).writeToFile(armed, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    MetricWait.start()
    return CONTRACT_REFUSED +
        "armed (not a recording): the run waits for MetricKit's next delivery, about a day away, and keeps waiting " +
        "across launches. Collect with POST /contract/ProcessMetrics?step=collect.\n"
}

/** Every run that spans a launch, resumed as a rig build's adapter set is built. */
fun resumeContractRuns() {
    claimContractSession()
    resumeMetricWait()
}
