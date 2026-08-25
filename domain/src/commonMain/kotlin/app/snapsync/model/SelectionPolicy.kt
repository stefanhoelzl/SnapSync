package app.snapsync.model

/**
 * The **selection policy** (capability `photo-selection-policy`): what a membership contributes, as ONE
 * value with ONE `admits` decision.
 *
 * The policy answers a single question — *may this asset enter the event?* — and it has three kinds of
 * input: the capture-date **range** bounds *when* it was taken, the **origin exclusions** bound *what it
 * is*, and the participation **direction** bounds *whether at all*.
 *
 * ## Why this is one value and not a set of rules each consumer re-applies
 *
 * Four consumers need the answer: the byte upload, the device manifest, the own-device status total `N`,
 * and the join-time shareable-count preview. Before this type they each assembled the rules by hand, and
 * they drifted: `add-event-date-range` added the capture-date **ceiling** to the byte filter and the
 * preview but not to the manifest projection or `N`. `Contribution.Since -> c.cutoff` (dropping `until`)
 * compiles exactly as readily as the correct destructure, and every test fixture used `until = null`, so
 * the whole suite was blind. The result on a real device: post-ceiling photos listed in `device.json` and
 * counted in `N` while their bytes never uploaded — the status screen pegged below 100% forever, and
 * other members offered a resource that 404s.
 *
 * So the rules live **here**, in one list, and every consumer asks [admits]. Adding or changing a rule is
 * one edit and every consumer follows by construction. A `:test:architecture` guard pins that no consumer
 * compares a capture date itself.
 *
 * ## Every rule decides on facts alone
 *
 * No rule may need an asset's *resources* to decide (capability `photo-selection-policy`). That is what
 * makes the admitted set **one** set rather than a family of approximations: a rule requiring a ~110 ms
 * per-asset resource read forces each consumer to choose between paying for it — pointless for a count —
 * and admitting on doubt, so the same policy yields different answers at different consumers.
 *
 * The animated-image rule was the only such rule, and it is gone. It read a *resource's* MIME type, so the
 * facts-only join preview could not see it and admitted a GIF on doubt while the eager status walk excluded
 * one: the preview over-counted by exactly the GIFs in scope. The 3 MP image floor already excludes every
 * ordinary GIF; what is no longer excluded is an edited one (the floor is skipped for `hasAdjustments`) or
 * one at ≥3 MP, and both land on the admit-on-doubt side this policy declares acceptable.
 *
 * It also inverts the walk's cost: because admission settles before any resource is read, resources are
 * fetched only for assets **already admitted**, rather than for every asset the walk returns.
 *
 * ## What it can and cannot know
 *
 * **This can only subtract, never infer.** PhotoKit exposes no "this device's camera took this" flag on
 * any iOS through 26 — the entire public surface of `PHAsset`/`PHAssetResource` was enumerated against
 * the 26.5 SDK headers and there is nothing. So each rule recognizes a category that is *certainly* not a
 * capture, and everything else is **admitted**.
 *
 * **Admit on doubt.** Where a rule cannot distinguish received media from a capture, it admits. The
 * asymmetry is deliberate and it is not squeamishness: a stray uploaded meme is visible, harmless, and
 * deletable, while an event photo that silently fails to upload is a failure of the product's core
 * promise on a surface where the user cannot even notice it, let alone correct it. This is why there is a
 * resolution *floor* and not a resolution *allowlist*, and why the floors are skipped for edited assets.
 *
 * ## Where it lives
 *
 * `model/` — the only zone every consumer can see (feature/upload and feature/status are mutually blind).
 * Platform-free, decided entirely on neutral [AssetFacts], and exercised in `commonTest` on JVM **and**
 * the simulator. The platform may narrow what a walk *returns* by pattern-matching [SelectionRule]s
 * (capability `photo-selection-policy`, *Selection filter*), but that is an optimization which can
 * neither widen nor narrow the admitted set — [admits] stays authoritative.
 */
