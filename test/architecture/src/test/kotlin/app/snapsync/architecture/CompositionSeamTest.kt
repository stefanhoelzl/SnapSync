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
 * `AppPorts`, `UploadPorts` and the sub-bundle `UploadRecordPorts` are where the shell hands the core
 * everything it may not build itself.
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
        "UploadPorts" to "domain/compose/src/commonMain/kotlin/app/snapsync/compose/UploadCore.kt",
        "UploadRecordPorts" to "domain/compose/src/commonMain/kotlin/app/snapsync/compose/UploadRecordPorts.kt",
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
     * The admissible reasons are the law's (`module-architecture`, "Ports are the I/O boundary named for the
     * need"): a callback INTO the core's own machinery. A reason claiming a seam "returns a value the
     * composition already holds" is not accepted for a value obtained by a platform read, however cached.
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
            "appDrivenUpload" to
                "a factory for the app's uploader (an AppUploadEngine, whose platform touches are its own " +
                "adapter's). A thunk so the engine is constructed at first use rather than while the graph " +
                "is being assembled",
            "extensionRegistration" to
                "the same, and `null` where this OS does not carry that mechanism at all — the " +
                "nullability IS that OS answer, and it must be a call rather than a value the bundle " +
                "carries so the registration is never constructed where its selector does not exist. " +
                "What it returns reaches the platform only through the UploadExtensionRegistry port",
            "uploaderPin" to
                "reads the rig's per-uploader switch, re-read at every use so it can change without " +
                "rebuilding the graph. `null` in a production build not by convention but by " +
                "CONSTRUCTION: the only writer of the root source behind it is the control channel's boot " +
                "hook, whose source is absent from a build made without the channel's build property",
            "onEventMinted" to
                "hands a minted event id back to the shell's link entry, which forwards it into THIS " +
                "core's join gate — a U-turn through the entry surface so create and a scanned QR take " +
                "one gate, not two. Nothing leaves the process",
        ),
        "UploadPorts" to mapOf(
            "selectionScope" to
                "what discovery may read right now (capability `limited-photo-access`), derived by the " +
                "app composition from current permission plus the in-memory snapshot — a call and not a " +
                "value because the answer changes between cycles. Pure core read",
            "token" to
                "the attestation bearer, read per request from the AttestStore port (extension) or the " +
                "core's own DeviceAttestation (app) — a call because a renewal must be picked up without " +
                "rebuilding the cycle",
        ),
        // DELIBERATELY EMPTY, and that is the entry rather than an omission. A cohesive sub-bundle of
        // AppPorts, holding PORT-typed fields and no lambda at all — so there is nothing here to judge, and
        // a bundle whose inventory is empty must still be listed or the set-of-bundles check below cannot
        // tell "no seams" from "not scanned".
        "UploadRecordPorts" to emptyMap(),
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
        "App.admission" to
            "UploaderProcess.App: the app's admission, bound to AppCore.appUploadAdmission() — grant, selection " +
            "scope and rig pin, all in-process state. The extension's variant is a PhotoGrantRead PORT",
        "AlbumGather.policyFor" to
            "the membership's ONE selection-policy derivation, built in compose/ over the download store and the " +
            "album port — a sibling the gather may not name (feature-blindness)",
        "CreateEvent.onMinted" to
            "hands the minted event to AppPorts.onEventMinted, which routes it into this core's join gate",
        "CollectDiagnosticDump.uploadFacts" to
            "two strings computed from this core's own upload resolution (registrable, admission) for the dump",
        "DownloadController.downloadEnabled" to
            "the membership's direction, three-valued (no membership → null → no arm), derived in compose/ over " +
            "the ConfigSource port the composition already reads",
        "QueuedPhotoDownloadJobs.newTransport" to
            "builds the DownloadTransport PORT around the jobs' own host queue — the object-graph cycle " +
            "AppPorts.newDownloadTransport breaks",
        "QueuedPhotoDownloadJobs.onStaged" to
            "delivers a staged resource to the sibling DownloadController, resolving its lazy when INVOKED so a " +
            "download-only relaunch reaches it too (capability `photo-download`)",
        "JoinEvent.provision" to
            "runs the provision the composition owns (the Provision flow under its entry label, then the album " +
            "gather start) — core machinery the join use-case may not name",
        "LeaveEvent.stopUploads" to
            "the sibling UploadTransitions.onLeave() — feature-blindness; its platform touches are the " +
            "uploaders' own adapters",
        "LeaveEvent.clearLedger" to
            "the ledger reset family, invoked from compose/ where ledger writes are confined (capability " +
            "`sync-ledger`: which code may perform which write); the store is the LedgerStore port",
        "LeaveEvent.notifyLeave" to
            "compose/'s best-effort wrapper over the LeaveNotifier PORT, which logs a failed Result rather " +
            "than failing the leave — the port is where the network crossing is declared",
        "MembershipEntry.stopUploads" to "the sibling UploadTransitions.onLeave() — feature-blindness",
        "MembershipEntry.notifyLeave" to "the same best-effort wrapper as LeaveEvent.notifyLeave, over the LeaveNotifier port",
        "MembershipEntry.loadShareSet" to
            "the sibling ShareSetLoad feature (its listing crosses the DeviceFilesSource port) — feature-blindness",
        "MembershipEntry.saveConfig" to
            "the ConfigStore port's save, handed in so the entry's ordered steps are recorded by its tests " +
            "exactly as they run",
        "MembershipEntry.startUploads" to "the sibling UploadTransitions.onJoin() — feature-blindness",
        "ReconfigureEvent.refreshStatus" to
            "this core's own status refresh (AppCore.refreshStatusSources) — feature-blindness",
        "ReconfigureEvent.armUpload" to "the sibling UploadTransitions.onReconfigure() — feature-blindness",
        "ReconfigureEvent.ensureAlbum" to
            "the sibling AlbumCoordinator's ensure (its PhotoKit touches are the AlbumManager port's)",
        "ReconfigureEvent.gatherAlbum" to "the sibling AlbumGather.start — feature-blindness",
        "ReconfigureEvent.startDownloads" to "the sibling DownloadController.reconcile — feature-blindness",
        "ReconfigureEvent.cancelDownloads" to "the sibling DownloadController.onLeaveOrSwitch() — feature-blindness",
        "ReconfigureEvent.bumpManifestVersion" to
            "the ledger's manifest-version bump, invoked from compose/ where ledger writes are confined " +
            "(capability `sync-ledger`); the store is the LedgerStore port",
        "ResetDeviceState.resetDownloads" to "the sibling DownloadController.onDurableStateReset() — feature-blindness",
        "ReadingLedgerCountsSource.read" to
            "a read-only LedgerStore.assetProgress() mapped to LedgerCounts in compose/, so ledger types never " +
            "reach feature/status (stated at the class); the store is the LedgerStore port",
        "ShareableCountSource.suppressedLocalIds" to
            "the DownloadStore port's suppressed set, the same read the status total and the cycle use",
        "ShareableCountSource.albumExcludedAssetIds" to
            "compose/'s admit-on-doubt read over the AlbumManager PORT — the one wrapper both consumers share",
        "StatusCountsPoller.refreshCheapLocalReads" to "this core's own StatusRefresh.refreshCheapLocalReads()",
        "StatusRefresh.refreshDownloadLine" to "the sibling download status source's refresh — feature-blindness",
        "StatusRefresh.policyFor" to "the membership's ONE selection-policy derivation, built in compose/",
        "BackgroundUploadPump.runCycle" to "the tier's own UploadCycle.run — core machinery",
        "BackgroundUploadPump.onCycleComplete" to "the sibling ledger-counts refresh — feature-blindness",
        "BackgroundUploadPump.mayCreate" to "this core's own app admission, read fresh at each completion",
        "SelectionScopedDiscovery.selectionScope" to "UploadPorts.selectionScope, forwarded — a pure core read",
        "JoinedMembership.policy" to "the membership's ONE selection-policy derivation, built by the entry gate",
        "UploadCycle.readGate" to "uploadCore's own entry-gate translation over the ports (readGate in UploadCore.kt)",
        "UploadCycle.engineFor" to
            "builds the SyncEngine over the gate's config per cycle — core machinery, whose transfer is a port",
        "UploadCycle.onDiscovery" to
            "the sibling DeviceManifestProducer (its publish crosses the ManifestPublisher PORT)",
        "UploadCycle.placeInAlbum" to
            "the sibling AlbumCoordinator.place (its PhotoKit touches are the AlbumManager port's)",
        "UploadTransitions.extensionRegistrable" to
            "model/'s pure registrability fact over the OS fact, the grant and the rig pin",
        "UploadTransitions.appEngine" to "AppPorts.appDrivenUpload, forwarded — a factory for the app's uploader",
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
                "joined StagedBytes, PhotoAccessRequester and Clock rather than becoming new ports). " +
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
                "performs a platform effect or crosses the network, it is a port (a feature may name ports; " +
                "reach for an existing one first). If it is a callback into the core's own machinery — a sibling " +
                "feature the zone law forbids it to name — pin it in `constructorPins` WITH its binding.\n" +
                "  `-` — a pin outlived its parameter. Delete it in the same commit.",
        )
        assertTrue(
            found.size >= 30,
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
        val floors = mapOf("AppPorts" to 30, "UploadPorts" to 10, "UploadRecordPorts" to 2) // the join marker left it
        floors.forEach { (bundle, floor) ->
            assertTrue(
                params(bundle).size >= floor,
                "composition seam gate: parsed only ${params(bundle).size} parameters of $bundle " +
                    "(expected at least $floor) — the constructor scan is broken and this gate is " +
                    "passing on nothing",
            )
        }
        // The arrow detection is exercised on the bundles that carry BOTH kinds of field. It cannot be
        // asserted on `UploadRecordPorts`, whose fields are all ports — which is the whole reason its
        // pinned inventory is empty, and therefore not evidence that the detection is broken.
        listOf("AppPorts", "UploadPorts").forEach { bundle ->
            val all = params(bundle)
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
