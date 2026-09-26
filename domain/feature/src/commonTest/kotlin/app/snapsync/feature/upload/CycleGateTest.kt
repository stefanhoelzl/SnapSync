package app.snapsync.feature.upload

import app.snapsync.model.PauseReason
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionScope
import app.snapsync.model.SuppressionReadiness
import app.snapsync.model.UploaderPin
import app.snapsync.model.selectionRulesFor
import app.snapsync.model.SelectionRule
import app.snapsync.model.captureCutoff
import app.snapsync.model.selectionScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The skip-or-leave-or-run gate (capability `join-event`, *An unreadable config is not an absent
 * config*). [CycleGate.NotJoined] reads the device as not joined — so the difference between
 * "unreadable" and "absent" is the difference between a settled join and a false leave on every
 * locked wake.
 *
 * The gate is tier-neutral by construction: it takes primitives, so the same decision is reached
 * whether the OS invoked the cycle or the app did. It used to be reached in the OS-invoked tier's
 * composition root, which is why the app-driven tier did not reach it at all.
 */
class CycleGateTest {

    private val host = "https://edge.example"
    private val eventId = "event-1"
    private val cutoff = captureCutoff("2026-07-01T00:00:00Z")
    // The membership carries a SUPPLIER, not a built policy: the one derivation reads two ports and this
    // gate's translation must stay port-pure (capability `background-upload`). These fixtures never invoke
    // it — the gate decides without consulting the policy, which is the point.
    private val admitting: suspend () -> SelectionPolicy = {
        SelectionPolicy(
            selectionRulesFor(
                includesUpload = true,
                cutoff = cutoff,
                ceiling = null,
                suppressedAssetIds = { emptySet() },
                albumExcludedAssetIds = { emptySet() },
            ),
        )
    }

    // Every pre-admission case runs as an admitted process: admission is decided only once the membership is
    // readable and joined, so these assert that it never masks "unreadable" or "not joined".
    private fun gate(
        configReadable: Boolean,
        membership: JoinedMembership?,
        host: String?,
        admission: UploadAdmission = UploadAdmission.Admit,
        skipDetail: String = "",
    ) = cycleGate(configReadable, membership, host, admission, skipDetail)

    private fun joined(
        eventId: String = this.eventId,
        policy: suspend () -> SelectionPolicy = admitting,
        saveToAlbum: Boolean = false,
    ) = JoinedMembership(eventId = eventId, policy = policy, saveToAlbum = saveToAlbum, manifestVersion = 0L)

    // THE regression. A locked device cannot read the Keychain; that must not clear the join marker.
    @Test
    fun `an unreadable config skips the cycle entirely`() {
        val gate = gate(configReadable = false, membership = joined(), host = host)

        assertIs<CycleGate.Skip>(
            gate,
            "an unreadable config must touch nothing: no reconcile, no marker clear, no jobs",
        )
    }

    @Test
    fun `an unreadable config skips even when no membership is known`() {
        // The membership is null precisely BECAUSE the config could not be read — inferring "not
        // joined" from that is the false leave.
        assertIs<CycleGate.Skip>(gate(configReadable = false, membership = null, host = host))
    }

    // The identity half of the roll-up. `configReadable` covers EVERY protected read the cycle needs,
    // not only the config: an unresolvable device id is "I could not look", never "no id", and the
    // reconciler and manifest producer both close over it — so even the leave-side branch needs it.
    @Test
    fun `an unresolvable device id skips exactly as an unreadable config does`() {
        // The config read succeeded and found a joined event; only the identity probe failed.
        val configRead = true
        val deviceIdReadable = false

        val gate = gate(
            configReadable = configRead && deviceIdReadable,
            membership = joined(),
            host = host,
        )

        assertIs<CycleGate.Skip>(
            gate,
            "an unresolvable identity must not reach NotJoined — that would clear the marker of a " +
                "device that never left",
        )
    }

    @Test
    fun `the skip carries the root's forensics verbatim`() {
        // The decision is made in shared code that cannot see WHY the read failed; the root supplies it
        // so the device log keeps one line rather than two across two files.
        val detail = "config status=-25308, deviceId readable=false"

        val gate = gate(configReadable = false, membership = null, host = host, skipDetail = detail)

        assertIs<CycleGate.Skip>(gate)
        assertEquals(detail, gate.detail)
    }

    @Test
    fun `a definitively absent config is NotJoined so the leave side still reconciles`() {
        val gate = gate(configReadable = true, membership = null, host = host)

        assertEquals(
            CycleGate.NotJoined,
            gate,
            "a real leave must still clear the join marker",
        )
    }

    @Test
    fun `a joined config runs the cycle and carries the membership through`() {
        val gate = gate(
            configReadable = true,
            membership = joined(policy = admitting, saveToAlbum = true),
            host = host,
        )

        assertIs<CycleGate.Run>(gate)
        assertEquals(eventId, gate.config.eventId)
        assertEquals(host, gate.config.host)
        // The cycle's selection inputs arrive WITH the decision — there is no second read, and nothing
        // downstream has to invent a cutoff for a membership that may not exist.
        assertEquals(admitting, gate.membership.policy)
        assertEquals(true, gate.membership.saveToAlbum)
    }

