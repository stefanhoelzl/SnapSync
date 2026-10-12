package app.snapsync.contracts

import app.snapsync.ports.PlatformAssetId

/**
 * A photo-library port as a binding hands it to a clause: the [port], plus the assets the binding seeded for
 * that clause. The seed is **input**, not an observation. The library mints its own identifiers, so the
 * binding reports what it created rather than the contract choosing ids, as `SecureStoreContract.seedValue`
 * does.
 *
 * [ids] are the platform's ids of what the binding created, as the platform itself reports them.
 */
class SeededLibrary<T>(
    val port: T,
    val ids: Set<PlatformAssetId> = emptySet(),
    /** A platform path the clause may write [name] to — and what such a path holds now, `null` for nothing. */
    val files: ClauseFiles? = null,
)

/** Where a clause may have the platform write a file, and what it reads back there. */
class ClauseFiles(val pathOf: (name: String) -> String, val read: (path: String) -> ByteArray?)

/** How many assets a `*_SEEDED` state puts in a clause's window. */
const val SEED_COUNT: Int = 2

/**
 * An asset id no library holds: a well-formed PhotoKit `localIdentifier` with a UUID derived from the clause id, so it
 * is deterministic and never collides with an asset the library minted.
 */
fun absentAssetId(clauseId: String): PlatformAssetId {
    val digits = clauseId.encodeToByteArray().joinToString("") { (it.toInt() and 0xF).toString(16) }
        .padEnd(12, '0').take(12)
    return PlatformAssetId("00000000-0000-4000-8000-$digits/L0/001")
}
