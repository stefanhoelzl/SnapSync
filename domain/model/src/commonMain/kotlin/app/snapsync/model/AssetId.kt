package app.snapsync.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A photo's identity in its **canonical** form — the one form every part of the system speaks: the core
 * stores, compares and hands it back without ever parsing it, the ledger and the download store key on it,
 * the manifest carries it, and the backend validates and stores it as it arrives (no translation on either
 * side of the wire).
 *
 * **The rule** ([isCanonicalAssetId]): non-empty, only RFC 3986 *unreserved* characters (`A–Z a–z 0–9 - . _ ~`),
 * and never `..`. That makes it a single URL path segment the backend's `validateFilename` accepts
 * (`api/src/validators.ts`) with an encoded spelling identical to the decoded one (the ledger's recorded
 * `destinationPath` relies on that), and free of the `\n` the download task tag joins its fields on.
 * Construction enforces it, so a non-canonical id is unrepresentable.
 *
 * **One reversible encoding mints it from the platform's own id**, in the gallery services — the core finds the
 * asset again by decoding it. PhotoKit's `localIdentifier` (`{UUID}/L0/NNN`) becomes `{UUID}_L0_NNN`, and a decimal
 * media-store id is itself.
 *
 * [toString] is the canonical string itself, so an id interpolates into a key or a log line unchanged.
 */
@Serializable
@JvmInline
value class AssetId(val value: String) : Comparable<AssetId> {
    init {
        require(isCanonicalAssetId(value)) { "not a canonical asset id: '$value'" }
    }

    override fun compareTo(other: AssetId): Int = value.compareTo(other.value)

    override fun toString(): String = value
}

/** Whether [value] obeys the canonical asset-id rule (see [AssetId]). */
fun isCanonicalAssetId(value: String): Boolean =
    value.isNotEmpty() && ".." !in value && value.all { it in UNRESERVED_EXTRA || it.isAsciiLetterOrDigit() }

private const val UNRESERVED_EXTRA = "-._~"

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
