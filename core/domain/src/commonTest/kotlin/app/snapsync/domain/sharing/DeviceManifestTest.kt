package app.snapsync.domain.sharing

import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.upload.MintsUploadRow
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * What a device manifest declares (capability `photo-sharing`): every row the one policy admits, whatever its upload
 * state, grouped per photo in a stable order, with its version and its declaration.
 */
@OptIn(MintsUploadRow::class)
class DeviceManifestTest {

    private val device = requireNotNull(DeviceId.parse("00000000-0000-4000-9000-0000000000a1"))
    private val version = ManifestVersion(7)

    private fun row(asset: String, role: ResourceRole = ResourceRole.PRIMARY, file: String = "IMG.HEIC", taken: String = "2026-06-02T10:00:00Z") =
        UploadRow.Discovered(
            UploadKey.of(id(asset), role, file),
            RowDetail(CaptureDate.Dated(ts(taken)), "image/heic", file),
        )

    private fun manifest(rows: List<UploadRow>, policy: SelectionPolicy = policy()) = DeviceManifest.of(
        device,
        rows,
        policy.admitRecorded(rows.map { it.recorded }),
        version,
        ShareDeclaration.Sharing,
    )

    @Test
    fun `it declares every admitted photo with all its resources, whatever their upload state`() {
        val live = row("A", ResourceRole.LIVE, "IMG.MOV")
        val primary = row("A")
        val requested = UploadRow.Requested(row("B").key, row("B").detail, null)
        val completed = UploadRow.Completed(row("C").key, row("C").detail)

        val declared = manifest(listOf(live, primary, requested, completed))

        assertEquals(listOf("A", "B", "C"), declared.assets.map { it.asset.value })
        val a = declared.assets.first()
        assertEquals(listOf(ResourceRole.PRIMARY, ResourceRole.LIVE), a.resources.map { it.key.role }, "primary first")
        assertEquals(CaptureDate.Dated(ts("2026-06-02T10:00:00Z")), a.captured)
        assertEquals("image/heic", a.resources.first().contentType)
        assertEquals("IMG.HEIC", a.resources.first().filename)
    }

    @Test
    fun `a photo the policy does not admit is not declared`() {
        val declared = manifest(listOf(row("IN"), row("OUT", taken = "2026-05-20T10:00:00Z")))
        assertEquals(listOf("IN"), declared.assets.map { it.asset.value })
    }

    @Test
    fun `the same rows in any order make the same manifest`() {
        val rows = listOf(row("B"), row("A", ResourceRole.LIVE, "IMG.MOV"), row("A"))
        val one = manifest(rows)
        val other = manifest(rows.reversed())
        assertEquals(one.assets.map { it.asset }, other.assets.map { it.asset })
        assertEquals(
            one.assets.flatMap { it.resources.map { r -> r.key } },
            other.assets.flatMap { it.resources.map { r -> r.key } },
        )
    }

    @Test
    fun `it carries its device, version and declaration`() {
        val declaration = ShareDeclaration.of(SharePhase.Ended(RANGE.until.at), SettlingLook.NothingToLook)
        val declared = DeviceManifest.of(device, emptyList(), emptyList(), version, declaration)
        assertEquals(device, declared.device)
        assertEquals(version, declared.version)
        assertSame(declaration, declared.declaration)
        assertEquals(emptyList(), declared.assets)
    }
}
