package app.snapsync.domain.upload

import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.event.anEvent
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.Share
import app.snapsync.domain.sharing.AdmittedRow
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.ManifestVersion
import app.snapsync.domain.sharing.MintsAdmittedRow
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.domain.sharing.ReadPlan
import app.snapsync.domain.upload.UploadPass.CAUGHT_UP
import app.snapsync.domain.upload.UploadPass.PAUSED
import app.snapsync.domain.upload.UploadPass.SKIPPED
import app.snapsync.domain.upload.UploadPass.UNVERIFIED
import app.snapsync.domain.upload.UploadPass.WORK_LEFT
import app.snapsync.domain.wake.UploadsLeft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

private val DEVICE = requireNotNull(DeviceId.parse("00000000-0000-4000-9000-0000000000a1"))

@OptIn(MintsUploadRow::class, MintsAdmittedRow::class)
class CycleVerdictsTest {

    private fun key(asset: String) = UploadKey.of(AssetId.parse(asset)!!, ResourceRole.PRIMARY, "a.jpg")
    private val dated =
        RowDetail(
            CaptureDate.Dated(app.snapsync.domain.time.Timestamp.of(kotlin.time.Instant.parse("2026-07-01T00:00:00Z"))),
            "image/jpeg",
            "a.jpg",
        )
    private val bare = RowDetail(CaptureDate.Undated, "", "")

    private fun active(): Membership.Active {
        val event = anEvent()
        val range = assertIs<CaptureRangeVerdict.Ok>(
            CaptureRange.of(
                CaptureCutoff(event.range.start.at),
                CaptureCeiling(event.range.end.at),
                event.range.start,
                event.range.end,
            ),
        ).range
        return Membership.Active(event, Share(range, Direction.Both, album = AlbumChoice.LibraryOnly))
    }

    @Test
    fun `a wake's passes roll up - the latest skip wins, then work left, a paused step's too`() {
        assertEquals(SKIPPED, UploadPass.of(WORK_LEFT, SKIPPED))
        assertEquals(WORK_LEFT, UploadPass.of(SKIPPED, WORK_LEFT))
        assertEquals(WORK_LEFT, UploadPass.of(CAUGHT_UP, PAUSED, null))
        assertEquals(CAUGHT_UP, UploadPass.of(CAUGHT_UP, null))
        assertEquals(CAUGHT_UP, UploadPass.of())
        assertEquals(UNVERIFIED, UploadPass.of(UNVERIFIED, WORK_LEFT), "a step that could send nothing")
        assertEquals(SKIPPED, UploadPass.of(UNVERIFIED, SKIPPED))
    }

    @Test
    fun `the heartbeat and the extension read a pass`() {
        assertEquals(
            listOf(
                UploadsLeft.CAUGHT_UP,
                UploadsLeft.WORK_LEFT,
                UploadsLeft.WORK_LEFT,
                UploadsLeft.WORK_LEFT,
                UploadsLeft.NOT_CONTRIBUTING,
            ),
            UploadPass.entries.map(UploadPass::left),
        )
        assertEquals(ExtensionAnswer.REST, CAUGHT_UP.extensionAnswer(inFlight = 0))
        assertEquals(ExtensionAnswer.INVOKE_AGAIN, CAUGHT_UP.extensionAnswer(inFlight = 2))
        assertEquals(ExtensionAnswer.INVOKE_AGAIN, WORK_LEFT.extensionAnswer(0))
        assertEquals(ExtensionAnswer.INVOKE_AGAIN, PAUSED.extensionAnswer(0))
        assertEquals(ExtensionAnswer.REST, SKIPPED.extensionAnswer(3))
        assertEquals(ExtensionAnswer.REST, UNVERIFIED.extensionAnswer(3), "it cannot obtain a proof: it rests")
        assertEquals(ExtensionAnswer.COULD_NOT_LOOK, CAUGHT_UP.extensionAnswer(inFlight = null), "a count unread")
    }

    @Test
    fun `a walk records the new, fills the bare and withdraws what its window holds but it did not admit`() {
        val rows = listOf(
            UploadRow.Completed(key("KNOWN"), dated),
            UploadRow.Completed(key("BARE"), bare),
            UploadRow.Requested(key("GONE"), dated, null),
            UploadRow.Discovered(key("OUTSIDE"), dated),
        )
        val found = listOf(FoundResource(key("BARE"), dated), FoundResource(key("NEW"), dated))
        val admitted = setOf(key("KNOWN").asset, key("BARE").asset, key("NEW").asset)
        val window = setOf(key("KNOWN").asset, key("BARE").asset, key("GONE").asset)

        val diff = WalkDiff.of(rows, found, admitted, window)

        assertEquals(listOf(key("NEW")), diff.fresh.map { it.key })
        assertEquals(listOf(key("BARE")), diff.bare.map { it.key })
        assertEquals(setOf(key("BARE").asset), diff.dated)
        assertEquals(listOf(key("GONE")), diff.departed)
        assertEquals(setOf(key("KNOWN").asset, key("GONE").asset, key("OUTSIDE").asset), WalkDiff.known(rows))
    }

