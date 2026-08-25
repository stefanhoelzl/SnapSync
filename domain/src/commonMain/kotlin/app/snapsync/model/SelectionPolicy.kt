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
sealed interface SelectionPolicy {

    /**
     * The membership contributes nothing — its participation direction excludes upload (`DownloadOnly`),
     * or the surface is previewing a candidate with sharing off.
     *
     * Carries no bounds: a non-contributor has none to speak of. It admits nothing, and — critically —
     * every consumer reaches the empty answer **without enumerating**. The walk costs one synchronous
     * PhotoKit round-trip per asset (~110 ms on an SE2), so expressing "contributes nothing" as a
     * per-asset filter would spend minutes of XPC on a 4000-photo library to arrive at the empty set.
     * [enumerates] is what callers check before starting one.
     */
    data object None : SelectionPolicy

    /**
     * The membership contributes every asset that satisfies **all** [rules].
     *
     * The capture-date **lower** bound is a field, not one rule among the rest, and [rules] derives its
     * [SelectionRule.CaptureAfter] from it. That makes "contributes, but with no capture floor"
     * unrepresentable — closing *A lower bound `from` SHALL be required: a membership without one is not
     * a representable state* at the type rather than at each consumer.
     *
     * It is the one bound a platform walk MUST push into its native fetch (capability
     * `photo-selection-policy`). That is a **liveness** property of the walk, not a correctness property
     * of admission: every rule is equally load-bearing for what is admitted, but an unbounded walk is
     * watchdog-killed before [admits] ever runs. Every *other* narrowing is advisory — omit it and you
     * pay only performance, because [admits] re-filters.
     *
     * [rest] holds every other rule. [rules] is computed once here rather than on each read, because
     * [admits] runs per asset across a walk; equality keys on the two constructor parameters, which is
     * exactly right — two policies with the same cutoff and the same rest are the same policy.
     */
    data class Admitting(
        val cutoff: CaptureCutoff,
        val rest: List<SelectionRule>,
    ) : SelectionPolicy {
        val rules: List<SelectionRule> = listOf(SelectionRule.CaptureAfter(cutoff)) + rest
    }

    /** Does the policy admit this asset? The single admission decision in the system. */
    fun admits(facts: AssetFacts): Boolean = when (this) {
        None -> false
        is Admitting -> rules.all { it.admits(facts) }
    }

    /**
     * Whether a library walk should begin at all. `false` for [None] — see its doc for why this is a
     * short-circuit and not a filter.
     */
    val enumerates: Boolean get() = this !is None

    // There is deliberately NO `walkFloor: CaptureCutoff?` accessor. It answered "what is the capture
    // floor" with an absent value whose two causes — "this membership contributes nothing" and "this
    // policy has no floor" — have opposite consequences, and it invited a consumer to branch on the
    // floor BEFORE checking the direction. `UploadCycle` did exactly that: because `None.walkFloor` was
    // `null`, every download-only cycle took the malformed-policy branch and logged at `Error`, which
    // the reporting seam turns into a crash report, while the branch that names the real reason sat
    // one line below, unreachable. A consumer needing the bound exhausts this sealed type instead:
    // `None` is handled on its own branch, and `Admitting` yields a non-null [Admitting.cutoff].

    companion object {
        /**
         * The **one** derivation of a membership's policy, from named fields.
         *
         * Named, typed parameters rather than positional strings: the four-site positional construction
         * this replaces is what made the date-role swap possible in the first place (passing `startsAt`
         * where `minPhotoDate` was wanted *lowers* the capture floor and leaks excluded photos). Now both
         * the role and the name have to be right.
         *
         * **No default, in either polarity** — this is not fastidiousness; both defaults are catastrophic
         * in opposite directions. A permissive default uploads the entire library from the beginning of
         * time; a fail-closed default is *worse* because it is silent — a contributing member would share
         * nothing, `N` would read `0`, and the screen would read "In sync" while nothing happened.
         *
         * [ceiling] is nullable only for a membership persisted before the capture-date range existed and
         * not yet reconciled (capability `event-rejoin-reconciliation`); `null` means unbounded above,
         * the admit-on-doubt direction. It becomes required once every device has reconciled.
         */
        fun from(
            includesUpload: Boolean,
            cutoff: CaptureCutoff,
            ceiling: CaptureCeiling?,
        ): SelectionPolicy {
            if (!includesUpload) return None
            // [cutoff] is stored verbatim on the variant; its `CaptureAfter` rule is derived there, so
            // the bound and the rule cannot drift apart.
            return Admitting(
                cutoff = cutoff,
                rest = buildList {
                    if (ceiling != null) add(SelectionRule.CaptureBefore(ceiling))
                    add(SelectionRule.ExcludeScreenshots)
                    add(SelectionRule.ExcludeScreenRecordings)
                    add(SelectionRule.MinImageArea(MIN_IMAGE_PIXEL_AREA))
                    add(SelectionRule.MinVideoArea(MIN_VIDEO_PIXEL_AREA))
                },
            )
        }

        /**
         * The committed-membership overload: reads the bounds off [config] **by name**, so a role swap is
         * a compile error and a name swap is visible at the one site it can happen.
         */
        fun from(config: EventConfig): SelectionPolicy = from(
            includesUpload = config.direction.includesUpload,
            cutoff = config.minPhotoDate,
            ceiling = config.maxPhotoDate,
        )
    }
}

/**
 * Complete a config-derived policy with the two exclusion sets that are **not** on the config: the echo
 * suppression (the download store's imported ids) and the denylisted-album members (a platform lookup).
 *
 * They arrive separately because they are read from ports, per query, by whoever is holding those ports —
 * while [SelectionPolicy.from] is pure and derives from the membership alone. Splitting the assembly this
 * way keeps the rule *list* a single derivation while letting each consumer supply the effectful sets at
 * the moment it can read them. [SelectionPolicy.None] stays [SelectionPolicy.None]: a membership that
 * contributes nothing does not become a membership that contributes nothing-except.
 */
fun SelectionPolicy.excluding(
    suppressedAssetIds: Set<String>,
    albumExcludedAssetIds: Set<String>,
): SelectionPolicy = when (this) {
    SelectionPolicy.None -> SelectionPolicy.None
    // The cutoff carries through untouched — completing a policy adds exclusions, it never moves the
    // capture floor.
    is SelectionPolicy.Admitting -> SelectionPolicy.Admitting(
        cutoff = cutoff,
        rest = rest + buildList {
            if (suppressedAssetIds.isNotEmpty()) add(SelectionRule.NotEcho(suppressedAssetIds))
            if (albumExcludedAssetIds.isNotEmpty()) {
                add(SelectionRule.NotInDenylistedAlbum(albumExcludedAssetIds))
            }
        },
    )
}

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
