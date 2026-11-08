package app.snapsync.domain.event

// An encrypted event's key (the encrypted file format, `docs/architecture.md`): a secret type (`docs/patterns.md`,
// "Secret types"). Who may read its bytes or make one from bytes is a compile-checked list — each marker's opt-ins are
// pinned (`OptInPinsTest`).

/** Reading an event key's bytes: only its text form, its id's derivation and the cipher may. */
@RequiresOptIn("Only the key's text form, its id and the cipher read key material.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class KeyMaterial

/** Making an event key from bytes: only its text form and the mint may. */
@RequiresOptIn("Only the key's text form and the mint make a key from bytes.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class MakesEventKey

/**
 * An encrypted event's key: [LENGTH] bytes, made on the creating device and carried to every other only inside the
 * event's invite. Never rendered, compared by its bytes.
 */
class EventKey private constructor(private val octets: List<Byte>) {
    /** The key's bytes — a copy. */
    @KeyMaterial
    fun bytes(): ByteArray = octets.toByteArray()

    override fun equals(other: Any?): Boolean = other is EventKey && other.octets == octets

    override fun hashCode(): Int = octets.hashCode()

    override fun toString(): String = "EventKey(***)"

    companion object {
        /** An AES-256 key's length. */
        const val LENGTH: Int = 32

        /** The key [bytes] are, or `null` when they are not exactly [LENGTH] long. */
        @MakesEventKey
        fun of(bytes: ByteArray): EventKey? = bytes.takeIf { it.size == LENGTH }?.let { EventKey(it.toList()) }
    }
}

/** Only the key ids' derivation mints an [IdentifiedKey]. */
@RequiresOptIn("Only the key ids' derivation says which id a key has.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsIdentifiedKey

/** [key] and the id its derivation gives it — evidence: only something that derived [id] from [key] may say so. */
class IdentifiedKey @MintsIdentifiedKey constructor(val key: EventKey, val id: EventKeyId) {
    override fun toString(): String = "IdentifiedKey(${id.value})"
}