    @Test
    fun `a top-up plans the rows needing a job whose asset the policy admits now`() {
        val rows = listOf(UploadRow.Discovered(key("A"), dated), UploadRow.Discovered(key("B"), dated))

        val plan = TopUpPlan.of(rows, listOf(AdmittedRow(key("B").asset)))

        assertEquals(listOf(key("B")), plan.rows.map { it.key })
        assertEquals(setOf(key("B").asset), plan.assets)
    }

    @Test
    fun `each uploader states its own admission`() {
        assertEquals(UploadAdmission.WITHHELD, UploadAdmission.forApp(ReadPlan.NotReadable))
        assertEquals(UploadAdmission.ADMIT, UploadAdmission.forApp(ReadPlan.Walk))
        assertEquals(UploadAdmission.ADMIT, UploadAdmission.forApp(ReadPlan.Snapshot("selection")))
        assertEquals(UploadAdmission.ADMIT, UploadAdmission.forExtension(PhotoAccess.FULL))
        assertEquals(UploadAdmission.WITHHELD, UploadAdmission.forExtension(PhotoAccess.LIMITED))
        assertEquals(UploadAdmission.WITHHELD, UploadAdmission.forExtension(PhotoAccess.NONE))
    }

    private fun GateStep.gate(): UploadGate = when (this) {
        is GateStep.Stop -> gate
        is GateStep.Proceed -> run
    }

    private fun entry(read: GateRead, host: UploadHost, presence: KeyPresence, admission: UploadAdmission) =
        UploadGate.entry(read, host, presence, admission).gate()

    private fun suppression(run: UploadGate.Run, readiness: EchoReadiness) = UploadGate.suppression(
        run,
        readiness,
    ).gate()

    @Test
    fun `the gate - unreadable touches nothing, no host or membership is not joined, deleted or lost withholds`() {
        val active = active()
        val read = GateRead.Read(active, ManifestVersion(7), DEVICE)
        val held = KeyPresence.NotNeeded

        assertEquals(
            UploadGate.Unreadable("x"),
            entry(GateRead.Unreadable("x"), UploadHost.CARRIED, held, UploadAdmission.ADMIT),
        )
        assertEquals(UploadGate.NotJoined, entry(read, UploadHost.NONE, held, UploadAdmission.ADMIT))
        assertEquals(
            UploadGate.NotJoined,
            entry(GateRead.Read(null, ManifestVersion(0), DEVICE), UploadHost.CARRIED, held, UploadAdmission.ADMIT),
        )
        val deleted = Membership.Deleted(active.event, active.share)
        assertIs<UploadGate.Withheld>(
            entry(GateRead.Read(deleted, ManifestVersion(0), DEVICE), UploadHost.CARRIED, held, UploadAdmission.ADMIT),
        )
        assertIs<UploadGate.Withheld>(
            entry(read, UploadHost.CARRIED, KeyPresence.Lost, UploadAdmission.ADMIT),
        )
        assertIs<UploadGate.Withheld>(entry(read, UploadHost.CARRIED, held, UploadAdmission.WITHHELD))
        val run = assertIs<UploadGate.Run>(entry(read, UploadHost.CARRIED, held, UploadAdmission.ADMIT))
        assertEquals(ManifestVersion(7), run.version)
        assertEquals(DEVICE, run.device, "the device the entry read, which the publish speaks for")
        assertSame(active, run.membership)
    }

    @Test
    fun `a build whose upload host is blank carries none`() {
        assertEquals(UploadHost.NONE, UploadHost.of(""))
        assertEquals(UploadHost.NONE, UploadHost.of("  "))
        assertEquals(UploadHost.CARRIED, UploadHost.of("https://edge.example"))
    }

    @Test
    fun `a running gate waits for echo suppression and recognition`() {
        val run = UploadGate.Run(active(), ManifestVersion(1), DEVICE)

        assertSame(run, suppression(run, EchoReadiness.Ready))
        assertEquals(UploadGate.Paused(UploadPause.OLD_SCHEMA), suppression(run, EchoReadiness.OldSchema))
        assertIs<UploadGate.Unreadable>(suppression(run, EchoReadiness.Unavailable("locked")))
        assertSame(run, UploadGate.recognition(run, recognised = true))
        assertIs<UploadGate.Withheld>(UploadGate.recognition(run, recognised = false))
    }

    @Test
    fun `what a pass answers when it stops at the gate`() {
        assertEquals(CAUGHT_UP, UploadGate.Unreadable("x").pass)
        assertEquals(SKIPPED, UploadGate.NotJoined.pass)
        assertEquals(SKIPPED, UploadGate.Withheld(active().event.id).pass)
        assertEquals(PAUSED, UploadGate.Paused(UploadPause.LEGACY_RECORDS).pass)
        assertEquals(CAUGHT_UP, UploadGate.Run(active(), ManifestVersion(1), DEVICE).pass)
    }
}
