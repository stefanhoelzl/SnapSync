package app.snapsync.model

/**
 * The path of an absolute `http(s)` URL — everything from the `/` that ends the authority up to a `?` or
 * `#`, or `"/"` when there is none.
 *
 * Pure string work, so the ledger can record where an upload was addressed without a platform URL type. The PATH rather
 * than the whole URL: it is what the platform must preserve to perform the request at all, and it is unaffected by any
 * handling of the query.
 *
 * Percent-encoding is left exactly as composed. That is safe for what this addresses because a
 * canonical [AssetId] and a role token contain only unreserved characters, so the encoded and decoded
 * spellings coincide — and the tier that compares it keeps a fallback for any row where they would not.
 */
fun destinationPathOf(url: String): String {
    val afterScheme = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
    val pathStart = url.indexOf('/', afterScheme)
    if (pathStart < 0) return "/"
    val end = url.indexOfFirst(pathStart) { it == '?' || it == '#' }
    return url.substring(pathStart, end)
}

private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
    for (i in from until length) if (predicate(this[i])) return i
    return length
}
