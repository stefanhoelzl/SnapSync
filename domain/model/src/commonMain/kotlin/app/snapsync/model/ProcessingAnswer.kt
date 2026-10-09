package app.snapsync.model

/**
 * What the operating system is told an invocation came to — iOS 26.1's `PHBackgroundResourceUploadProcessingResult`,
 * case for case. The platform's vocabulary: which outcome of the core's work each answer stands for is the
 * composition's decision, not the adapter's.
 */
enum class ProcessingAnswer {
    /** Nothing more to do now; the system may rest. */
    COMPLETED,

    /** Work remains; the system should invoke again. */
    PROCESSING,

    /** The invocation failed. */
    FAILED,
}
