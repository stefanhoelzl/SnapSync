package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **No transport adapter holds the ledger store** (`docs/architecture.md`, "The transport-ledger
 * gate"; law: `photo-sharing`, "Reader and writer capability split").
 *
 * A transport — an implementation of an upload port (`OsUploadQueue`, `TransferSession`), or the target-bound
 * `uploadJobQueue` factory that picks one — holds no ledger at all; the data layer's upload tiers hold the rows (`JobRecord`: the guarded terminal writes and the one destination
 * lookup). Every other row read and write, including the decision which in-flight rows a transport has lost,
 * is the data layer's and its use cases'.
 *
 * **Why a text gate, when a type already narrows it.** Neither the compiler nor the module graph can withhold
 * the upload rows from a transport: they are declared in `:domain:data`, which `:adapter:ios:ext-safe` depends on for
 * its database adapter, so a transport there could name them.
 * Narrowing the constructor makes the boundary visible where a reader looks; only this stops the next edit from
 * widening it back.
 *
 * The scope is **derived from the source**, not listed: a new transport is covered the moment it declares the
 * supertype. Comments are stripped, so a transport's KDoc may still explain why it holds no ledger store.
 *
 * Decision record: `changes/transport-only-seam` (D5, D10).
 */
class TransportLedgerGateTest {

    /** A class implementing the seam, or the factory that binds one. */
    private val transportDeclaration =
        Regex("""\)\s*:\s*(?:OsUploadQueue|TransferSession)\s*\{|\bfun\s+uploadJobQueue\s*\(""")

    /**
     * The ledger, in either width: since the transfer re-cut (11f) a transport is a thin upload port and records
     * nothing — the data layer's tiers hold the upload rows (`OwnUploadRows`, `RowTransitions`, `JobRecord`).
     */
    private val ledgerStore = Regex("""\b(?:OwnUploadRows|RowTransitions|JobRecord|WalkRecord|UploadRowsReset)\b""")

    /** The file's **code**, with KDoc and comments stripped — the same reading `UploadJobSubsystemBindingTest` uses. */
    private fun codeOf(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    /** Production adapter sources declaring a transport; test source sets are out of scope. */
    private fun transports(): List<SourceScan.Source> = SourceScan.kotlinFiles()
        .filter { it.path.startsWith("/adapter/") && "/src/" in it.path }
        .filterNot { it.path.substringAfter("/src/").substringBefore("/").endsWith("Test") }
        .filter { transportDeclaration.containsMatchIn(codeOf(it.text)) }

    @Test
    fun `no transport adapter references the upload rows`() {
        val scope = transports()
        assertTrue(
            scope.isNotEmpty(),
            "the transport scan matched no adapter source — the seam or the adapter layout moved, and this gate " +
                "would pass while reading nothing",
        )
        val offenders = scope.filter { ledgerStore.containsMatchIn(codeOf(it.text)) }.map { it.path }
        assertTrue(
            offenders.isEmpty(),
            "these transport adapters reference the ledger:\n  ${offenders.joinToString("\n  ")}\n" +
                "A transport is a thin upload port and records nothing: the data layer's tiers hold the upload rows " +
                "and write through it (phase 11f). See `photo-sharing`, \"Reader and writer capability split\".",
        )
    }

    /**
     * Vacuity check on the recognition itself: the transports that exist today must be in scope, so a regex
     * that stopped matching cannot empty the gate down to whatever happens to still match.
     */
    @Test
    fun `the known transports are recognised`() {
        val paths = transports().map { it.path }
        for (known in listOf("/IosUrlSessionUploadPlatform.kt", "/IosPhotoKitUploadPlatform.kt", "/AndroidUpload.kt")) {
            assertTrue(
                paths.any { it.endsWith(known) },
                "$known is no longer recognised as a transport by this gate's scan; scope was:\n  " +
                    paths.joinToString("\n  "),
            )
        }
    }
}
