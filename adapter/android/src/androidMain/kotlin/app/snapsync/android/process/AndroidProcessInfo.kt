package app.snapsync.android.process

import app.snapsync.model.MemoryFootprint
import app.snapsync.model.Never
import app.snapsync.model.NeverAnswers
import app.snapsync.ports.ProcessInfo

/**
 * The Android [ProcessInfo]. No memory footprint: it exists for MetricKit's reports, and Android composes no
 * process-metric provider for it to ride with — while the nearest Android figure (a PSS read) walks the process's
 * memory maps on every call.
 */
@NeverAnswers(
    Never(member = "memoryFootprint", answer = "returns"),
)
class AndroidProcessInfo : ProcessInfo {
    override fun memoryFootprint(): MemoryFootprint? = null
}
