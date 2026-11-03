package app.snapsync.ports

/**
 * What the platform answered a registration write — the [ExtensionRegistry] port's raw answer, which the core
 * classifies. The port decides nothing: it reports whether the write took and, if not, which error, or that this
 * platform has no such registration at all.
 */
sealed interface RegistrationAnswer {
    /** The platform answered the write: whether it took ([ok]) and, if not, its error [domain] and [code]. */
    data class Answered(val ok: Boolean, val domain: String?, val code: Long?) : RegistrationAnswer

    /**
     * This platform has no upload-extension registration — iOS below 26.1 (whose selector does not exist), the JVM,
     * Android. Nothing was asked of the operating system.
     */
    data object Unsupported : RegistrationAnswer
}
