package app.snapsync.model

/**
 * One key's durable upload memory. The ledger is the engine's only state: per-resource entries
 * keyed by [Resource.filename], holding the [assetId] the resource belongs to (an opaque grouping
 * id, several resources of one photo share it), the last recorded lifecycle [state], and the
 * [attempt] it belongs to. An uploaded resource is immutable, so a `COMPLETED` entry's mere
 * existence is the proof of upload; there is no content version, and the ledger keeps no timestamp.
 */
class LedgerEntry(
    val key: String,
    val assetId: String,
    val state: LedgerState,
    val attempt: Int,
) {
    override fun equals(other: Any?): Boolean = other is LedgerEntry &&
        key == other.key && assetId == other.assetId && state == other.state &&
        attempt == other.attempt

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String =
        "LedgerEntry($key, assetId=$assetId, $state, attempt=$attempt)"
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
 * The ledger's lifetime truth in one snapshot-consistent read, counted by **photo (assetId), not
 * resource row**: [pending] = photos with any non-`COMPLETED` resource, [completed] = photos whose
 * resources are all `COMPLETED`.
 */
class LedgerAggregates(
    val pending: Int,
    val completed: Int,
) {
    override fun equals(other: Any?): Boolean = other is LedgerAggregates &&
        pending == other.pending && completed == other.completed

    override fun hashCode(): Int = 31 * pending + completed

    override fun toString(): String =
        "LedgerAggregates(pending=$pending, completed=$completed)"
}

/**
 * One outstanding resource: the [assetId] (photo) a non-`COMPLETED` [key] belongs to. The backlog
 * read returns these so a status projection can group outstanding resources by photo; the backend
 * never interprets them (it just reports the rows whose state is not `COMPLETED`).
 */
class PendingResource(val assetId: String, val key: String) {
    override fun equals(other: Any?): Boolean =
        other is PendingResource && assetId == other.assetId && key == other.key

    override fun hashCode(): Int = 31 * assetId.hashCode() + key.hashCode()

    override fun toString(): String = "PendingResource(assetId=$assetId, key=$key)"
}