/**
 * The membership contributes every asset **all** of its [rules] admit.
 *
 * It is a conjunction of rules and nothing else. It special-cases no rule, and it asserts that no
 * particular rule is present — the capture floor included. That invariant lives at the one place a
 * membership becomes a policy ([selectionRulesFor]), which always emits the floor because the persisted
 * `minPhotoDate` is non-null. "Contributes nothing" is likewise a rule ([SelectionRule.DenyAll]) rather
 * than a second kind of value.
 *
 * This replaced a two-variant sealed type — a non-contributing variant carrying no rules, and a
 * contributing one carrying the capture floor as a non-null field with its `CaptureAfter` rule derived
 * from it. That shape answered "does this contribute?" and "is there a floor?" by construction, which is
 * genuinely stronger than answering them by inspection. It was collapsed because both questions stopped
 * needing an answer here: the floor invariant moved to the build site deliberately, and the consumer that
 * needed the direction question — the upload cycle's gate — no longer withholds the manifest write, which
 * was the only thing it could still justify withholding.
 *
 * The cost is stated rather than hidden: `SelectionPolicy(listOf(ExcludeScreenshots))` compiles. The one
 * derivation cannot produce it, and a guard keeps construction to that derivation, but the type no longer
 * refuses it.
 */
class SelectionPolicy(val rules: List<SelectionRule>) {

    /** Does the policy admit this asset? The single admission decision in the system. */
    fun admits(facts: AssetFacts): Boolean = rules.all { it.admits(facts) }

    /**
     * Does this membership contribute at all?
     *
     * Not a shortcut for `admits` — it is the question the upload cycle's direction gate asks, and the
     * gate withholds far more than a walk: upload job creation and the retry pass. A membership that
     * contributes nothing needs none of that, and answering it per-asset would mean discovering the fact
     * once per photo instead of once per cycle.
     *
     * Derived by asking the rules rather than by testing for `DenyAll` by identity, so a rule added later
     * that can never admit anything declares itself instead of being silently missed
     * ([SelectionRule.deniesEverything]).
     */
    val contributes: Boolean get() = rules.none { it.deniesEverything }

    override fun equals(other: Any?): Boolean = other is SelectionPolicy && rules == other.rules

    override fun hashCode(): Int = rules.hashCode()

    override fun toString(): String = "SelectionPolicy($rules)"
}

/**
 * The **one** derivation from a membership to a rule list.
 *
 * Rule construction is `suspend` because two of the rules are read from ports — the download store's
 * imported ids (echo suppression) and the platform album lookup (denylisted-album membership). **Policy**
 * construction is not: a [SelectionPolicy] is a plain value over an already-finished list. That split is
 * the point. There is no second step that completes a partially-built policy, because a partially-built
 * policy is a value a consumer can hold and act on — and holding one is how the cutoff kept having to be
 * extracted back out of it.
 *
 * **The direction is resolved first**, and a non-contributor invokes neither reader: the album lookup is a
 * platform fetch, and paying for it to learn that a membership contributes nothing is exactly the cost the
 * old two-variant type existed to avoid.
 *
 * **The floor is always emitted** for a contributing membership, from [cutoff], which is non-null. This is
 * where *A lower bound `from` SHALL be required* is enforced — not in the policy type, which asserts
 * nothing about its contents.
 *
 * **No default, in either polarity** — this is not fastidiousness; both defaults are catastrophic in
 * opposite directions. A permissive default uploads the entire library from the beginning of time; a
 * fail-closed default is *worse* because it is silent — a contributing member would share nothing, `N`
 * would read `0`, and the screen would read "In sync" while nothing happened.
 *
 * [ceiling] is nullable only for a membership persisted before the capture-date range existed and not yet
 * reconciled (capability `event-rejoin-reconciliation`); `null` means unbounded above, the admit-on-doubt
 * direction. It becomes required once every device has reconciled.
 */
