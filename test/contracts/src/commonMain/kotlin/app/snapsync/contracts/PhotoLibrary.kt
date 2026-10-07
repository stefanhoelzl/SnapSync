package app.snapsync.contracts

import app.snapsync.model.SelectionPolicy
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.motionPhotoStill
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

    /**
     * A 64×48, two-frame H.264 MP4 (the Android import test's `android.mp4`): the video a motion photo carries in the
     * `LivePhotoImport` contract. Real enough for AVFoundation to read and pass through.
     */
    val mp4: ByteArray = hex(
        "000000206674797069736f6d0000020069736f6d69736f32617663316d7034310000000866726565000004356d646174" +
            "000002ae0605ffffaadc45e9bde6d948b7962cd820d923eeef78323634202d20636f7265203136342072333139312034" +
            "363133616333202d20482e3236342f4d5045472d342041564320636f646563202d20436f70796c65667420323030332d" +
            "32303234202d20687474703a2f2f7777772e766964656f6c616e2e6f72672f783236342e68746d6c202d206f7074696f" +
            "6e733a2063616261633d31207265663d33206465626c6f636b3d313a303a3020616e616c7973653d3078333a30783131" +
            "33206d653d686578207375626d653d37207073793d31207073795f72643d312e30303a302e3030206d697865645f7265" +
            "663d31206d655f72616e67653d3136206368726f6d615f6d653d31207472656c6c69733d31203878386463743d312063" +
            "716d3d3020646561647a6f6e653d32312c313120666173745f70736b69703d31206368726f6d615f71705f6f66667365" +
            "743d2d3220746872656164733d31206c6f6f6b61686561645f746872656164733d3120736c696365645f746872656164" +
            "733d30206e723d3020646563696d6174653d3120696e7465726c616365643d3020626c757261795f636f6d7061743d30" +
            "20636f6e73747261696e65645f696e7472613d3020626672616d65733d3320625f707972616d69643d3220625f616461" +
            "70743d3120625f626961733d30206469726563743d3120776569676874623d31206f70656e5f676f703d302077656967" +
            "6874703d32206b6579696e743d323530206b6579696e745f6d696e3d3235207363656e656375743d343020696e747261" +
            "5f726566726573683d302072635f6c6f6f6b61686561643d34302072633d637266206d62747265653d31206372663d32" +
            "332e302071636f6d703d302e36302071706d696e3d302071706d61783d3639207170737465703d342069705f72617469" +
            "6f3d312e34302061713d313a312e3030008000000024658884003bfffee3abf814d85054744cc528fe85b4634fcf148f" +
            "4d850d2d39d404b3b9810000000a419a246c43bffea9d3a000000008419e427885ff113100000008019e617442bf14b0" +
            "00000008019e636a42bf14b100000010419a6849a84168994c0877fffea9d3a10000000a419e8645112c2fff11310000" +
            "0008019ea57442bf14b100000008019ea76a42bf14b000000010419aac49a8416c994c0877fffea9d3a00000000a419e" +
            "ca45152c2fff113100000008019ee97442bf14b000000008019eeb6a42bf14b000000010419af049a8416c994c086fff" +
            "fea7ee410000000a419f0e45152c2fff113100000008019f2d7442bf14b100000008019f2f6a42bf14b000000010419b" +
            "3449a8416c994c0867fffe9efc800000000a419f5245152c2fff113100000008019f717442bf14b000000008019f736a" +
            "42bf14b00000000f419b7849a8416c994c0857fffe3a530000000a419f9645152c2fff113000000008019fb57442bf14" +
            "b100000008019fb76a42bf14b1000004656d6f6f760000006c6d76686400000000e66a9dd0e66a9dd0000003e8000003" +
            "e80001000001000000000000000000000000010000000000000000000000000000000100000000000000000000000000" +
            "004000000000000000000000000000000000000000000000000000000000000002000003907472616b0000005c746b68" +
            "6400000003e66a9dd0e66a9dd00000000100000000000003e80000000000000000000000000000000000010000000000" +
            "000000000000000000000100000000000000000000000000004000000000400000003000000000002465647473000000" +
            "1c656c73740000000000000001000003e80000040000010000000003086d646961000000206d64686400000000e66a9d" +
            "d0e66a9dd0000032000000320055c400000000002d68646c720000000000000000766964650000000000000000000000" +
            "00566964656f48616e646c657200000002b36d696e6600000014766d6864000000010000000000000000000000246469" +
            "6e660000001c6472656600000000000000010000000c75726c2000000001000002737374626c000000bf737473640000" +
            "000000000001000000af6176633100000000000000010000000000000000000000000000000000400030004800000048" +
            "0000000000000001144c61766336312e332e313030206c69627832363400000000000000000000000018ffff00000035" +
            "617663430164000affe100186764000aacd9447b011000000300100000030320f122596001000668ebe3cb22c0fdf8f8" +
            "000000001070617370000000010000000100000014627472740000000000002168000021680000001873747473000000" +
            "000000000100000019000002000000001473747373000000000000000100000001000000d86374747300000000000000" +
            "1900000001000004000000000100000a000000000100000400000000010000000000000001000002000000000100000a" +
            "000000000100000400000000010000000000000001000002000000000100000a00000000010000040000000001000000" +
            "0000000001000002000000000100000a000000000100000400000000010000000000000001000002000000000100000a" +
            "000000000100000400000000010000000000000001000002000000000100000a00000000010000040000000001000000" +
            "0000000001000002000000001c737473630000000000000001000000010000001900000001000000787374737a000000" +
            "000000000000000019000002da0000000e0000000c0000000c0000000c000000140000000e0000000c0000000c000000" +
            "140000000e0000000c0000000c000000140000000e0000000c0000000c000000140000000e0000000c0000000c000000" +
            "130000000e0000000c0000000c000000147374636f0000000000000001000000300000006175647461000000596d6574" +
            "61000000000000002168646c7200000000000000006d6469726170706c0000000000000000000000002c696c73740000" +
            "0024a9746f6f0000001c6461746100000001000000004c61766636312e312e313030",
    )

    /** [jpeg] as a Google motion photo carrying [mp4]: what an Android member's camera shares. */
    val motionPhoto: ByteArray by lazy { checkNotNull(motionPhotoStill(jpeg, mp4.size.toLong())) + mp4 }

    /** A JPEG whose motion-photo XMP points at a trailer that is no video. */
    val brokenMotionPhoto: ByteArray by lazy {
        checkNotNull(motionPhotoStill(jpeg, BROKEN_TRAILER.toLong())) + ByteArray(BROKEN_TRAILER)
    }

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
            GalleryImportContract,
            // Its clauses seed the photos they upload.
            UploadContract,
            // Its change clause seeds one photo to move the library's token.
            GalleryContract,
            // Its clauses seed the photos they move into a folder album.
            FolderAlbumContract,
            // Appended, so no window above moves.
            LivePhotoImportContract,
        )
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    /** 1980-01-01, as days since the epoch. */
    private const val FIRST_DAY = 3652

    private const val CLAUSES_PER_CONTRACT = 50

    private const val SPACING_DAYS = 3

    private const val BROKEN_TRAILER = 64
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
