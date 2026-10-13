package app.snapsync.android.process

import app.snapsync.model.Availability
import app.snapsync.model.MemoryFootprint
import app.snapsync.model.Never
import app.snapsync.model.NeverAnswers
import app.snapsync.ports.ProcessInfo

/**
 * The Android [ProcessInfo]: Android's counterpart of iOS's protected data is the user's credential-encrypted storage,
 * unreadable from boot until the first unlock — and this app never runs before it. No component of it is direct-boot
 * aware, the libraries' included (`AndroidManifest.xml`, held there by `verifyNoDirectBoot`), so a process of it starts
 * only once the storage is readable, and protected data is always available to it.
 *
 * No memory footprint: it exists for MetricKit's reports, and Android composes no process-metric provider for it to
 * ride with — while the nearest Android figure (a PSS read) walks the process's memory maps on every call.
 */
@NeverAnswers(
    Never(member = "protectedDataAvailable", answer = "Availability.UNAVAILABLE"),
    Never(member = "memoryFootprint", answer = "returns"),
)
class AndroidProcessInfo : ProcessInfo {
    override suspend fun protectedDataAvailable(): Availability = Availability.AVAILABLE

    override fun memoryFootprint(): MemoryFootprint? = null
}
