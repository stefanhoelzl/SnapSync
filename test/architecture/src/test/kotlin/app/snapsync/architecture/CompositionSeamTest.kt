package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every function-typed field of the composition bundles is pinned, with its reason** (capability
 * `architecture-guards`, requirement "The composition seam gate"; law: `module-architecture`, "Ports
 * are the I/O boundary named for the need"). Decision record: `changes/…/enforce-port-boundary`
 * (D1, D5, D9).
 *
 * `AppPorts` and `UploadPorts` are where the shell hands the core everything it may not build itself.
 * Most of what crosses is a port, and a port is a *declared* boundary — a reader, and every gate that
 * reads types, can see the process end there. A function-typed field declares nothing: it is equally
 * the shape of pure in-core coordination and of an inline adapter written in the composition root. Five
 * of them were the latter, and one (`now = { NSDate()… }`) sat beside the `Clock` port built for
 * exactly that value, bypassed and invisible.
 *
 * **A pin, not an analysis — because the property is not decidable from source** (D1). Whether a
 * lambda's invocation leaves the process cannot be read off its type: `downloadStagingRoot: () ->
 * String` resolved an App-Group container and `deviceId: () -> String` returns a value the composition
 * already holds, and the two are type-identical. Nor is it decidable from the body, which is in another
 * module and often another binary. So the gate records the human judgement at the moment it is made,
 * exactly as [KotlinShellGuardTest] does for complexity suppressions, and forces the next person to
 * make one: a new function-typed field fails until it is either given a port type or entered below
 * **with a reason it is not one**. A removed field fails until its pin goes with it, so the inventory
 * can never describe code that is not there.
 *
 * ## What this gate CANNOT see — it constrains what the composition hands the core
 *
 * It says nothing about **what the OS hands the shell**. Registering an `NSNotificationCenter`
 * observer, adopting a scene-delegate callback, or submitting a `BGProcessingTaskRequest` is the shell
 * *being called by* the platform — arranging to be woken — not reaching out to read or write something.
 * That surface is out of scope here, and no green run makes a claim about it. (One pinned entry,
 * `scheduleBackstop`, sits exactly on that line and says so; whether the line holds is an Open Question
 * in the decision record, to be settled by the first site that straddles it — not by this gate.)
 *
 * Two narrower blind spots, so a green run is not over-read either:
 *  - **It reads a declaration, not a call graph.** A pinned lambda whose body is later rewritten to
 *    reach out of the process still passes: the pin's reason goes stale silently, because nothing here
 *    re-derives it. The reason is a receipt for a judgement, not a proof of one.
 *  - **It is scoped to the two bundles.** A platform touch smuggled in as a field of some *other* type
 *    the shell constructs — a data class carrying a lambda, say — is not function-typed at this level
 *    and is not seen. The bundle set itself is pinned (below) so a *third* bundle cannot appear
 *    unnoticed, which is the one novelty this gate can close.
 *
 * The tone is [MainLaneContainmentTest]'s, deliberately: *it contains a lane; it does not decide
 * whether a call blocks.* This one pins a seam inventory; it does not decide whether a seam crosses.
 */
class CompositionSeamTest {

    /** The composition bundles (law "One shared composition") → the `compose/` file declaring each. */
    private val bundles = mapOf(
        "AppPorts" to "domain/compose/src/commonMain/kotlin/app/snapsync/compose/SnapSyncApp.kt",
        "UploadPorts" to "domain/compose/src/commonMain/kotlin/app/snapsync/compose/UploadCore.kt",
    )

    /**
     * bundle → field → **why this lambda is core coordination rather than a platform touch**.
     *
     * A reason is required by construction (a pin is a `to` with a string; the hygiene test below
     * refuses a stub), because the pin's only job is to carry the judgement. The recurring shapes:
     *
     *  - **the chain terminates at a port** — `flow/` may not reference `ports/` at all (law "Zones
     *    inside the core"), so `compose/` hands a flow its collaborator as a lambda while the platform
     *    touch at the far end still goes through a port (D9);
     *  - **deferred construction** — a thunk whose *call time* is load-bearing, returning something the
     *    composition could otherwise have built eagerly, but must not (a locked launch, a cycle in the
     *    object graph, a tier that must stay unconstructed);
     *  - **a re-entry into this same core** through a shell surface that decides nothing (law "Shells
     *    are wiring only").
     *
     * Two entries are judgement calls at the edge rather than instances of those shapes, and they say
     * so at the site instead of borrowing a shape that does not fit: `AppPorts.reloadConfig` and
     * `UploadPorts.host`.
     */
    private val pins: Map<String, Map<String, String>> = mapOf(
        "AppPorts" to mapOf(
            // A factory FOR a port, which is the opposite of a lambda standing in for one: what it
            // returns is the declared boundary. It is a lambda because the transport must be handed the
            // queue that owns it (`DownloadTransportHost`), which does not exist until the feature is
            // built — a cycle in the object graph, broken here.
            "newDownloadTransport" to
                "builds the DownloadTransport PORT, which is where the crossing is declared; a lambda " +
                "only because the transport takes the host queue that does not exist until the feature " +
                "is constructed",
            // D1's own example of why this gate is a pin and not an analysis.
            "deviceId" to
                "returns a value the composition already holds — the identity `resolveOrMint` produced " +
                "over the Keychain port. A thunk so the resolve happens at first use, never while " +
                "assembling a locked background launch, where the Keychain read would throw out of the " +
                "composition",
            "appDrivenUpload" to
                "hands back core machinery (a feature/upload type). A thunk so the mechanism is " +
                "constructed at first use rather than while the graph is being assembled",
            "osDrivenUpload" to
                "the same, and `null` where this OS does not carry that mechanism at all — the " +
                "nullability IS that OS answer, and it must be a call rather than a value the bundle " +
                "carries so the mechanism is never constructed where its registration selector does " +
                "not exist",
            "relinquishOsRegistration" to
                "a platform effect: it deregisters the OS's upload-job configuration record. " +
                "Deliberately NARROWER than that mechanism's own `stop()`, whose ledger clear and " +
                "cursor reset would wipe rows the incoming mechanism reconciles precisely — which is " +
                "why it is a lambda bound at the composition site and not a second seam verb",
            "uploadMechanismOverride" to
                "reads a development pin on the resolved mechanism, re-read per resolution so the pin " +
                "can change without rebuilding the graph. `null` in a production build not by " +
                "convention but by CONSTRUCTION: the only writer of the root thunk behind it is the " +
                "control channel's boot hook, whose source is absent from a build made without the " +
                "channel's build property — so a shipped binary has nothing able to assign it, and the " +
                "mechanism it runs stays a function of the device alone",
            // The lambda carries the tier's FAILURE POSTURE, which a shared port would erase: the app
            // admits on doubt, the extension lets a throw fail the cycle (stated at the field).
            "albumExcludedAssetIds" to
                "the process's admit-on-doubt wrapper over the AlbumManager port — the chain terminates " +
                "at a port (D9); the lambda carries the per-tier failure posture, and is shared verbatim " +
                "with the status total so the two consumers of one policy cannot diverge",
            "clearDiscoveryCursor" to
                "invalidates the shared cursor through the DiscoveryStore port, which this bundle " +
                "deliberately does not carry (it belongs to UploadPorts) — one surface both " +
                "ReconfigureEvent and ResetDeviceState reach it by, rather than two that could diverge",
            "provision" to
                "re-enters this core's own flow/Provision through the shell's log-wrapping delegator; " +
                "the shell decides nothing on the way (law \"Shells are wiring only\")",
            "onEventMinted" to
                "hands a minted event id back to the shell's link entry, which forwards it into THIS " +
                "core's join gate — a U-turn through the entry surface so create and a scanned QR take " +
                "one gate, not two. Nothing leaves the process",
            "refreshAttestation" to
                "drives the core's own DeviceAttestation feature (whose network call is the AttestClient " +
                "port); the shell adds only the awaited call and the health flag",
            // The nearest thing here to a violation, named rather than dressed up.
            "reloadConfig" to
                "JUDGEMENT CALL: refreshes the core's own config read-model from the store already " +
                "behind ConfigSource/ConfigStore — the file read is those ports'; what has no port is " +
                "the refresh VERB, deliberately (see readGate in UploadCore.kt: `reload()` is a " +
                "read-model side effect, not gate logic, and the spec names the gate's inputs " +
                "exhaustively). Give it a port the moment it grows a second reason to exist",
            "scheduleBackstop" to
                "submits a BGProcessingTaskRequest: ARRANGING TO BE CALLED BACK, which is where this " +
                "gate's scope stops (see the blind spot above). Nothing is read out and no value comes " +
                "back into the core — the work happens later, in a flow the OS triggers",
            "registerPush" to
                "re-PUTs the token the OS already delivered (an in-memory PushTokenSource read) through " +
                "PushRegistration over the PushHttpClient port — both ends are ports",
        ),
        "UploadPorts" to mapOf(
            "deviceId" to
                "as AppPorts.deviceId — the identity the composition already resolved over the Keychain " +
                "port, thunked so a locked launch is never forced to read it while assembling",
            // The second judgement call, and the one with an expiry attached.
            "host" to
                "JUDGEMENT CALL: the compile-time upload base baked into THIS binary's own bundle — a " +
                "build constant of the running process rather than another system's state, which cannot " +
                "change while it runs. A thunk because the extension re-reads its bundle per gate call " +
                "and an absent host must skip the cycle, not crash it. EXPIRY: if the destination ever " +
                "becomes runtime-resolved (the open uploadBase question), it is a port",
            "selectionScope" to
                "what discovery may read right now (capability `limited-photo-access`), derived by the " +
                "app composition from current permission plus the in-memory snapshot — a call and not a " +
                "value because the answer changes between cycles. Pure core read",
            "albumExcludedAssetIds" to
                "as AppPorts — the AlbumManager port at the far end; on this tier a thrown lookup fails " +
                "the cycle, which is exactly the per-tier difference a shared port member would erase",
            "token" to
                "the attestation bearer, read per request from the AttestStore port (extension) or the " +
                "core's own DeviceAttestation (app) — a call because a renewal must be picked up without " +
                "rebuilding the cycle",
            "appVersion" to
                "JUDGEMENT CALL, and the SAME judgement as `host` directly above: the marketing version " +
                "baked into THIS binary's own bundle — a build constant of the running process, not " +
                "another system's state, and it cannot change while it runs. A thunk because the " +
                "extension re-reads its own bundle and must declare ITS version on the byte upload the " +
                "OS performs (capability `min-app-version`), which no shared client can reach. EXPIRY: " +
                "if the declared version ever becomes runtime-resolved, it is a port — the same expiry " +
                "`host` carries, for the same reason",
        ),
    )

    // ---- scanning ---------------------------------------------------------------------------------

    private data class Field(val name: String, val type: String, val line: Int)

    /** Every `class <Name>Ports(` declared in `compose/` — the bundle set itself, pinned below. */
    private fun declaredBundles(): Set<String> {
        val files = ZoneGates.requireZone("composition-seam", "compose")
        return files.flatMap { file ->
            Regex("""\bclass\s+(\w*Ports)\s*\(""").findAll(ZoneGates.stripComments(file.readText()))
                .map { it.groupValues[1] }
        }.toSet()
    }

    /**
     * The constructor parameters of [className], as declared. Comments are stripped first and that is
     * not optional: `AppPorts` documents the fields it *no longer has*, so a scanner reading KDoc would
     * report `presentPhotoPicker: () -> Unit` — a field this change deleted — as live.
     */
    private fun params(bundle: String): List<Field> {
        val path = bundles.getValue(bundle)
        val file = File(ZoneGates.repoRoot, path)
        assertTrue(file.isFile, "composition seam gate: $path is gone — the bundle moved and this gate is stale")
        val code = ZoneGates.stripComments(file.readText())
        val header = Regex("""\bclass\s+$bundle\s*\(""").find(code)
            ?: error("composition seam gate: no `class $bundle(` in $path — the declaration moved, fix the scan")
        val open = header.range.last
        val body = balanced(code, open) ?: error("composition seam gate: unbalanced `class $bundle(` in $path")
        return splitTopLevel(code.substring(open + 1, body), ',')
            .mapNotNull { (text, offset) ->
                val decl = text.trim()
                if (decl.isBlank()) return@mapNotNull null
                val colon = indexOfTopLevel(decl, ':') ?: return@mapNotNull null
                val name = decl.take(colon).trim().substringAfterLast(' ')
                val type = decl.substring(colon + 1)
                    .let { rest -> indexOfTopLevel(rest, '=')?.let { rest.take(it) } ?: rest }
                    .trim()
                Field(name, type, code.take(open + 1 + offset).count { it == '\n' } + 1)
            }
    }

    /** The index of the `)` closing the `(` at [open], or null. */
    private fun balanced(code: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /**
     * Top-level splitting over `(<{[` nesting. `->` is consumed as ONE token so its `>` never reads as
     * a closing generic bracket — the difference between seeing `() -> Unit` and seeing garbage.
     */
    private fun scan(text: String, stop: Char?, onTop: (Int) -> Unit) {
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("->", i) -> { if (depth == 0 && stop == null) onTop(i); i += 2; continue }
                c == '"' -> { i++; while (i < text.length && text[i] != '"') i++ }
                c in "(<{[" -> depth++
                c in ")>}]" -> depth--
                depth == 0 && stop != null && c == stop -> onTop(i)
            }
            i++
        }
    }

    private fun splitTopLevel(text: String, separator: Char): List<Pair<String, Int>> {
        val cuts = mutableListOf<Int>()
        scan(text, separator) { cuts += it }
        val parts = mutableListOf<Pair<String, Int>>()
        var start = 0
        (cuts + text.length).forEach { cut ->
            parts += text.substring(start, cut) to start
            start = cut + 1
        }
        return parts
    }

    private fun indexOfTopLevel(text: String, char: Char): Int? {
        var found: Int? = null
        scan(text, char) { if (found == null) found = it }
        return found
    }

    /** A type is function-typed iff it carries a `->` at nesting depth 0 — `() -> (X) -> Y` included. */
    private fun isFunctionType(type: String): Boolean {
        var arrow = false
        scan(type, null) { arrow = true }
        return arrow
    }

    private fun functionFields(bundle: String): List<Field> = params(bundle).filter { isFunctionType(it.type) }

    // ---- the gate ---------------------------------------------------------------------------------

    @Test
    fun `every function-typed field of the composition bundles is pinned, exactly`() {
        val found = bundles.keys.associateWith { functionFields(it) }
        val expected = pins.mapValues { (_, entries) -> entries.keys.toSortedSet() }
        val actual = found.mapValues { (_, fields) -> fields.map { it.name }.toSortedSet() }

        // Name the drift concretely first — a bare two-map diff makes the reader do the work.
        val unpinned = bundles.keys.sorted().flatMap { bundle ->
            val extra = (actual[bundle] ?: emptySet()) - (expected[bundle] ?: emptySet())
            found.getValue(bundle).filter { it.name in extra }.map { f ->
                "  + $bundle.${f.name}: ${f.type}  (${bundles.getValue(bundle)}:${f.line})"
            }
        }
        val stale = bundles.keys.sorted().flatMap { bundle ->
            ((expected[bundle] ?: emptySet()) - (actual[bundle] ?: emptySet()))
                .map { "  - $bundle.$it — pinned but no longer declared" }
        }

        assertEquals(
            expected,
            actual,
            "the composition seam inventory drifted (law: module-architecture, \"Ports are the I/O " +
                "boundary named for the need\").\n" +
                (if (unpinned.isEmpty()) "" else unpinned.joinToString("\n") + "\n") +
                (if (stale.isEmpty()) "" else stale.joinToString("\n") + "\n") +
                "  `+` — a new function-typed field. Ask ONE question: does invoking it leave the " +
                "process? If it reads a platform value, performs a platform effect, or crosses the " +
                "network, it is an adapter written in the composition root — give it a port type " +
                "(reach for an existing port first: three of the five seams this gate was armed for " +
                "joined StagedBytes, PhotoAccessRequester and Clock rather than becoming new ports). " +
                "If it genuinely coordinates within the core, pin it in this file WITH ITS REASON.\n" +
                "  `-` — a pin outlived its field. Delete it in the same commit, so the inventory can " +
                "never describe code that is not there.\n" +
                "  And remember what this gate does NOT see: it constrains what the composition hands " +
                "the core, never what the OS hands the shell, and it reads declarations rather than " +
                "call graphs. Green here is not a claim that no seam crosses.",
        )
    }

    /**
     * A gate that scans nothing passes vacuously — and this one is one moved declaration away from
     * scanning nothing. The floor is [KotlinShellGuardTest]'s: assert the scope is real BEFORE trusting
     * a green scan. `params` itself fails on an absent file or declaration; this pins that what it did
     * parse is a whole constructor rather than the one parameter that survived a broken split.
     */
    @Test
    fun `the gate actually parsed both composition bundles (non-vacuity floor)`() {
        val floors = mapOf("AppPorts" to 30, "UploadPorts" to 10)
        floors.forEach { (bundle, floor) ->
            val all = params(bundle)
            assertTrue(
                all.size >= floor,
                "composition seam gate: parsed only ${all.size} parameters of $bundle (expected at " +
                    "least $floor) — the constructor scan is broken and this gate is passing on nothing",
            )
            assertTrue(
                all.any { isFunctionType(it.type) } && all.any { !isFunctionType(it.type) },
                "composition seam gate: $bundle parsed as all-function or no-function fields — the " +
                    "arrow detection is broken, which fails this gate open in one direction or the other",
            )
        }
        assertEquals(
            bundles.keys.toSortedSet(),
            declaredBundles().toSortedSet(),
            "the set of composition bundles changed. A third `*Ports` bundle in compose/ is a third " +
                "place the shell can hand the core a lambda, and this gate cannot see it until it is " +
                "listed here (with its file) — add it, or fold it into an existing bundle.",
        )
    }

    /**
     * Every pin states a reason, and a real one. The pin's whole job is to carry the judgement: a
     * reasonless entry degrades the inventory into a list of whatever failed the gate last, which is
     * the failure mode [MainLaneContainmentTest]'s allowlist names out loud and this table would reach
     * faster, having more entries.
     */
    @Test
    fun `every pin states a reason`() {
        pins.forEach { (bundle, entries) ->
            entries.forEach { (field, reason) ->
                assertTrue(
                    reason.trim().length >= 40 && !reason.contains("TODO"),
                    "composition seam gate: $bundle.$field is pinned without a real reason. State why " +
                        "invoking it does NOT leave the process — that sentence is the entire value of " +
                        "the pin, and the next reader has nothing else to go on.",
                )
            }
        }
    }

    /**
     * The parser is the load-bearing part of this gate, and both of its failure directions are silent:
     * miss the arrow and a new lambda sails through, mis-split the constructor and everything does.
     * Pinned on a sample rather than on the bundles, so the property is checked even when the pins are
     * clean — including the case that motivated sharing the comment stripper (a KDoc naming a field
     * that no longer exists).
     */
    @Test
    fun `the constructor parser finds function types and ignores prose`() {
        val sample = """
            /** Was `presentPhotoPicker: () -> Unit` until this port absorbed it. */
            class Sample(
                val clock: Clock,
                // a line comment naming ghost: () -> String
                val deviceId: () -> String,
                val albumExcluded: suspend (cutoff: CaptureCutoff) -> Set<String>,
                val uploadSilentPush: () -> (suspend (eventId: String) -> Unit)? = { null },
                val lane: CoroutineContext,
                val log: Logger = Logger.withTag("x"),
                val counts: Map<String, List<Int>> = emptyMap(),
            )
        """.trimIndent()
        val code = ZoneGates.stripComments(sample)
        val open = Regex("""\bclass\s+Sample\s*\(""").find(code)!!.range.last
        val decls = splitTopLevel(code.substring(open + 1, balanced(code, open)!!), ',')
            .map { it.first.trim() }
            .filter { it.isNotBlank() }
            .associate { decl ->
                val colon = indexOfTopLevel(decl, ':')!!
                decl.take(colon).trim().substringAfterLast(' ') to
                    (indexOfTopLevel(decl.substring(colon + 1), '=')
                        ?.let { decl.substring(colon + 1).take(it) } ?: decl.substring(colon + 1)).trim()
            }

        assertEquals(
            setOf("clock", "deviceId", "albumExcluded", "uploadSilentPush", "lane", "log", "counts"),
            decls.keys,
            "the constructor split lost or invented a parameter — nested generics, defaults and a " +
                "trailing comma are all live in the real bundles",
        )
        assertTrue("ghost" !in decls, "a commented-out field was parsed as live — the stripper is not running")
        listOf("deviceId", "albumExcluded", "uploadSilentPush").forEach {
            assertTrue(isFunctionType(decls.getValue(it)), "`${decls[it]}` is a function type and was missed")
        }
        listOf("clock", "lane", "log", "counts").forEach {
            assertTrue(!isFunctionType(decls.getValue(it)), "`${decls[it]}` is not a function type and was flagged")
        }
    }
}
