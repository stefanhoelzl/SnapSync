package app.snapsync.contracts

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.selectionPolicyFor

/**
 * What the photo-library contracts share: the fixture a binding seeds, and where each clause seeds it
 * (`docs/architecture.md`, "In-app hosts CI can reach are run live over the rig").
 *
 * A real photo library is **shared by every clause of every contract** in a run, and a clause cannot empty it:
 * deleting an asset raises a system confirmation someone has to tap. So a clause owns an address instead,
 * derived from its id, and reads only there. That address is a one-day **capture-date window**, and each seeded
 * asset is dated at its noon. PhotoKit's own predicate widens every date bound by a day (`predicateFor`), so a
 * read may return a neighbouring clause's assets. That is a superset, which every contract here allows. What no
 * clause may assume is that its window is the only thing in the library.
 *
 * The windows sit in 1980–1999, well before any photo a simulator ships with.
 */
object PhotoLibrary {

    /** A 16×16 JPEG: the smallest ordinary photo a library accepts. Seeded and imported as-is. */
    val jpeg: ByteArray = hex(
        "ffd8ffe000104a46494600010100000100010000ffdb004300100b0c0e0c0a100e0d0e1211101318281a181616183123" +
        "251d283a333d3c3933383740485c4e404457453738506d51575f626768673e4d71797064785c656763ffdb0043011112" +
        "121815182f1a1a2f63423842636363636363636363636363636363636363636363636363636363636363636363636363" +
        "6363636363636363636363636363ffc00011080010001003012200021101031101ffc4001f0000010501010101010100" +
        "000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221" +
        "31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a" +
        "434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899" +
        "9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1" +
        "f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100" +
        "020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015" +
        "6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869" +
        "6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4" +
        "c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f0092" +
        "8a28af18f58fffd9",
    )

    /** Bytes no library can decode, staged under an image type: what an import that must fail is given. */
    val notAnImage: ByteArray = "this is not an image".encodeToByteArray()

    /**
     * The window [clauseId] of [contract] owns. Allocated by position — the contract's place in [contracts],
     * then the clause's place in its contract — so two clauses can never share one, and three days apart so a
     * predicate widened by a day never reaches past a neighbour.
     */
    fun window(contract: String, clauseId: String): CaptureWindow {
        val c = contracts.indexOfFirst { it.name == contract }
        require(c >= 0) { "$contract is not a photo-library contract; add it to PhotoLibrary.contracts" }
        val k = contracts[c].clauses.indexOfFirst { it.id == clauseId }
        require(k in 0 until CLAUSES_PER_CONTRACT) { "$contract has no clause $clauseId, or too many clauses" }
        return CaptureWindow(FIRST_DAY + (c * CLAUSES_PER_CONTRACT + k) * SPACING_DAYS)
    }

    /**
     * The policy a clause reads its window with: floor and ceiling at the window's edges, built through the
     * one derivation production uses. [contributes] false is a membership that shares nothing.
     */
    suspend fun policy(contract: String, clauseId: String, contributes: Boolean = true): SelectionPolicy {
        val window = window(contract, clauseId)
        return selectionPolicyFor(
            includesUpload = contributes,
            cutoff = captureCutoff(window.start),
            ceiling = captureCeiling(window.end),
            suppressedAssetIds = { emptySet() },
            albumExcludedAssetIds = { emptySet() },
        )
    }

    /** Every contract whose clauses seed the shared library, in a fixed order: the order IS the allocation. */
    val contracts: List<Contract<*, *>> by lazy {
        listOf(
            GalleryReaderContract,
            PhotoLibraryImporterContract,
            // Its clauses seed the photos they upload.
            BackgroundTransferContract,
            // Its change clause seeds one photo to move the library's token.
            GalleryContract,
        )
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    /** 1980-01-01, as days since the epoch. */
    private const val FIRST_DAY = 3652

    private const val CLAUSES_PER_CONTRACT = 50

    private const val SPACING_DAYS = 3
}

/**
 * One clause's one-day capture window, as the ISO-8601 instants a policy and a seed use. [epochDay] is days
 * since 1970-01-01.
 */
class CaptureWindow(val epochDay: Int) {
    /** The window's first instant: the capture floor a clause's policy carries. */
    val start: String = "${date(epochDay)}T00:00:00Z"

    /** The first instant after the window: the capture ceiling. */
    val end: String = "${date(epochDay + 1)}T00:00:00Z"

    /** Noon inside the window: the capture date of every asset a clause seeds, and of every import. */
    val seedDate: String = "${date(epochDay)}T12:00:00Z"

    /** Whether [iso] (an ISO-8601 instant, as a library reports it) falls inside the window. */
    operator fun contains(iso: String): Boolean = iso >= start && iso < end

    private companion object {
        /** Days since the epoch to `yyyy-MM-dd` (the proleptic Gregorian civil date). */
        fun date(epochDay: Int): String {
            val z = epochDay + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
        }
    }
}
