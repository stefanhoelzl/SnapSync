package app.snapsync.domain.receiving

import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.EventKeyId
import app.snapsync.domain.event.anEvent
import app.snapsync.domain.event.ts
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.Share
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import kotlin.test.assertIs

/** This device. */
internal val ME = testDeviceId("me")

/** Another member's device. */
internal val OTHER = testDeviceId("other")

/** [asset] of [device]. */
internal fun ref(
    asset: String,
    device: app.snapsync.domain.device.DeviceId = OTHER,
) = AssetRef(device, testAssetId(asset))

/** A union photo of [asset] by [device], captured at [captured] (`null`: undated). */
internal fun photo(
    asset: String,
    device: app.snapsync.domain.device.DeviceId = OTHER,
    captured: String? = "2026-07-15T10:00:00Z",
) =
    UnionPhoto(
        ref(asset, device),
        captured?.let { CaptureDate.Dated(ts(it)) } ?: CaptureDate.Undated,
        listOf(UnionResource("$asset-primary.heic", "https://e/$asset", "primary", "image/heic", "IMG.HEIC")),
    )

/** An active membership of [anEvent] with [direction], encrypted under [key] when one is named. */
internal fun active(direction: Direction = Direction.Both, key: EventKeyId? = null) = Membership.Active(
    anEvent().copy(encryption = key?.let(Encryption::Encrypted) ?: Encryption.Plain),
    Share(
        assertIs<CaptureRangeVerdict.Ok>(
            CaptureRange.of(
                CaptureCutoff(anEvent().range.start.at),
                CaptureCeiling(anEvent().range.end.at),
                anEvent().range.start,
                anEvent().range.end,
            ),
        ).range,
        direction,
        album = AlbumChoice.LibraryOnly,
    ),
)

/** A library asset id. */
internal fun local(text: String): AssetId = testAssetId(text)
