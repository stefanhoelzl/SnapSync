package app.snapsync.contracts

import app.snapsync.model.normalizeAssetId

/**
 * A photo-library port as a binding hands it to a clause: the [port], plus the assets the binding seeded for
 * that clause. The seed is **input**, not an observation. The library mints its own identifiers, so the
 * binding reports what it created rather than the contract choosing ids, as `SecureStoreContract.seedValue`
 * does.
 *
 * [rawIds] are the library's own identifiers (a PhotoKit `localIdentifier` still carries `/`); [ids] are the
 * normalized form every port answers in.
 */
class SeededLibrary<T>(val port: T, val rawIds: List<String> = emptyList()) {
    val ids: Set<String> = rawIds.mapTo(linkedSetOf(), ::normalizeAssetId)
}

/** How many assets a `*_SEEDED` state puts in a clause's window. */
const val SEED_COUNT: Int = 2

/**
 * A normalized asset id no library holds: a well-formed `localIdentifier` with a UUID derived from the clause
 * id, so it is deterministic and never collides with an asset the library minted.
 */
fun absentAssetId(clauseId: String): String {
    val digits = clauseId.encodeToByteArray().joinToString("") { (it.toInt() and 0xF).toString(16) }
        .padEnd(12, '0').take(12)
    return "00000000-0000-4000-8000-${digits}_L0_001"
}