suspend fun selectionRulesFor(
    includesUpload: Boolean,
    cutoff: CaptureCutoff,
    ceiling: CaptureCeiling?,
    suppressedAssetIds: suspend () -> Set<String>,
    albumExcludedAssetIds: suspend (CaptureCutoff) -> Set<String>,
): List<SelectionRule> {
    // The direction, first and cheaply: neither reader is consulted for a non-contributor.
    if (!includesUpload) return listOf(SelectionRule.DenyAll)
    return buildList {
        // The floor, always. `cutoff` is non-null, so no contributing rule list this derivation produces
        // can lack one.
        add(SelectionRule.CaptureAfter(cutoff))
        if (ceiling != null) add(SelectionRule.CaptureBefore(ceiling))
        add(SelectionRule.ExcludeScreenshots)
        add(SelectionRule.ExcludeScreenRecordings)
        add(SelectionRule.MinImageArea(MIN_IMAGE_PIXEL_AREA))
        add(SelectionRule.MinVideoArea(MIN_VIDEO_PIXEL_AREA))
        // The two effectful sets, read at the one moment whoever holds those ports can read them.
        suppressedAssetIds().takeIf { it.isNotEmpty() }?.let { add(SelectionRule.NotEcho(it)) }
        albumExcludedAssetIds(cutoff).takeIf { it.isNotEmpty() }
            ?.let { add(SelectionRule.NotInDenylistedAlbum(it)) }
    }
}

/**
 * The committed-membership overload: reads the bounds off [config] **by name**, so a role swap is a
 * compile error and a name swap is visible at the one site it can happen. The four-site positional
 * construction this replaces is what made the date-role swap possible (passing `startsAt` where
 * `minPhotoDate` was wanted *lowers* the capture floor and leaks excluded photos).
 */
suspend fun selectionRulesFor(
    config: EventConfig,
    suppressedAssetIds: suspend () -> Set<String>,
    albumExcludedAssetIds: suspend (CaptureCutoff) -> Set<String>,
): List<SelectionRule> = selectionRulesFor(
    includesUpload = config.direction.includesUpload,
    cutoff = config.minPhotoDate,
    ceiling = config.maxPhotoDate,
    suppressedAssetIds = suppressedAssetIds,
    albumExcludedAssetIds = albumExcludedAssetIds,
)

/** [selectionRulesFor], wrapped. The rules are gathered asynchronously; the policy is a plain value. */
suspend fun selectionPolicyFor(
    config: EventConfig,
    suppressedAssetIds: suspend () -> Set<String>,
    albumExcludedAssetIds: suspend (CaptureCutoff) -> Set<String>,
): SelectionPolicy =
    SelectionPolicy(selectionRulesFor(config, suppressedAssetIds, albumExcludedAssetIds))

/**
 * One rule of the [SelectionPolicy]. Sealed so the platform can pattern-match the set and translate the
 * rules it can express into a native fetch predicate (capability `photo-selection-policy`, *Selection
 * filter*) — a domain rule, translated per platform, never a platform hint leaking into `model/`.
 *
 * Each rule is a pure predicate over neutral [AssetFacts]. There is deliberately **no** `ExcludeEdited`
 * rule: `hasAdjustments` is an *exemption* from the resolution floors (a cropped photo renders small and
 * would otherwise be mistaken for a compressed download), not an exclusion — it is honoured inside
 * [MinImageArea]/[MinVideoArea], where it belongs.
 */
sealed interface SelectionRule {

    fun admits(facts: AssetFacts): Boolean

    /**
     * Whether this rule refuses **every** asset, whatever the facts.
     *
     * Declared rather than inferred: a caller that tested for [DenyAll] by identity would silently miss a
     * second always-refusing rule added later, and the consequence of missing one is not a wrong admitted
     * set (the conjunction still refuses) but a cycle that does a library's worth of work to discover it
     * contributes nothing.
     */
    val deniesEverything: Boolean get() = false

    /**
     * The membership contributes **nothing** — its participation direction excludes upload
     * (`DownloadOnly`), or a surface is previewing a candidate with sharing off.
     *
     * A rule rather than a second kind of policy. It admits no asset, so it needs no bounds: the
     * conjunction is false whatever else is in the list, and a consumer asks [SelectionPolicy.admits]
     * exactly as it would for any other rule.
     *
     * The platform translator MUST express it as a query matching no asset (capability `gallery-status`).
     * That is a liveness property, not a correctness one — [admits] returns false regardless — but without
     * it a non-contributing membership pays a whole-library walk on every cold start to reach the empty
     * set its own configuration already stated.
     */
    data object DenyAll : SelectionRule {
        override fun admits(facts: AssetFacts): Boolean = false
        override val deniesEverything: Boolean get() = true
    }

