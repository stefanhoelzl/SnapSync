package app.snapsync.fake

import app.snapsync.model.DiagnosticDump
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.ports.AlbumMapStore
import app.snapsync.ports.AttestClient
import app.snapsync.ports.AttestKey
import app.snapsync.ports.AttestStore
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.DeviceLogSource
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.DiagnosticsReporter
import app.snapsync.ports.DiscoveryStore
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.GalleryStatusSource
import app.snapsync.ports.ImportedAssetPresence
import app.snapsync.ports.JoinedEventMarker
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.PhotoSelectionChangeSource
import app.snapsync.ports.StagedBytes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * **The honest doubles' only public surface: a factory per port, returning the PORT type**
 * (capability `architecture-guards`; law `module-architecture` "The module set withholds").
 *
 * The implementations behind these functions are `internal`. `internal` is module-scoped, so
 * `:test:world` — a different module — cannot name them, cannot widen them, and cannot reach a
 * member the port does not declare. Honesty is therefore not a rule about what a fake may expose;
 * it is a property of what a consumer can express, enforced by the compiler.
 *
 * This replaced a text gate that policed the same property by matching `var` at the start of a
 * line. That gate could not see a `val` of a mutable type, and duly missed one:
 * `InMemoryStagedBytes.files` was public mutable state read directly by the world harness. The set
 * is now injected — the operator's rigging owns the cell it wants to observe and passes it in,
 * which is where a lever belongs (`:test:world`), rather than being exposed from the honest double.
 *
 * A fake needing operator-visible state takes that state as a **parameter**: the caller keeps its
 * own reference and observes it there.
 */

fun inMemoryLedgerStore(): LedgerStore = InMemoryLedgerStore()

fun inMemoryDownloadStore(): DownloadStore = InMemoryDownloadStore()

fun inMemoryDiscoveryStore(): DiscoveryStore = InMemoryDiscoveryStore()

fun inMemoryDeviceManifestStore(): DeviceManifestStore = InMemoryDeviceManifestStore()

fun inMemoryAlbumMapStore(initial: Map<String, String> = emptyMap()): AlbumMapStore =
    InMemoryAlbumMapStore(initial)

fun inMemoryJoinedEventMarker(value: String? = null): JoinedEventMarker =
    InMemoryJoinedEventMarker(value)

fun inMemoryAttestKey(supported: Boolean = true): AttestKey = InMemoryAttestKey(supported)

fun inMemoryAttestClient(
    challengeValue: String? = "in-memory-challenge",
    tokenExpiresAtEpochSeconds: Long = 90L * 24 * 60 * 60,
    mints: Boolean = true,
    renews: Boolean = false,
): AttestClient = InMemoryAttestClient(challengeValue, tokenExpiresAtEpochSeconds, mints, renews)

fun inMemoryAttestStore(token: String? = null, keyId: String? = null): AttestStore =
    InMemoryAttestStore(token, keyId)

fun inMemoryCandidateSource(state: MutableStateFlow<List<RawAsset>>): CandidateSource =
    InMemoryCandidateSource(state)

fun inMemoryCandidateSource(initial: List<RawAsset> = emptyList()): CandidateSource =
    InMemoryCandidateSource(initial)

fun inMemoryGalleryStatusSource(state: MutableStateFlow<Int?>): GalleryStatusSource =
    InMemoryGalleryStatusSource(state)

fun inMemoryGalleryStatusSource(initial: Int? = null): GalleryStatusSource =
    InMemoryGalleryStatusSource(initial)

fun inMemoryPhotoSelectionChangeSource(
    cell: MutableSharedFlow<List<Resource>>,
): PhotoSelectionChangeSource = InMemoryPhotoSelectionChangeSource(cell)

fun inMemoryDeviceLogSource(
    logs: MutableStateFlow<Map<DeviceLogSource.Process, String>>,
): DeviceLogSource = InMemoryDeviceLogSource(logs)

fun inMemoryDeviceLogSource(
    logs: Map<DeviceLogSource.Process, String> = emptyMap(),
): DeviceLogSource = InMemoryDeviceLogSource(logs)

fun inMemoryDiagnosticsReporter(
    started: MutableStateFlow<Boolean>,
    sent: MutableStateFlow<List<DiagnosticDump>>,
    isConfigured: Boolean = true,
): DiagnosticsReporter = InMemoryDiagnosticsReporter(started, sent, isConfigured)

fun inMemoryDiagnosticsReporter(): DiagnosticsReporter = InMemoryDiagnosticsReporter()

fun inMemoryAssetPresence(
    present: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
    readable: MutableStateFlow<Boolean> = MutableStateFlow(true),
): ImportedAssetPresence = InMemoryAssetPresence(present, readable)

/**
 * [files] is the caller's own cell. The operator rigging that wants to observe staged paths keeps
 * its reference and reads it there; the honest double does not expose it back.
 */
fun inMemoryStagedBytes(
    files: MutableSet<String> = mutableSetOf(),
    root: String = "staged:/",
): StagedBytes = InMemoryStagedBytes(files, root)
