package app.snapsync.sync

/**
 * One key's durable upload memory. The ledger is the engine's only state: per-resource entries
 * keyed by [Resource.filename], holding the last recorded lifecycle [state], the [attempt] it
 * belongs to, and the content-identity [version] it was recorded for.
 */
class LedgerEntry(
    val key: String,
    val state: LedgerState,
    val attempt: Int,
    val version: String,
) {
    override fun equals(other: Any?): Boolean = other is LedgerEntry &&
        key == other.key && state == other.state && attempt == other.attempt && version == other.version

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "LedgerEntry($key, $state, attempt=$attempt, version=$version)"
}

enum class LedgerState {
    /** Work was answered for this key — a hope; the engine cannot prove it was executed. */
    REQUESTED,

    /** The platform observed and reported a successful upload — a fact about the world. */
    COMPLETED,

    /** The platform reported a failed attempt; a retry was answered alongside. */
    FAILED,
}

/**
 * The ledger's storage seam — a dumb row store. Backends store entries verbatim: no
 * interpretation, no precedence, last write wins. [put] is a single-row upsert and the unit of
 * atomicity; record semantics live above, in [LedgerWriter], written once for every backend.
 */
interface LedgerBackend {
    suspend fun get(key: String): LedgerEntry?
    suspend fun put(entry: LedgerEntry)
}

/**
 * Read-only face of the ledger. Handing a [LedgerWriter] out typed as [LedgerReader] is the
 * narrowing — type safety against accidental writes, by construction: only the composition root
 * that owns the engine ever constructs the writer.
 */
open class LedgerReader(protected val backend: LedgerBackend) {
    suspend fun entry(key: String): LedgerEntry? = backend.get(key)
}

/**
 * The ledger's single writer (one per platform, hosted with the engine). Each record operation
 * upserts a complete, self-contained entry in one backend [LedgerBackend.put] — no operation
 * depends on a prior read, so recording is idempotent per key: applying the same record twice
 * converges to the same entry.
 */
class LedgerWriter(backend: LedgerBackend) : LedgerReader(backend) {

    suspend fun recordRequested(key: String, attempt: Int, version: String) =
        backend.put(LedgerEntry(key, LedgerState.REQUESTED, attempt, version))

    suspend fun recordCompleted(key: String, attempt: Int, version: String) =
        backend.put(LedgerEntry(key, LedgerState.COMPLETED, attempt, version))

    suspend fun recordFailed(key: String, attempt: Int, version: String) =
        backend.put(LedgerEntry(key, LedgerState.FAILED, attempt, version))
}
