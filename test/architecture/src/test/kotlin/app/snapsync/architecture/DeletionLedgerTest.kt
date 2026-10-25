package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The retired dead weight stays dead** (promoted from the migration beacon's deletion-ledger
 * row at the finale, per the beacon's own contract). Each entry below was deliberately deleted
 * during the migration, with its rationale in the corresponding decision record — and each is the
 * kind of thing that grows back innocently (a convenience interface here, a second uploader
 * there). Resurrection is not forbidden forever; it is forbidden *silently*: bringing one back
 * means deleting its row here in the same commit, with the argument in the PR.
 *
 * Patterns quote the retired names, so this guard excludes its own source from the scan (the same
 * self-exclusion the beacon applied).
 */
class DeletionLedgerTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun sources(): List<File> = listOf("adapter", "domain", "app", "test", "ui").flatMap { root ->
        File(repoRoot, root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path && "/src/" in it.path }
            .filterNot { it.name == "DeletionLedgerTest.kt" }
            .toList()
    }

    private fun declared(files: List<File>, pattern: String): List<String> = files
        .filter { Regex(pattern).containsMatchIn(it.readText()) }
        .map { it.toRelativeString(repoRoot) }

    @Test
    fun `the deletion ledger's retired items stay dead`() {
        val files = sources()
        assertTrue(files.isNotEmpty(), "deletion-ledger gate scanned zero sources — the roots moved")
        val toml = File(repoRoot, "gradle/libs.versions.toml").readText()

        val resurrections = buildList {
            if ("zxing" in toml) add("zxing catalog entries (QR is the OS camera's job — delete-dead-weight)")
            if ("kotlincrypto" in toml) add("kotlincrypto catalog entries (no client-side crypto — delete-dead-weight)")
            if (File(
                    repoRoot,
                    "capability",
                ).exists()
            ) {
                add("a capability/ tree (the zone died with the migration — features are :domain packages)")
            }
            // RETIRED ROWS — `LedgerReader`, `LoggingPushReceiver`, `EventMetadataSource`.
            //
            // All three retired a declaration for being **single-implementation interface ceremony**.
            // That judgement did not survive: `enforce-port-boundary` brought `LeaveNotifier` back under
            // this ledger's own reversal clause, because a **port** is not an interface justified by a
            // second implementation — it is the declared boundary where the core stops and an external
            // system begins, and deleting the interface made the composition carry the crossing as an
            // opaque closure instead, invisible to every gate that reads types.
            //
            // `EventMetadataSource` is an HTTP client interface, which is that same boundary by that same
            // definition. Keeping its row would have this guard block a CORRECT change, and a ledger row
            // that argues against the law is worse than no row. The rows are retired rather than left to
            // be discovered one reversal at a time.
            // RETIRED ROW — `interface LeaveNotifier` ("the class is the seam", delete-dead-weight).
            // Deliberately resurrected by `enforce-port-boundary`, per this gate's own contract: the
            // 2026-07-17 judgement was that a single-implementation interface is ceremony, and that
            // reasoning does not survive the law it collides with. A port is not justified by a second
            // implementation — it is the declared boundary where the core stops and an external system
            // begins (`docs/architecture.md`, "Ports are the I/O boundary named for the need"). With the
            // interface gone, the composition handed the core a `suspend (eventId) -> Unit` closure over
            // the adapter instead, which is the same crossing made invisible to every gate that reads
            // types. The row is deleted rather than narrowed because there is nothing left to keep dead.
            if (declared(files, "enum class Arrow" + "Level").isNotEmpty() &&
                declared(files, "enum class Arrow" + """\b""").isNotEmpty()
            ) {
                add("Arrow/ArrowLevel duplicate enum (unified at migration step 9)")
            }
            // The device-manifest accumulator: a second durable structure tracking the same
            // deletion-aware asset set as the upload ledger, with different columns and the same
            // pruning signals. The ledger already had to be right about all of it — a wrong row
            // re-uploads a library or hides a photo forever — so the accumulator could only ever
            // disagree. The manifest is a projection of the ledger now (capability `photo-sharing`).
            declared(files, "fun load" + "Accumulator").forEach {
                add("the device-manifest accumulator in $it (the manifest projects from the ledger)")
            }
            // The world (`:test:world`) and its mini-edge: a second composition of the app, beside the JVM root, and a
            // second backend, beside the real `api/` and the backend mock — each one more answer to keep in step with
            // the first, and the world's operator faces reached past the protocol into the composed core. The JVM root
            // (`:app:jvm`) over the mocks is the one off-device composition; its tests are rig tests (11g2b).
            if (File(
                    repoRoot,
                    "test/world/build.gradle.kts",
                ).exists()
            ) {
                add("a :test:world module (the JVM root over the mocks replaced it — 11g2b)")
            }
            declared(files, """(class|object) (MiniEdge|BackendStore)\b""").forEach {
                add("the mini-edge in $it (the backend mock and the real api/ are the two backends — 11g2b)")
            }
            // The forge: a status screen over canned inputs, as a binary for the marketing screenshots and a desktop
            // harness for review. It could show a frame the app never reached, and it rotted between dispatches. The
            // raws come from the real app over launch adapters, and review is the world harness's (12).
            if (File(
                    repoRoot,
                    "app/ios/forge",
                ).exists()
            ) {
                add("an :app:ios:forge module (the raws come from the real app — 12)")
            }
            if (File(
                    repoRoot,
                    "iosApp/SnapSyncForge",
                ).exists()
            ) {
                add("a SnapSyncForge Xcode target (the raws come from the real app — 12)")
            }
            declared(files, """fun (forgeStatusHost|ForgeHarnessRoot)\b""").forEach {
                add("a forged status host in $it (every UI state is reached through the world harness's levers — 12)")
            }
            // PRODUCTION uploaders only. The retired item was a second *uploader*; the repo names a
            // test after its subject (`HttpEnrollmentTest`), so `class \w*Enrollment` matches the test
            // of the surviving uploader as surely as a resurrected one. Narrowed rather than the row
            // deleted: a real second uploader in production source still trips this.
            val enrollments =
                declared(files.filterNot { it.name.endsWith("Test.kt") }, """class \w*""" + "Enrollment")
            if (enrollments.size > 1) {
                add("Enrollment ×${enrollments.size} (exactly one uploader serves all): ${enrollments.sorted()}")
            }
            // The pre-v0.1 Keychain relics: the unscoped device-id slot the app adopted from before minting, the
            // pre-App-Group album-map slot, and the in-place protection upgrade. Every device has run a build that
            // writes the shared slot background-readable, so each was a branch no installed device can reach.
            declared(files, """\bDEVICE_ID_""" + "LEGACY\\b").forEach {
                add("the legacy device-id slot in $it (every device holds its id in the shared slot)")
            }
            declared(files, """\bALBUM_MAP_""" + "LEGACY\\b").forEach {
                add("the legacy album-map slot in $it (its one-shot migration is retired)")
            }
            declared(files, """\bmigrate""" + "Protection\\b").forEach {
                add("the in-place Keychain protection upgrade in $it (every item is written background-readable)")
            }
            // The other shims for state no ≥0.4 device holds: the start-up removal of the join marker a ≤0.3 build
            // wrote, the album map's Keychain-to-App-Group migration, and the millisecond-tolerant bound parse.
            declared(files, """fun remove""" + "OrphanedJoinMarker\\b").forEach {
                add("the orphaned join-marker removal in $it (no ≥0.4 device holds the key)")
            }
            declared(files, """\b(fun album""" + "MapSource|AlbumMap" + "Source)\\b").forEach {
                add("the album map's Keychain migration in $it (no ≥0.4 device holds a Keychain map)")
            }
            declared(files, """fun parse""" + "Tolerant\\b").forEach {
                add("the millisecond-tolerant bound parse in $it (every stored bound is second precision)")
            }
        }
        assertTrue(
            resurrections.isEmpty(),
            "retired dead weight resurrected — delete it again, or delete its ledger row here in " +
                "the same commit with the argument in the PR:\n  " + resurrections.joinToString("\n  "),
        )
    }

    /**
     * The open-cells list: the port-grid cells no clause covered yet, scaffolding while the gap clauses were written.
     * Emptied and deleted; every grid cell is covered with no exception (`ClauseCoversTest`, which also refuses any
     * other file beside the contracts module's sources and recordings). Same reversal clause as the ledger above.
     */
    @Test
    fun `the open-cells list stays dead`() {
        val resurrections = buildList {
            if (File(repoRoot, "test/contracts/open-" + "cells.txt").exists()) add("test/contracts/open-cells.txt")
            declared(sources(), """\b(open""" + "Cells|OPEN_" + "CELLS)" + "\\w*\\b").forEach {
                add("an open-cells exemption in $it")
            }
        }
        assertTrue(
            resurrections.isEmpty(),
            "every grid cell is covered, with no list of exceptions — write the clause instead:\n  " +
                resurrections.joinToString("\n  "),
        )
    }

    /**
     * Trust before its layers (phase 4.1): one identity class with a role flag, where two types now say which process
     * may mint; a throwing token store and an attestation service that decided inside the data it kept; the extension's
     * credential under its old name; a gateway flag where a method now says a request must not go out without a token;
     * a second, non-publishing refresh; and the dev backends' fallback bearer — a token attached to any request that
     * carried none — where every caller now attests and both backends refuse a gated request without one, as deployed.
     */
    @Test
    fun `trust's retired items stay dead`() {
        val files = sources()
        val resurrections = buildList {
            declared(files, """enum class DeviceIdentity""" + "Role\\b").forEach {
                add("the identity role flag in $it (two identity types say which may mint — 4.1)")
            }
            val pre = """(class|interface) (DeviceAttestation|ExtensionCredential|AttestStore|PersistedDevice""" +
                "Identity)\\b"
            declared(files, pre).forEach { add("trust's pre-layer types in $it (AttestingCredential & co — 4.1)") }
            val calls = """\bobtain""" + "First\\b|fun ensure" + "Fresh\\b|fun tokenExpiry" + "Seconds\\b"
            declared(files, calls).forEach {
                add("a retired trust call in $it (sendObtainingFirst; one refresh; TokenText reads the expiry — 4.1)")
            }
            declared(files, """\bfallback""" + "Bearer\\b").forEach {
                add("a fallback bearer in $it (every test host attests — 4.1)")
            }
            if (File(repoRoot, "api/src/dev/fallback.ts").exists()) {
                add("api/src/dev/fallback.ts (the dev server attaches no token — 4.1)")
            }
        }
        assertTrue(resurrections.isEmpty(), "retired items came back: $resurrections")
    }

    /**
     * The control protocol's string commands (3g-a): the `/os/<root>/<member>` entry routes and their trigger groups, the
     * `/device` verbs and their lever table, the operating system's record as one untyped grab-bag, and the client's
     * calls of them. Each became a typed service — `os`, `played-os`, `extension-host`, and a value read on the service
     * owning each fact — so a string route back is a second, untyped way to drive the same delivery, which no service's
     * refusals classify and no compiler checks.
     */
    @Test
    fun `the control protocol's string commands stay dead`() {
        val files = sources()
        val resurrections = buildList {
            val plumbing = """(class|interface) (Trigger""" + "Group|RigTrig" + "ger|RigCom" + "mand|CommandRe" + "sult)\\b"
            declared(files, plumbing).forEach {
                add("the string routes' plumbing in $it (the typed services replaced it — 3g-a)")
            }
            val tables = """\b(trigger""" + "Groups|device" + "Commands|honoured" + "Levers|lever" + "Refusals)\\b"
            declared(files, tables).forEach {
                add("a string-route table in $it (each host serves its typed services — 3g-a)")
            }
            val calls = """\bfun (device""" + "Verb|os" + "Record|os" + "AtRelease)\\b|\\bclass Os" + "Record\\b"
            declared(files, calls).forEach {
                add("a string verb's call or the untyped OS record in $it (typed reads on the owning service — 3g-a)")
            }
            declared(files, """/os/(app|photo""" + "kit-ext)/|device/os-" + "record").forEach {
                add("a string route in $it (`os`, `played-os`, `extension-host` and the typed reads — 3g-a)")
            }
            if (File(repoRoot, "test/rig/src/commonMain/kotlin/app/snapsync/rig/Mock" + "Levers.kt").exists()) {
                add("MockLevers.kt (the levers are the typed services' — 3g-a)")
            }
        }
        assertTrue(resurrections.isEmpty(), "retired items came back: $resurrections")
    }

    /**
     * The membership before its layers (phase 4.2): a service that read and wrote the membership as one `EventConfig`
     * and a refresh that decided inside it; the old zones' join, leave, settings and switch rules, where use cases and
     * entry points now run them; an event that could close, where nothing closes; an invite link's hints that could
     * join headlessly; and the screen's join-details read, switch question and rename reset, where requests answer on
     * their statuses.
     */
    @Test
    fun `the membership's retired items stay dead`() {
        val files = sources()
        val resurrections = buildList {
            val pre = """(class|interface|object) (Config""" + "Service|Membership" + "Entry|Membership" +
                "Refresh|Quiet" + "Switch|Event" + "Completion|ResetDevice" + "State|DeviceEn" + "roller)\b"
            declared(files, pre).forEach { add("the membership's pre-layer types in $it (the layers' — 4.2)") }
            declared(files, """val closed""" + ": Boolean\b").forEach {
                add("a closed membership in $it (nothing closes; EventOver is the join's refusal — 4.2)")
            }
            declared(files, """auto""" + "Join\b|\binviteLink" + "Hints\b").forEach {
                add("an invite link's hints in $it (joining happens only on confirmation — 4.2)")
            }
            declared(files, """fun (loadJoin""" + "Details|switchLoses" + "Nothing|reset" + "Rename)\b").forEach {
                add("a door the requests replaced in $it (answered on their statuses — 4.2)")
            }
        }
        assertTrue(resurrections.isEmpty(), "retired items came back: $resurrections")
    }

    /**
     * The device runs' shell scripts (phase 13): the `journeys (ios|android)` jobs run the `journeys` program
     * (`:test:journeys`) over the typed client, waiting on the device's events — so a script back is a second way to
     * run them, with waits of its own and no tests. The shell left is the builds and the daemon stop
     * (`scripts/journeys-build`).
     */
    @Test
    fun `the device runs' shell scripts stay dead`() {
        val retired = listOf(
            "scripts/android" + "-journeys" to "the Android run is `journeys run --platform android`",
            "scripts/android" + "-force-stop-check" to "the force-stop check is `ForceStopJourney`, run on every push",
            "scripts/sim" + "-contracts" to "the iOS run is `journeys run --platform ios`",
            "scripts/sim" + "-settle" to "the settle is `journeys settle`, its daemons in `device/Settle.kt`",
            "scripts/lib/sim" + ".sh" to "`journeys find-device` and `Settle.kt`'s daemon list",
            "scripts/lib/journeys" + ".sh" to "the program keeps the stages and runs the journeys",
            "scripts/await" + "-photo-migration.py" to "`PhotoMigrations`, tested in `build`",
            "scripts/await_photo" + "_migration_test.py" to "`PhotoMigrationsTest`",
        )
        val resurrections = retired.filter { (path, _) -> File(repoRoot, path).exists() }
            .map { (path, now) -> "$path ($now — phase 13)" }
        assertTrue(resurrections.isEmpty(), "retired items came back: $resurrections")
    }
}
