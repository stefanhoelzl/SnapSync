package app.snapsync.feature.upload

import app.snapsync.model.CycleResult
import app.snapsync.model.ProcessingAnswer

/**
 * What the operating system is told a cycle came to (capability `background-upload`) — a decision about the cycle,
 * so the upload feature's, not the adapter's. [CycleResult.SKIPPED] answers like [CycleResult.COMPLETED]: this device
 * contributes nothing, so the system may rest. [CycleResult.Paused] answers like [CycleResult.PROCESSING]: the cycle
 * touched nothing and asks to be invoked again, until the process it waits for (the app, migrating the download store)
 * has run.
 */
fun CycleResult.answer(): ProcessingAnswer = when (this) {
    CycleResult.COMPLETED, CycleResult.SKIPPED -> ProcessingAnswer.COMPLETED
    CycleResult.PROCESSING, is CycleResult.Paused -> ProcessingAnswer.PROCESSING
    CycleResult.FAILED -> ProcessingAnswer.FAILED
}
