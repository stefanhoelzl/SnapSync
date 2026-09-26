package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **A production build's development controls never deliver** (`docs/architecture.md`, "A build-time-only module is
 * contained by compilation"; capability `join-event`, "Joining happens only on confirmation").
 *
 * The `DevControls` port carries the inputs only a test build may set: the per-uploader pin and the invite-link hints
 * that let a crafted link join without a tap. Their guarantee is structural — the control channel's controls are
 * compiled only into a rig build — so it holds only while production source contains nothing that could answer
 * otherwise. Two pins, both exact:
 *
 *  - the ONE `DevControls` implementation in production source is `InertDevControls` (null pin, hints ignored, never
 *    delivers) — the channel's `RigDevControls` lives in `:test:rig`, which a production build does not link;
 *  - `InviteLinkHints.Honoured` is NAMED in production source only where the join gate reads it — no production
 *    code can answer it.
 *
 * Production source here: `:domain`, `:adapter`, `:app` and `:ui` main source sets, never their tests and never an
 * adapter's rig-gated `src/rig`. The rig's own hook directories live under `test/` and are out of scope by path.
 */
class DevControlsContainmentTest {

    private val production by lazy {
        SourceScan.kotlinFiles().filter { src ->
            listOf("/domain/", "/adapter/", "/app/", "/ui/").any { src.path.startsWith(it) } &&
                !Regex("""/src/(\w*[Tt]est|rig)/""").containsMatchIn(src.path)
        }
    }

    @Test
    fun `the only production development controls are the inert ones`() {
        val implementations = production
            .filter { IMPLEMENTS.containsMatchIn(ZoneGates.stripComments(it.text)) }
            .map { it.path }
        assertEquals(
            listOf("/adapter/generic/app/src/commonMain/kotlin/app/snapsync/dev/InertDevControls.kt"),
            implementations,
            "a production DevControls other than the inert one could pin an uploader or honour a link's hints on a " +
                "shipped build — the channel's controls belong in `:test:rig`, compiled into a rig build only",
        )
    }

    @Test
    fun `honoured invite-link hints are only ever read in production`() {
        val naming = production
            .filter { "InviteLinkHints.Honoured" in ZoneGates.stripComments(it.text) }
            .map { it.path }
        assertEquals(
            listOf("/domain/presentation/src/commonMain/kotlin/app/snapsync/presentation/StatusContainerHost.kt"),
            naming,
            "`InviteLinkHints.Honoured` is named in production outside the join gate's read — a production answer " +
                "of it would let a crafted link join without a tap",
        )
    }

    @Test
    fun `the scan is real (non-vacuity floor)`() {
        assertTrue(production.size >= 200, "scanned only ${production.size} production files — the scope is broken")
    }

    private companion object {
        /** A class or object header naming `DevControls` among its supertypes (a parameter typed so does not). */
        val IMPLEMENTS = Regex("""\b(?:class|object)\s+\w+\s*(?:\([^)]*\))?\s*:\s*[\w\s,<>.]*\bDevControls\b""")
    }
}
