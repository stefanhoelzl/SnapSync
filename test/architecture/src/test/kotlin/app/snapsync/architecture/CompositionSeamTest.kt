package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every function-typed field of the composition bundles is pinned, with its reason** (capability
 * `docs/architecture.md`, requirement "The composition seam gate"; law: `docs/architecture.md`, "Ports
 * are the I/O boundary named for the need"). Decision record: `changes/…/enforce-port-boundary`
 * (D1, D5, D9).
 *
 * `AppPorts`, `ExtensionPorts`, `ProcessPorts` and the device bundles are where the shell hands the core
 * everything it may not build itself — ports and nothing else since `PortBundleTest` holds them to it, so their
 * pinned inventories below are empty, and stay listed so a lambda slipping back in is seen here too.
 * Most of what crosses is a port, and a port is a *declared* boundary — a reader, and every gate that
 * reads types, can see the process end there. A function-typed field declares nothing: it is equally
 * the shape of pure in-core coordination and of an inline adapter written in the composition root. Five
 * of them were the latter, and one (`now = { NSDate()… }`) sat beside the `Clock` port built for
 * exactly that value, bypassed and invisible.
 *
 * **A pin, not an analysis — because the property is not decidable from source** (D1). Whether a
 * lambda's invocation leaves the process cannot be read off its type: two `() -> String` seams are
 * type-identical while one resolves an App-Group container and the other returns a constant. (The example
 * this sentence used to give — `deviceId`, pinned as "a value the composition already holds" — was itself
 * wrong: its first call was a Keychain read, and it is the `DeviceIdentity` port now.) Nor is it decidable from the body, which is in another
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
 * That surface is out of scope here, and no green run makes a claim about it. (`scheduleBackstop` once sat
 * on that line; it is the `BackgroundScheduler` port now, because the submit it hid could fail silently.)
 *
 * Two narrower blind spots, so a green run is not over-read either:
 *  - **It reads a declaration, not a call graph.** A pinned lambda whose body is later rewritten to
 *    reach out of the process still passes: the pin's reason goes stale silently, because nothing here
 *    re-derives it. The reason is a receipt for a judgement, not a proof of one.
 *  - **It is scoped to constructors in `feature/` and `compose/`** — the bundles, and every class the core
 *    builds (`harden-seam-bug-classes` widened it from the bundles alone, where features taking a lambda
 *    for a port read went unseen). A platform touch smuggled in as a field of some *other* type — a data
 *    class carrying a lambda, say — is not function-typed at this level and is not seen. The bundle set
 *    itself is pinned (below) so a *further* bundle cannot appear unnoticed — `UploadRecordPorts` arrived
 *    that way and is listed with an empty inventory, which is a statement rather than an omission.
 *
 * The tone is [MainLaneContainmentTest]'s, deliberately: *it contains a lane; it does not decide
 * whether a call blocks.* This one pins a seam inventory; it does not decide whether a seam crosses.
 */
class CompositionSeamTest {

    /** The composition bundles (law "One shared composition") → the `compose/` file declaring each. */
    private val bundles = mapOf(
        "AppPorts" to "domain/compose/src/commonMain/kotlin/app/snapsync/compose/SnapSyncApp.kt",
        "ExtensionPorts" to "domain/compose/src/commonMain/kotlin/app/snapsync/compose/ExtensionCore.kt",
        "ProcessPorts" to "domain/compose/src/commonMain/kotlin/app/snapsync/compose/ProcessComposition.kt",
        "AppDevicePorts" to "domain/compose/src/commonMain/kotlin/app/snapsync/compose/DevicePorts.kt",
        "ExtensionDevicePorts" to "domain/compose/src/commonMain/kotlin/app/snapsync/compose/DevicePorts.kt",
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
     * The admissible reasons are the law's (`docs/architecture.md`, "Ports are the I/O boundary named for the
     * need"): a callback INTO the core's own machinery. A reason claiming a seam "returns a value the
     * composition already holds" is not accepted for a value obtained by a platform read, however cached.
     */
    private val pins: Map<String, Map<String, String>> = mapOf(
        // DELIBERATELY EMPTY, all five, and that is the entry rather than an omission: a composition bundle holds
        // ports and nothing else (`PortBundleTest`). What used to be pinned here — the app uploader's factory, the
        // minted-event hook, the cycle's token and selection reads — is built by the composition itself now, and
        // pinned below as the constructor seam it became. A bundle whose inventory is empty must still be listed, or
        // the set-of-bundles check below cannot tell "no seams" from "not scanned".
        "AppPorts" to emptyMap(),
        "ExtensionPorts" to emptyMap(),
        "ProcessPorts" to emptyMap(),
        // An iOS or Android root's real adapters, one `Lazy` PORT each — the bundle its build's adapter set hands back,
        // a launch-time adapters' on a rig build (`docs/testing.md`). A lazy is a port built on first use, not a lambda
        // the core calls.
        "AppDevicePorts" to emptyMap(),
        "ExtensionDevicePorts" to emptyMap(),
    )

    /**
     * Every OTHER class the core builds (`feature/` and `compose/`): `Class.param` → its binding, and why that
     * binding is a callback into the core rather than a platform touch. Discovered, not listed — a new
     * function-typed constructor parameter anywhere in those zones fails until it is pinned here.
     *
     * The recurring shape is feature-blindness: a feature may not name a sibling feature (law "Zones inside the
     * core"), so `compose/` hands it the sibling as a lambda. Such a lambda reaches the platform, if at all,
     * only through the sibling's own ports.
     */
    private val constructorPins: Map<String, String> = mapOf(
        "CollectDiagnosticDump.uploadFacts" to
            "two strings computed from this core's own upload resolution (registrable, admission) for the dump",
        "CollectDiagnosticDump.appFacts" to
            "the bug report's facts only this core holds — the device id (never minted), the clock's zone, the process " +
            "footprint and the selection snapshot's size — each a direct read mapped to a Fact by model/",
        "CollectDiagnosticDump.refusalFacts" to
            "the latest refused attestation's facts the credential's status holds in memory (AttestationStatus." +
            "refusalFacts) — read for a report offered from \"Report this\" (capability `privacy-security`)",
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
        KotlinDecls.closing(code, open) ?: error("composition seam gate: unbalanced `class $bundle(` in $path")
        return KotlinDecls.paramsAt(code, open, bundle).map { Field(it.name, it.type, it.line) }
    }

    /**
     * A type is function-typed iff it carries a `->` at nesting depth 0 — `() -> (X) -> Y` included — or is a
     * nullable function type `(… -> …)?`, whose arrow sits one level down and which this gate used to miss.
     */
    private fun isFunctionType(type: String): Boolean = KotlinDecls.isFunctionType(type)

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
                "joined StagedBytes and Clock rather than becoming new ports). " +
                "If it genuinely coordinates within the core, pin it in this file WITH ITS REASON.\n" +
                "  `-` — a pin outlived its field. Delete it in the same commit, so the inventory can " +
                "never describe code that is not there.\n" +
                "  And remember what this gate does NOT see: it constrains what the composition hands " +
                "the core, never what the OS hands the shell, and it reads declarations rather than " +
                "call graphs. Green here is not a claim that no seam crosses.",
        )
    }

    /** Every function-typed constructor parameter of a non-bundle class declared in `feature/` or `compose/`. */
    private fun constructorInventory(): Map<String, String> {
        val files = ZoneGates.requireZone("composition-seam", "feature") + ZoneGates.requireZone("composition-seam", "compose")
        return files.flatMap { file ->
            KotlinDecls.constructorParams(ZoneGates.stripComments(file.readText()))
                .filter { it.owner !in bundles && isFunctionType(it.type) }
                .map { "${it.owner}.${it.name}" to "${file.path.substringAfter("/domain/")}:${it.line}" }
        }.toMap()
    }

    @Test
    fun `every function-typed constructor parameter in feature and compose is pinned, exactly`() {
        val found = constructorInventory()
        val unpinned = (found.keys - constructorPins.keys).sorted().map { "  + $it  (${found.getValue(it)})" }
        val stale = (constructorPins.keys - found.keys).sorted().map { "  - $it — pinned but no longer declared" }
        assertTrue(
            unpinned.isEmpty() && stale.isEmpty(),
            "the core's constructor seam inventory drifted (law: module-architecture, \"Ports are the I/O " +
                "boundary named for the need\").\n" +
                (unpinned + stale).joinToString("\n") + "\n" +
                "  `+` — a new function-typed constructor parameter. If its binding reads a platform value, " +
                "performs a platform effect or crosses the network, it is a port, reached through a service (a feature sees services, never " +
                "ports; reach for an existing one first). If it is a callback into the core's own machinery — a sibling " +
                "feature the zone law forbids it to name — pin it in `constructorPins` WITH its binding.\n" +
                "  `-` — a pin outlived its parameter. Delete it in the same commit.",
        )
        // The floor is the true count, minus nothing: 28 since the selection moved into the layers (4.4a), which
        // removed the albumExclusions / selectionScope / suppressedLocalIds / albumExcludedAssetIds lambdas — the
        // read is now OwnPhotos', a service, not a seam. 26 since the small concepts moved (4.8): the heartbeat's
        // facts (WakeRearm.facts) and the extension's registrability (UploadTransitions.extensionRegistrable) are
        // types now. 19 since receiving moved (4.5): the download arm's direction (DownloadArm.enabled), the controller's
        // import readiness, event album and album callback, the received status source's event and direction, and the
        // adoption's record went with the controller — receiving's steps are use cases now. 9 since the uploads
        // moved into the layers (4.4b): the cycle's gate, engine, discovery and album hooks, the admissions, the final
        // share's publish and the transitions' engine are typed steps now — and 4.7 the gather's policy lambda, which reads
        // the selection through `OwnAlbumPhotos` now. 4 since the status moved (4.9): the ledger counts' read, the poll's
        // group and the refresh's download line and contribution went with the poller and the refresh — the screen's
        // counts follow the stores' change feed. 3 with the command door (4.9): a create is answered on its status, so
        // the minted event's hook (CreateEvent.onMinted) went. Lower it only with a removal like those, never to pass.
        assertTrue(
            found.size >= 3,
            "composition seam gate: the constructor scan found only ${found.size} function-typed parameters in " +
                "feature/ and compose/ — the scan is broken and this gate is passing on nothing",
        )
    }

    /**
     * A gate that scans nothing passes vacuously — and this one is one moved declaration away from
     * scanning nothing. The floor is [KotlinShellGuardTest]'s: assert the scope is real BEFORE trusting
     * a green scan. `params` itself fails on an absent file or declaration; this pins that what it did
     * parse is a whole constructor rather than the one parameter that survived a broken split.
     */
    @Test
    fun `the gate actually parsed every composition bundle (non-vacuity floor)`() {
        val floors = mapOf(
            "AppPorts" to 20,
            "ExtensionPorts" to 9,
            "ProcessPorts" to 7,
            "AppDevicePorts" to 20,
            "ExtensionDevicePorts" to 10,
        )
        floors.forEach { (bundle, floor) ->
            assertTrue(
                params(bundle).size >= floor,
                "composition seam gate: parsed only ${params(bundle).size} parameters of $bundle " +
                    "(expected at least $floor) — the constructor scan is broken and this gate is " +
                    "passing on nothing",
            )
        }
        // The arrow detection itself is exercised on a sample (`the constructor parser finds function types and
        // ignores prose`): every bundle here is all-port, which is the whole reason its pinned inventory is empty.
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
        constructorPins.forEach { (seam, reason) ->
            assertTrue(
                reason.trim().length >= 40 && !reason.contains("TODO"),
                "composition seam gate: $seam is pinned without a real reason — state its binding and why it " +
                    "is a callback into the core rather than a platform touch.",
            )
        }
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
                val onEdit: (() -> Unit)?,
                val lane: CoroutineContext,
                val log: Logger = Logger.withTag("x"),
                val counts: Map<String, List<Int>> = emptyMap(),
            )
        """.trimIndent()
        val code = ZoneGates.stripComments(sample)
        val open = Regex("""\bclass\s+Sample\s*\(""").find(code)!!.range.last
        val decls = KotlinDecls.paramsAt(code, open, "Sample").associate { it.name to it.type }

        assertEquals(
            setOf("clock", "deviceId", "albumExcluded", "uploadSilentPush", "onEdit", "lane", "log", "counts"),
            decls.keys,
            "the constructor split lost or invented a parameter — nested generics, defaults and a " +
                "trailing comma are all live in the real bundles",
        )
        assertTrue("ghost" !in decls, "a commented-out field was parsed as live — the stripper is not running")
        listOf("deviceId", "albumExcluded", "uploadSilentPush", "onEdit").forEach {
            assertTrue(isFunctionType(decls.getValue(it)), "`${decls[it]}` is a function type and was missed")
        }
        listOf("clock", "lane", "log", "counts").forEach {
            assertTrue(!isFunctionType(decls.getValue(it)), "`${decls[it]}` is not a function type and was flagged")
        }
    }
}
