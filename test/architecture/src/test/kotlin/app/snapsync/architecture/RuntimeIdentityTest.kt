package app.snapsync.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Runtime identity is pinned** (capability `architecture-guards`; decision record:
 * `pin-runtime-identity-and-zone-gates`).
 *
 * Every literal here is a string the OS or the installed base holds on its side — App-Group
 * container id, Keychain (service, account) pairs, `NSUserDefaults` keys, DB filenames, the
 * device-manifest layout, OS-registered BGTask/URLSession identifiers, framework `baseName`s.
 * Changing one strands or corrupts state on devices already in the field; the worst case (the
 * device-id pair) mints a new device identity and corrupts the event union for every member of an
 * event, remotely unfixably. The migration moves every file these literals live in, and nothing
 * else asserts them (they are `iosMain` defaults, invisible to the JVM loop).
 *
 * Each pin asserts **exactly one** occurrence in production Kotlin with the exact value — so any
 * move that drops, duplicates, or re-values a literal fails this build, and future drift stays
 * single-sited. Non-Kotlin surfaces (entitlements, `Info.plist`, `build.gradle.kts`) carry their
 * own pinned counts; the BGTask ids are pinned in BOTH Kotlin and `Info.plist`, because drift
 * between the two silently kills that background tier (the OS rejects an unpermitted submit and
 * nothing raises).
 *
 * The pin inventory is the spec's (`openspec/specs/architecture-guards/spec.md`): adding, removing,
 * or re-valuing a pin is a spec delta, deliberately.
 */
/**
 * The shared Keychain access group, as production Kotlin must state it. Held here as a literal
 * rather than imported: this guard is JVM-only and the constant lives in an `iosMain` source set, so
 * the pin is — deliberately — a text assertion about source, exactly like every other pin here.
 */
private const val SHARED_ACCESS_GROUP = "E9Z8BADH58.app.snapsync.shared"

class RuntimeIdentityTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    /**
     * Production Kotlin: every `.kt` under a main source set (`src/<x>Main/` or `src/main/`),
     * excluding the test-only `test/` tree and build output. The walk derives from the repository
     * structure — no hand-maintained module list — so code born in a new top-level root (e.g.
     * `adapter/` at migration step 4) is in scope with zero edits here.
     */
    private fun productionKotlin(): List<File> = repoRoot.walkTopDown()
        .onEnter { dir ->
            dir.name != "build" && !dir.name.startsWith(".") &&
                !(dir.parentFile == repoRoot && dir.name == "test")
        }
        .filter { it.isFile && it.extension == "kt" && it.isInMainSourceSet() }
        .toList()

    private fun File.isInMainSourceSet(): Boolean {
        val segments = path.replace('\\', '/').split('/')
        val srcIdx = segments.lastIndexOf("src")
        if (srcIdx == -1 || srcIdx + 1 >= segments.size) return false
        val sourceSet = segments[srcIdx + 1]
        return sourceSet == "main" || sourceSet.endsWith("Main")
    }

    private fun buildFiles(): List<File> = repoRoot.walkTopDown()
        .onEnter { dir -> dir.name != "build" && !dir.name.startsWith(".") }
        .filter { it.isFile && it.name == "build.gradle.kts" }
        .toList()

    /** Quoted Kotlin string literals: exactly one production occurrence each. */
    private val kotlinLiterals = listOf(
        "group.app.snapsync",
        "discovery.changeToken",
        "rejoin.joinedEventId",
        "app.snapsync.album.map",
        "ledger.db",
        "downloads.db",
        "eventconfig.json",
        "device-manifest",
        // NB `accumulator.json` was pinned here until the device manifest became a projection of the
        // upload ledger (capability `sync-ledger`). The file is gone, so pinning it would fail the
        // "nowhere — a move dropped or re-valued it" arm forever; `DeletionLedgerTest` keeps the
        // accumulator itself from growing back.
        "last-uploaded.json",
        "app.snapsync.upload.heartbeat",
        "app.snapsync.download.backstop",
        "app.snapsync.upload.session",
        "app.snapsync.download.bg",
        // The shared Keychain access group (capability `device-identity`). It is runtime identity in
        // the same sense as a service or account: re-valuing it addresses a DIFFERENT real item, and
        // does so silently — every read still succeeds and simply returns something else. That is
        // precisely how the app and the upload extension came to hold two different device ids.
        SHARED_ACCESS_GROUP,
    )

    /**
     * Keychain seats that legitimately search **without** naming an access group, pinned as an exact
     * inventory.
     *
     * Unscoped search is not forbidden — it is *bounded*. The attest pair and album map are left
     * unscoped deliberately: the attest token demonstrably works cross-process today, and the album
     * map is a self-healing cache. What must not happen is a *new* unscoped seat appearing by
     * default, which is how implicit placement spread in the first place.
     *
     * The config seat (`app.snapsync.config`/`eventconfig`) left this set with `KeychainConfigReader`
     * — the Stage-2 change deleted the read-only legacy fallback that was its only justification for
     * searching unscoped (capability `event-rejoin-reconciliation`). Because the set is exact in both
     * directions, reconstructing that seat **unscoped** fails this gate. Stated blind spot:
     * reconstructing it **scoped** would not — scoped sites are only checked for the device-id seat's
     * presence, never pinned as a set — which is narrow, since a scoped read cannot find the unscoped
     * items pre-11a builds wrote, the only thing such a seat could be after.
     *
     * Adding, removing, or re-scoping an entry here is a spec delta to `architecture-guards`.
     */
    private val unscopedKeychainSeats = setOf(
        "app.snapsync.attest" to "token",
        "app.snapsync.attest" to "keyid",
        "app.snapsync.album" to "albummap",
    )

    /**
     * Keychain entries, pinned as (service, account) PAIRS — the pair is the unit of identity, so a
     * cross-swap of accounts between services fails even though every individual string survives.
     */
    private val keychainPairs = listOf(
        "app.snapsync.deviceid" to "deviceid",
        // NB the config pair (app.snapsync.config, eventconfig) was pinned here until the Stage-2
        // change deleted the read-only legacy fallback (KeychainConfigReader), which was its one
        // seat. It now appears in production Kotlin NOWHERE, which an exactly-once pin cannot
        // express — pinning it would fail the "nowhere" arm for ever. The config's runtime identity
        // is carried by the "eventconfig.json" literal above; a reconstructed unscoped seat is
        // caught by the unscoped inventory (capability event-rejoin-reconciliation).
        "app.snapsync.attest" to "token",
        "app.snapsync.attest" to "keyid",
        "app.snapsync.album" to "albummap",
    )

    private val bgTaskIds = listOf("app.snapsync.upload.heartbeat", "app.snapsync.download.backstop")

    private val baseNames = listOf("SnapSyncKit", "SnapSyncUploadKit")

    /**
     * The entitlements files that SIGN a shipped binary. The Keychain access group is cross-checked
     * against these two and only these two: they are the pair that must agree, because the app and the
     * extension address the same item through them.
     */
    private val signingEntitlementsFiles = listOf(
        "iosApp/iosApp/iosApp.entitlements",
        "iosApp/BackgroundUploadExtension/BackgroundUploadExtension.entitlements",
    )

    /**
     * Every entitlements file declaring the App Group — the two signing ones plus the ad-hoc
     * SIMULATOR plist, which signs no shipped binary but gives a simulator build its container.
     *
     * The third is the surface most likely to be forgotten in a rename, precisely because no shipped
     * build fails when it is wrong: the simulator simply loses its container, which reads as the app
     * being broken rather than as a rename being incomplete.
     */
    private val appGroupEntitlementsFiles =
        signingEntitlementsFiles + "iosApp/Configuration/simulator.entitlements"

    private fun occurrences(files: List<File>, needle: String): List<String> = files.flatMap { file ->
        file.readText().lineSequence().withIndex()
            .filter { (_, line) -> needle in line }
            .map { (i, _) -> "${file.toRelativeString(repoRoot)}:${i + 1}" }
            .toList()
    }

    private fun assertExactlyOnce(what: String, found: List<String>) {
        assertTrue(
            found.size == 1,
            "$what must appear exactly once; found ${found.size}:" +
                (if (found.isEmpty()) " (nowhere — a move dropped or re-valued it)"
                 else "\n  ${found.joinToString("\n  ")}") +
                "\nThis literal is runtime identity the installed base depends on. If the change is " +
                "intentional, it is a spec delta to architecture-guards, not a casual edit.",
        )
    }

    @Test
    fun `every runtime-identity literal appears exactly once in production Kotlin`() {
        val sources = productionKotlin()
        assertTrue(sources.isNotEmpty(), "production Kotlin scan resolved zero files — the walk is broken")
        for (literal in kotlinLiterals) {
            assertExactlyOnce("Kotlin literal \"$literal\"", occurrences(sources, "\"$literal\""))
        }
    }

    @Test
    fun `every keychain (service, account) pair appears exactly once`() {
        val sources = productionKotlin()
        assertTrue(sources.isNotEmpty(), "production Kotlin scan resolved zero files — the walk is broken")
        for ((service, account) in keychainPairs) {
            val pair = Regex("""service\s*=\s*"${Regex.escape(service)}",\s*account\s*=\s*"${Regex.escape(account)}"""")
            val found = sources.flatMap { file ->
                pair.findAll(file.readText()).map { file.toRelativeString(repoRoot) }.toList()
            }
            assertExactlyOnce("Keychain pair (service=$service, account=$account)", found)
        }
    }

    /**
     * The access group is the one pinned literal assembled from THREE surfaces that are edited
     * independently — Kotlin, the generated `Deployment.xcconfig`, and the two entitlements files. Pinning the Kotlin
     * value alone would not catch a team-id change or an entitlements rename, and the resulting
     * mismatch is invisible at runtime: the item is written to a real group that simply is not the
     * one the other process reads.
     */
    @Test
    fun `the shared Keychain access group agrees across Kotlin, TEAM_ID and both entitlements`() {
        // TEAM_ID moved into the GENERATED Deployment.xcconfig (capability
        // `deployment-configuration`): it is Apple identity, resolved from the deployment alongside the
        // bundle id, so the backend's copy and this one can no longer disagree.
        val xcconfig = File(repoRoot, "iosApp/Configuration/Deployment.xcconfig")
        assertTrue(
            xcconfig.isFile,
            "Deployment.xcconfig is missing — run `python3 scripts/resolve-deployment.py <deployment>`; " +
                "the renderings are generated, never committed",
        )
        val teamId = Regex("""^\s*TEAM_ID\s*=\s*(\S+)\s*$""", RegexOption.MULTILINE)
            .find(xcconfig.readText())?.groupValues?.get(1)
        assertTrue(teamId != null, "TEAM_ID not found in Deployment.xcconfig — run the resolver, or the signing surface moved")

        val declared = signingEntitlementsFiles.map { path ->
            val file = File(repoRoot, path)
            assertTrue(file.isFile, "$path is missing — the entitlements surface moved; fix this pin's path")
            val group = Regex("""<string>\$\(AppIdentifierPrefix\)(.+?)</string>""")
                .find(file.readText())?.groupValues?.get(1)
            assertTrue(group != null, "$path declares no \$(AppIdentifierPrefix) keychain group")
            path to group
        }

        val suffixes = declared.map { it.second }.toSet()
        assertTrue(
            suffixes.size == 1,
            "the two entitlements files must declare the SAME keychain group, or the app and the " +
                "extension address different items and each silently reads its own:\n  " +
                declared.joinToString("\n  ") { "${it.first} → ${it.second}" },
        )

        val composed = "$teamId.${suffixes.single()}"
        assertTrue(
            composed == SHARED_ACCESS_GROUP,
            "the Kotlin access group and the entitlements disagree.\n" +
                "  Kotlin:       $SHARED_ACCESS_GROUP\n" +
                "  entitlements: $composed  (TEAM_ID=$teamId + ${suffixes.single()})\n" +
                "Drift here does not fail loudly — both processes still read successfully and each " +
                "gets a DIFFERENT item. That is the split-identity fault (capability device-identity).",
        )
    }

    /**
     * The device-id seat names its group; every other seat's unscoped-ness is an explicit inventory
     * rather than a default nobody noticed.
     */
    @Test
    fun `only the pinned seats search the Keychain without an access group`() {
        val sources = productionKotlin()
        assertTrue(sources.isNotEmpty(), "production Kotlin scan resolved zero files — the walk is broken")

        // Construction sites only — not the `class IosKeychain(` declaration itself.
        val site = Regex("""(?<!class )IosKeychain\(([^)]*)\)""")
        val unscoped = mutableSetOf<Pair<String, String>>()
        val scoped = mutableSetOf<Pair<String, String>>()
        for (file in sources) {
            for (match in site.findAll(file.readText())) {
                val args = match.groupValues[1]
                val service = Regex("""service\s*=\s*"([^"]+)"""").find(args)?.groupValues?.get(1)
                val account = Regex("""account\s*=\s*"([^"]+)"""").find(args)?.groupValues?.get(1)
                assertTrue(
                    service != null && account != null,
                    "an IosKeychain construction in ${file.toRelativeString(repoRoot)} does not name its " +
                        "service/account inline; this guard cannot classify it — keep the pinned form",
                )
                if ("accessGroup" in args) scoped += service to account else unscoped += service to account
            }
        }

        assertTrue(
            unscoped == unscopedKeychainSeats,
            "the unscoped-Keychain inventory changed.\n" +
                "  expected: ${unscopedKeychainSeats.sortedBy { it.first + it.second }}\n" +
                "  found:    ${unscoped.sortedBy { it.first + it.second }}\n" +
                "A NEW unscoped seat means an item's access group is again chosen by the platform at " +
                "write time, from whatever entitlements the writing build carried. Scoping one is " +
                "welcome — as a spec delta to architecture-guards, not a silent edit.",
        )
        assertTrue(
            ("app.snapsync.deviceid" to "deviceid") in scoped,
            "the device id must address its access group explicitly (capability device-identity)",
        )
    }

    @Test
    fun `the App-Group id is pinned in every entitlements file`() {
        for (path in appGroupEntitlementsFiles) {
            val file = File(repoRoot, path)
            assertTrue(file.isFile, "$path is missing — the entitlements surface moved; fix this pin's path")
            val found = occurrences(listOf(file), "<string>group.app.snapsync</string>")
            assertExactlyOnce("App-Group id in $path", found)
        }
    }

    /**
     * The simulator plist's **absence** of a keychain group is load-bearing, so it is asserted rather
     * than left to a comment.
     *
     * Adding `keychain-access-groups` makes an ad-hoc-signed simulator build UN-LAUNCHABLE — measured
     * 2026-08-09 in the unprefixed form, in the correctly prefixed form, on an ad-hoc signature, and
     * when signed with the real "Apple Development" identity with the full repo entitlements expanded.
     * The symptom is `SBMainWorkspace` refusing the launch, which says nothing about entitlements, so
     * the obvious repair for a reader who notices the "missing" key is exactly the edit that causes it.
     *
     * This is why `device-identity` resolves through a per-target `SecureStore` binding rather than the
     * shared Keychain group on `iosSimulatorArm64`.
     */
    @Test
    fun `the simulator entitlements declare no keychain group`() {
        val path = "iosApp/Configuration/simulator.entitlements"
        val file = File(repoRoot, path)
        assertTrue(file.isFile, "$path is missing — the simulator signing surface moved; fix this pin's path")
        // Match the DECLARATION, with XML comments stripped — not any mention of the name. That file's
        // own header explains at length why the key is absent, and a bare substring check fires on the
        // explanation, which is the failure mode `KeychainContainmentTest` avoids by excluding itself
        // and the archive gate avoids by scoping its match to a section.
        val declarations = file.readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(
            "<key>keychain-access-groups</key>" !in declarations,
            "$path declares keychain-access-groups. Its ABSENCE is the decision, not an omission: " +
                "that entitlement makes an ad-hoc-signed simulator build un-launchable " +
                "(SBMainWorkspace refuses it, saying nothing about entitlements). The device id " +
                "resolves through the iosSimulatorArm64 SecureStore binding instead — see " +
                "capability device-identity.",
        )
    }

    @Test
    fun `BGTask ids agree between Kotlin and Info plist`() {
        val plist = File(repoRoot, "iosApp/iosApp/Info.plist")
        assertTrue(plist.isFile, "iosApp/iosApp/Info.plist is missing — the plist surface moved; fix this pin's path")
        val sources = productionKotlin()
        for (id in bgTaskIds) {
            // The Kotlin side is already pinned exactly-once above; here the OS-consulted side must
            // carry the identical value, or the submit is rejected and the tier dies silently.
            assertExactlyOnce("BGTask id $id in Info.plist", occurrences(listOf(plist), "<string>$id</string>"))
            assertExactlyOnce("BGTask id $id in Kotlin", occurrences(sources, "\"$id\""))
        }
    }

    @Test
    fun `framework baseNames appear exactly once in build files`() {
        val files = buildFiles()
        assertTrue(files.isNotEmpty(), "build-file scan resolved zero files — the walk is broken")
        for (name in baseNames) {
            assertExactlyOnce("framework baseName \"$name\"", occurrences(files, "baseName = \"$name\""))
        }
    }
}
