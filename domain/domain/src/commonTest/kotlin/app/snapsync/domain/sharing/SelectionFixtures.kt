package app.snapsync.domain.sharing

import app.snapsync.domain.asset.AssetFacts
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.MediaKind
import app.snapsync.domain.asset.PixelArea
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.time.Timestamp
import kotlin.time.Instant

// The selection's test values: an event, a member's range over it, and assets as the platform describes them.

internal fun ts(text: String): Timestamp = Timestamp.of(Instant.parse(text))

internal fun id(text: String): AssetId = AssetId.parse(text) ?: error("not a canonical asset id: $text")

internal val START = EventStart(ts("2026-06-01T00:00:00Z"))
internal val END = EventEnd(ts("2026-06-07T00:00:00Z"))

/** The member's range: the event's own dates. */
internal val RANGE: CaptureRange = range("2026-06-01T00:00:00Z", "2026-06-07T00:00:00Z")

internal fun range(from: String, until: String): CaptureRange =
    (CaptureRange.of(CaptureCutoff(ts(from)), CaptureCeiling(ts(until)), START, END) as CaptureRangeVerdict.Ok).range

/** A camera photo taken during the event, unless said otherwise. */
internal fun asset(
    name: String,
    taken: String? = "2026-06-02T10:00:00Z",
    kind: MediaKind = MediaKind.PHOTO,
    edited: Boolean = false,
    pixels: Long? = 12_000_000,
): AssetFacts = AssetFacts(
    assetId = id(name),
    captureDate = taken?.let { CaptureDate.Dated(ts(it)) } ?: CaptureDate.Undated,
    kind = kind,
    isEdited = edited,
    pixelArea = PixelArea.of(pixels),
)

/** The policy of a member sharing [range], with [received] photos and photos in [denied] albums. */
internal fun policy(
    direction: Direction = Direction.Both,
    range: CaptureRange = RANGE,
    received: Set<AssetId> = emptySet(),
    denied: Set<AssetId> = emptySet(),
): SelectionPolicy = SelectionPolicy.of(SelectionInputs(direction, range, received, denied))
