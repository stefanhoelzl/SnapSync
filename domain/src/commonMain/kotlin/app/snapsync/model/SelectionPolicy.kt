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

    /** The membership contributes every asset that satisfies **all** [rules]. */
    data class Admitting(val rules: List<SelectionRule>) : SelectionPolicy

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

    /**
     * The capture-date **lower** bound, if any — the one bound a platform walk MUST push into its native
     * fetch (capability `photo-selection-policy`). That is a **liveness** property of the walk, not a
     * correctness property of admission: every rule here is equally load-bearing for what is admitted,
     * but an unbounded walk is watchdog-killed before [admits] ever runs. Every *other* narrowing is
     * advisory — omit it and you pay only performance, because [admits] re-filters.
     */
    val walkFloor: CaptureCutoff?
        get() = (this as? Admitting)?.rules
            ?.filterIsInstance<SelectionRule.CaptureAfter>()?.firstOrNull()?.cutoff

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
            return Admitting(
                buildList {
                    add(SelectionRule.CaptureAfter(cutoff))
                    if (ceiling != null) add(SelectionRule.CaptureBefore(ceiling))
                    add(SelectionRule.ExcludeScreenshots)
                    add(SelectionRule.ExcludeScreenRecordings)
                    add(SelectionRule.ExcludeGif)
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
    is SelectionPolicy.Admitting -> SelectionPolicy.Admitting(
        rules + buildList {
            if (suppressedAssetIds.isNotEmpty()) add(SelectionRule.NotEcho(suppressedAssetIds))
            if (albumExcludedAssetIds.isNotEmpty()) {
                add(SelectionRule.NotInDenylistedAlbum(albumExcludedAssetIds))
            }
        },
    )
}

/**
 * The **admitted set** over a flat resource list: the ids of the assets this policy admits, decided once
 * on per-asset facts ([factsFromResources]) so an asset's resources stand or fall together.
 *
 * Consumers filter by this rather than re-stating any rule — which is the whole point of the type.
 */
fun SelectionPolicy.admittedAssetIds(resources: List<Resource>): Set<String> =
    factsFromResources(resources).filter { admits(it) }.mapTo(mutableSetOf()) { it.assetId }

/** The resources of the admitted assets — the set the upload cycle uploads and the manifest lists. */
fun SelectionPolicy.admittedResources(resources: List<Resource>): List<Resource> {
    val admitted = admittedAssetIds(resources)
    return resources.filter { it.assetId in admitted }
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

    /** A GIF is never a camera capture — including one exported from a Live Photo, which is a re-encode. */
    data object ExcludeGif : SelectionRule {
        override fun admits(facts: AssetFacts): Boolean = !facts.isGif
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

/** `PHAssetMediaSubtype.photoScreenshot` — `1 shl 2`. */
const val SUBTYPE_SCREENSHOT: Long = 1L shl 2

/** `PHAssetMediaSubtype.videoScreenRecording` — `1 shl 19`. Runtime-present since iOS 13. */
const val SUBTYPE_SCREEN_RECORDING: Long = 1L shl 19

/**
 * The subtype bits that exclude an asset outright. Also inlined into the iOS fetch predicate as an
 * optimization — see `PhotoLibraryRawAssetSource.fetchOptionsSince`, and note the hard-won constraint
 * that the predicate form must be `NOT ((mediaSubtypes & N) != 0)`.
 */
const val EXCLUDED_SUBTYPE_MASK: Long = SUBTYPE_SCREENSHOT or SUBTYPE_SCREEN_RECORDING

/** A GIF is never a camera capture — including one exported from a Live Photo, which is a re-encode. */
const val MIME_GIF: String = "image/gif"

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