    /**
     * The capture-date **lower** bound: at or after [cutoff]. An asset with no `creationDate` (the empty
     * string) sorts before any real cutoff and is therefore excluded — the one place a missing fact
     * excludes rather than admits, because an undated asset cannot be shown to be in the event window.
     */
    data class CaptureAfter(val cutoff: CaptureCutoff) : SelectionRule {
        override fun admits(facts: AssetFacts): Boolean = facts.creationDate >= cutoff.at
    }

    /**
     * The capture-date **upper** bound (the ceiling): at or before [ceiling]. This is the rule that
     * reached only two of four consumers before the policy became one value — see [SelectionPolicy].
     */
    data class CaptureBefore(val ceiling: CaptureCeiling) : SelectionRule {
        override fun admits(facts: AssetFacts): Boolean = facts.creationDate <= ceiling.at
    }

    /** Exact, perfect recall, and the highest-frequency exclusion. */
    data object ExcludeScreenshots : SelectionRule {
        override fun admits(facts: AssetFacts): Boolean = !facts.isScreenshot
    }

    data object ExcludeScreenRecordings : SelectionRule {
        override fun admits(facts: AssetFacts): Boolean = !facts.isScreenRecording
    }

    /**
     * The **image** resolution floor — compressed received media. Inert for a video (that is
     * [MinVideoArea]'s job), for an **edited** asset (see [SelectionRule]), and when the area is unknown
     * or non-positive (admit on doubt, never drop a real photo).
     */
    data class MinImageArea(val minArea: Long) : SelectionRule {
        override fun admits(facts: AssetFacts): Boolean = facts.isVideo || admitsByArea(facts, minArea)
    }

    /**
     * The **video** resolution floor — a *separate, lower* floor, and this is load-bearing rather than a
     * refinement. 1080p video is 1920×1080 = 2.07 MP, which is **below** the image floor, so a single
     * shared floor would silently drop every 1080p recording — and 1080p is the iOS capture default.
     */
    data class MinVideoArea(val minArea: Long) : SelectionRule {
        override fun admits(facts: AssetFacts): Boolean = !facts.isVideo || admitsByArea(facts, minArea)
    }

    /**
     * Echo suppression (capability `photo-download`): assets this device **downloaded and imported** from
     * other contributors. They live in the library, so a walk finds them, but re-uploading one sends a
     * foreign photo back into the event and pegs `N` above what will ever complete.
     */
    data class NotEcho(val suppressedAssetIds: Set<String>) : SelectionRule {
        override fun admits(facts: AssetFacts): Boolean = facts.assetId !in suppressedAssetIds
    }

    /**
     * The album denylist (capability `photo-selection-policy`): assets sitting in an album a
     * messaging/social app made. Album membership is the one origin fact that is **not** on the asset —
     * it needs a platform lookup — so the resolved id set is supplied to the policy rather than looked up
     * by it. The titles stay in `model/` ([DENYLISTED_ALBUM_TITLES]); cost is O(albums), not O(assets).
     */
    data class NotInDenylistedAlbum(val excludedAssetIds: Set<String>) : SelectionRule {
        override fun admits(facts: AssetFacts): Boolean = facts.assetId !in excludedAssetIds
    }
}

/** The shared floor test: edited and unknown-area assets are admitted; see [SelectionRule]. */
private fun admitsByArea(facts: AssetFacts, minArea: Long): Boolean {
    if (facts.isEdited) return true
    val area = facts.pixelArea ?: return true
    if (area <= 0L) return true
    return area >= minArea
}

/**
 * Images below **3 MP** are excluded. WhatsApp caps received images at a 1600 long edge (~1.9 MP, at
 * worst 2.6 MP square), Telegram ~1.2 MP, an Instagram save ~1.5 MP — while the *weakest* camera on the
 * oldest supported device (the SE2 front camera) is 3088×2320 = 7.2 MP. The floor sits >2× below that.
 *
 * **Fixed, not derived from `AVCaptureDevice` at runtime.** A device-derived floor is *tighter* on a
 * better camera, and tighter means more false drops — the opposite of admit-on-doubt. The device's real
 * camera resolution is information this policy deliberately declines to act on.
 */
const val MIN_IMAGE_PIXEL_AREA: Long = 3_000_000

/** See [SelectionRule.MinVideoArea] for why this floor is separate and lower. */
const val MIN_VIDEO_PIXEL_AREA: Long = 1280L * 720L