    // A non-contributing membership still RUNS the gate — the direction gate lives one step further in,
    // inside `UploadCycle.run()`, so the cycle can decline it after the read rather than before.
    @Test
    fun `a non-contributing membership is Run and declines later at the direction gate`() {
        val gate = gate(
            configReadable = true,
            membership = joined(policy = { SelectionPolicy(listOf(SelectionRule.DenyAll)) }),
            host = host,
        )

        assertIs<CycleGate.Run>(gate)
        // The gate does NOT invoke the supplier — it decides whether to run, and the direction gate inside
        // the cycle is what consults the policy. That is the whole reason this is a supplier: the
        // derivation reads two ports, and this translation must stay port-pure.
        assertEquals(
            SelectionPolicy(listOf(SelectionRule.DenyAll)),
            runBlocking { gate.membership.policy() },
        )
    }

    @Test
    fun `a missing host is NotJoined as it always has been`() {
        assertEquals(CycleGate.NotJoined, gate(configReadable = true, membership = joined(), host = null))
        assertEquals(CycleGate.NotJoined, gate(configReadable = true, membership = joined(), host = ""))
    }

    @Test
    fun `an empty event id is NotJoined`() {
        assertEquals(
            CycleGate.NotJoined,
            gate(configReadable = true, membership = joined(eventId = ""), host = host),
        )
    }

    // ---- admission (capability `background-upload`, "The upload cycle owns its entry decision") ----------

    @Test
    fun `a process that may not create is Withheld and carries the config the narrow settle needs`() {
        val gate = gate(configReadable = true, membership = joined(), host = host, admission = UploadAdmission.Withheld)

        assertIs<CycleGate.Withheld>(gate)
        assertEquals(eventId, gate.config.eventId)
        assertEquals(host, gate.config.host)
    }

    // The NOT_DETERMINED trap: building the policy reads the album structure, which prompts. The gate must
    // decide the decline without invoking the supplier.
    @Test
    fun `a withheld decline does not invoke the policy supplier`() {
        val forbidden: suspend () -> SelectionPolicy = { error("the gate must not build the policy") }
        val gate = gate(configReadable = true, membership = joined(policy = forbidden), host = host, admission = UploadAdmission.Withheld)
        assertIs<CycleGate.Withheld>(gate)
    }

    @Test
    fun `unreadable and absent outrank admission`() {
        for (admission in UploadAdmission.entries) {
            assertIs<CycleGate.Skip>(gate(configReadable = false, membership = joined(), host = host, admission = admission))
            assertEquals(CycleGate.NotJoined, gate(configReadable = true, membership = null, host = host, admission = admission))
        }
    }

    @Test
    fun `the app admits under any usable grant unless switched off`() {
        for (permission in GalleryAccess.entries) {
            val usable = permission == GalleryAccess.GRANTED || permission == GalleryAccess.LIMITED
            val expected = if (usable) UploadAdmission.Admit else UploadAdmission.Withheld
            val read = selectionScope(permission, emptyList())
            assertEquals(expected, appAdmission(permission, read), "under $permission")
            assertEquals(UploadAdmission.Withheld, appAdmission(permission, read, UploaderPin(app = false)), "switched off")
            assertEquals(expected, appAdmission(permission, read, UploaderPin(extension = false)), "the other switch")
        }
    }

    @Test
    fun `the app withholds under a partial grant until the selection has been read`() {
        // An unread selection is not an empty one: a read snapshot is an authoritative walk, so a cycle over an
        // unread one would delete every row (`changes/selection-is-the-walk`, D1). A read EMPTY selection admits —
        // it is a real answer, and a receive-only member's resting state.
        assertEquals(UploadAdmission.Withheld, appAdmission(GalleryAccess.LIMITED, SelectionScope.Unread))
        assertEquals(
            UploadAdmission.Admit,
            appAdmission(GalleryAccess.LIMITED, selectionScope(GalleryAccess.LIMITED, emptyList())),
        )
        assertEquals(
            UploadAdmission.Withheld,
            appAdmission(GalleryAccess.LIMITED, selectionScope(GalleryAccess.LIMITED, null)),
            "the derivation of a null snapshot is what withholds",
        )
    }

    @Test
    fun `under a full grant both processes admit`() {
        assertEquals(UploadAdmission.Admit, appAdmission(GalleryAccess.GRANTED, SelectionScope.Unrestricted))
        assertEquals(UploadAdmission.Admit, extensionAdmission(GalleryAccess.GRANTED))
    }

    @Test
    fun `the extension admits exactly under a full grant`() {
        for (permission in GalleryAccess.entries) {
            val expected = if (permission == GalleryAccess.GRANTED) UploadAdmission.Admit else UploadAdmission.Withheld
            assertEquals(expected, extensionAdmission(permission), "under $permission")
        }
    }

    // ---- the suppression step: after admission, before anything is touched ------------------------------------

    private val run = CycleGate.Run(UploadConfig(host, eventId), JoinedMembership(eventId, admitting, false, 0L))

    @Test
    fun `a ready suppression read runs the cycle`() {
        assertEquals(run, suppressionGate(run, SuppressionReadiness.Ready))
    }

    @Test
    fun `an old suppression store pauses the cycle`() {
        // The extension may not migrate the app's store, and running without it re-uploads downloaded photos.
        assertEquals(CycleGate.Paused(PauseReason.OLD_SCHEMA), suppressionGate(run, SuppressionReadiness.OldSchema))
    }

    @Test
    fun `an unopenable suppression store skips the cycle naming why`() {
        val gate = suppressionGate(run, SuppressionReadiness.Unavailable("locked"))
        assertIs<CycleGate.Skip>(gate, "\"I could not look\" uploads nothing this run")
        assertTrue("locked" in gate.detail)
    }
}
