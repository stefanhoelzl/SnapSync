package app.snapsync.format

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.UploadKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An upload key's one text form: every ledger row, object name and transfer tag a device holds was written in it, so
 * a change strands what an installed build uploaded.
 */
class UploadKeyTextTest {

    private val asset = AssetId.parse("E6C2F1A0-0B1C-4D2E-8F3A-1B2C3D4E5F60_L0_001")!!

    @Test
    fun `a key is its asset, role and lowercased extension`() {
        assertEquals(
            "E6C2F1A0-0B1C-4D2E-8F3A-1B2C3D4E5F60_L0_001-primary.heic",
            UploadKeyText.of(UploadKey.of(asset, ResourceRole.PRIMARY, "IMG_0001.HEIC")),
        )
        assertEquals(
            "E6C2F1A0-0B1C-4D2E-8F3A-1B2C3D4E5F60_L0_001-live.mov",
            UploadKeyText.of(UploadKey.of(asset, ResourceRole.LIVE, "IMG_0001.MOV")),
        )
    }

    @Test
    fun `a file named without an extension is named bin`() {
        assertEquals("bin", UploadKey.of(asset, ResourceRole.PRIMARY, "IMG_0001").extension)
        assertEquals("bin", UploadKey.of(asset, ResourceRole.PRIMARY, "IMG_0001.").extension)
    }

    @Test
    fun `a key reads back as itself - an asset id holding dashes included`() {
        ResourceRole.entries.forEach { role ->
            val key = UploadKey.of(asset, role, "x.JPG")
            assertEquals(key, UploadKeyText.parse(UploadKeyText.of(key)))
        }
        val digits = AssetId.parse("1234")!!
        assertEquals(UploadKey.read(digits, ResourceRole.PRIMARY, "jpg"), UploadKeyText.parse("1234-primary.jpg"))
    }

    @Test
    fun `text that spells no key is none`() {
        assertNull(UploadKeyText.parse("no-extension"))
        assertNull(UploadKeyText.parse("primary.jpg"))
        assertNull(UploadKeyText.parse("1234-still.jpg"))
        assertNull(UploadKeyText.parse("bad id!-primary.jpg"))
        assertNull(UploadKeyText.parse(""))
    }
}
