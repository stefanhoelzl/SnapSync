@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.mock

import app.snapsync.domain.asset.AssetId
import app.snapsync.model.Availability
import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashLevel
import app.snapsync.model.Crumb
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceRefusal
import app.snapsync.model.FileArea
import app.snapsync.model.NetworkAccess
import app.snapsync.model.PushEndpoint
import app.snapsync.model.ResourceRole
import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureSlots
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.WakeCadence
import app.snapsync.model.WakeId
import app.snapsync.model.WakeNetwork
import app.snapsync.model.WakeTrigger
import app.snapsync.model.deviceManifestFromJson
import app.snapsync.model.encodeToJson
import app.snapsync.ports.GalleryAsset
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryResource
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import app.snapsync.ports.TransferFailure
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.UploadJobState
import app.snapsync.ports.UploadTarget
import kotlinx.datetime.TimeZone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * **What a mocked system keeps, as text** (`docs/testing.md`, "Launch-time adapters") — the durable state each mock
 * of a [MockDevice] holds, encoded one system at a time so a process restores only the systems its adapter choice mocks.
 *
 * Durable means what the real system keeps across the app's process — the backend's events, the library's photos, the
 * operating system's queued jobs — and nothing a process holds: registered handlers, open observers, background-time
 * holds (a dead process's never end), a held-open deferred. Those start empty in every process, as on a device.
 *
 * The databases are not here: a persisted adapter choice's databases are FILES ([app.snapsync.mock.DatabasesMock]'s directory),
 * which persist themselves and are shared across processes as files are.
 */
object MockState {
    /** [system]'s durable state on [device], or `null` for a system that keeps none. */
    fun encode(device: MockDevice, system: MockedSystem): String? = CODECS[system]?.encode?.invoke(device)

    /** Put [text] — an [encode] of [system] — back into [device]'s mock of it. */
    fun restore(device: MockDevice, system: MockedSystem, text: String) {
        CODECS[system]?.restore?.invoke(device, text)
    }
}

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** One system's text: how its durable state is written, and how it is put back. */
private class Codec(val encode: (MockDevice) -> String?, val restore: (MockDevice, String) -> Unit)

/** A system whose state is one [serializer]'s value: [write] reads it off the device, [read] puts it back. */
private fun <D> codec(serializer: KSerializer<D>, write: (MockDevice) -> D, read: (MockDevice, D) -> Unit) = Codec(
    encode = { json.encodeToString(serializer, write(it)) },
    restore = { device, text -> read(device, json.decodeFromString(serializer, text)) },
)

/** A system whose whole state is a few named scalars. */
private fun scalars(write: (MockDevice) -> Map<String, String>, read: (MockDevice, Map<String, String>) -> Unit) =
    codec(StringsDto.serializer(), { StringsDto(write(it)) }, { device, dto -> read(device, dto.values) })

/**
 * Every system that keeps something — the databases (files), the background-time holds, the links and the screen (a
 * process's own) keep nothing here.
 */
private val CODECS: Map<MockedSystem, Codec> = mapOf(
    MockedSystem.BACKEND to codec(
        BackendDto.serializer(),
        { it.backend.state.locked { BackendDto.of(it.backend.state) } },
        { d, dto -> d.backend.state.locked { dto.into(d.backend.state) } },
    ),
    MockedSystem.LIBRARY to codec(
        LibraryDto.serializer(),
        { it.library.state.locked { LibraryDto.of(it.library.state) } },
        { d, dto -> d.library.state.locked { dto.into(d.library.state) } },
    ),
    MockedSystem.FILES to codec(FilesDto.serializer(), { FilesDto.of(it.disk) }, { d, dto -> dto.into(d.disk) }),
    MockedSystem.PREFERENCES to scalars({
        it.preferences.values.snapshot()
    }, { device, values -> device.preferences.values.putAll(values) }),
    MockedSystem.KEYCHAIN to codec(
        KeychainDto.serializer(),
        { KeychainDto.of(it.keychain.items.snapshot()) },
        { d, dto -> dto.into(d.keychain.items) },
    ),
    MockedSystem.INTEGRITY to codec(
        EnclaveDto.serializer(),
        { it.enclave.keys.snapshot().let { (generated, held) -> EnclaveDto(generated, held) } },
        { device, keys -> device.enclave.keys.restore(keys.generated, keys.held) },
    ),
    MockedSystem.CRASH_REPORTER to codec(
        CrashDumpsDto.serializer(),
        { CrashDumpsDto(it.crashReporter.dumps.value.map(CrashDto::of)) },
        { device, dto -> device.crashReporter.dumps.value = dto.dumps.map { it.event() } },
    ),
    MockedSystem.PROCESS_INFO to scalars(
        { mapOf(PROTECTED to it.processInfo.cell.value.name) },
        { device, values -> values[PROTECTED]?.let { device.processInfo.cell.value = Availability.valueOf(it) } },
    ),
    MockedSystem.NETWORK to scalars(
        { mapOf(ACCESS to accessName(it.connectivity.cell.value)) },
        { device, values -> values[ACCESS]?.let { device.connectivity.cell.value = accessNamed(it) } },
    ),
    MockedSystem.DEVICE_CONDITIONS to scalars(
        { DeviceConditionsText.encode(it.deviceConditions.cell.value) },
        { device, values -> device.deviceConditions.cell.value = DeviceConditionsText.apply(device.deviceConditions.cell.value, values) },
    ),
    MockedSystem.CLOCK to codec(
        ClockDto.serializer(),
        { ClockDto(it.clock.now.toEpochMilliseconds(), it.clock.zone.id) },
        { device, clock ->
            device.clock.now = Instant.fromEpochMilliseconds(clock.nowEpochMillis)
            device.clock.zone = TimeZone.of(clock.zone)
        },
    ),
    MockedSystem.WAKE to codec(WakeDto.serializer(), { WakeDto.of(it.wakes) }, { d, dto -> dto.into(d.wakes) }),
    MockedSystem.EXTENSION_REGISTRY to scalars(
        { device -> device.extensionRegistry.record?.let { mapOf(REGISTERED to it.value.toString()) }.orEmpty() },
        { device, values -> values[REGISTERED]?.let { device.extensionRegistry.record?.value = it.toBoolean() } },
    ),
    MockedSystem.UPLOAD_QUEUE to codec(
        QueueDto.serializer(),
        { it.uploadQueue.locked { QueueDto.of(it.uploadQueue) } },
        { d, dto -> d.uploadQueue.locked { dto.into(d.uploadQueue) } },
    ),
    MockedSystem.UPLOAD_SESSION to scalars(
        { mapOf(HANDBACKS to it.uploadSession.handbacks.toString()) },
        { device, values -> values[HANDBACKS]?.let { device.uploadSession.handbacks = it.toInt() } },
    ),
    MockedSystem.DOWNLOADS to codec(
        DownloadsDto.serializer(),
        { device ->
            val started = device.downloads.locked { device.downloads.started.toList() }
            DownloadsDto(started.map { StartedDto(it.url, it.description, it.cancelled, it.finished, it.network.name) })
        },
        { device, dto ->
            device.downloads.locked {
                dto.started.forEach {
                    device.downloads.started += DownloadSessionMock.Started(it.url, it.description, TransferNetwork.valueOf(it.network)).apply {
                        cancelled = it.cancelled
                        finished = it.finished
                    }
                }
            }
        },
    ),
    MockedSystem.LIFECYCLE to scalars(
        { mapOf(EVER_ACTIVE to it.lifecycle.everActive.toString()) },
        { device, values -> values[EVER_ACTIVE]?.let { device.lifecycle.everActive = it.toBoolean() } },
    ),
    MockedSystem.PUSH to scalars(
        { mapOf(REGISTRATIONS to it.pushService.registrations.toString()) },
        { device, values -> values[REGISTRATIONS]?.let { device.pushService.registrations = it.toInt() } },
    ),
    MockedSystem.SYSTEM_UI to codec(
        SystemUiDto.serializer(),
        {
            SystemUiDto(it.systemUi.shared.value, it.systemUi.opened.value, it.systemUi.settings.value, it.systemUi.sharedTitles.value)
        },
        { device, ui ->
            device.systemUi.shared.value = ui.shared
            device.systemUi.sharedTitles.value = ui.sharedTitles
            device.systemUi.opened.value = ui.opened
            device.systemUi.settings.value = ui.settings
        },
    ),
)

private const val PROTECTED = "protectedData"
private const val ACCESS = "access"
private const val REGISTERED = "registered"
private const val HANDBACKS = "handbacks"
private const val EVER_ACTIVE = "everActive"
private const val REGISTRATIONS = "registrations"

@Serializable
private class StringsDto(val values: Map<String, String>)

@Serializable
private class FilesDto(
    val shared: Map<String, String>,
    val private: Map<String, String>,
    val denied: List<Pair<String, String>>,
) {
    fun into(disk: FileSystemMock) {
        disk.shared.putAll(shared.mapValues { Base64.decode(it.value) })
        disk.private.putAll(private.mapValues { Base64.decode(it.value) })
        disk.denied.putAll(denied.associate { (FileArea.valueOf(it.first) to it.second) to Unit })
    }

    companion object {
        fun of(disk: FileSystemMock) = FilesDto(
            shared = disk.shared.snapshot().mapValues { Base64.encode(it.value) },
            private = disk.private.snapshot().mapValues { Base64.encode(it.value) },
            denied = disk.denied.snapshot().keys.map { it.first.name to it.second },
        )
    }
}

@Serializable
private class SlotDto(
    val service: String,
    val account: String,
    val shared: Boolean,
    val value: String,
    val protection: String,
)

@Serializable
private class KeychainDto(val slots: List<SlotDto>) {
    /**
     * Back into [items], each at the slot the app addresses it by — the slots are the app's own (`SecureSlots`), so a
     * stored item names one of them; an item that names none is one no build of the app would read, and stays behind.
     */
    fun into(items: MutableMap<SecureSlot, SecureStoreRead.Found>) {
        slots.forEach { dto ->
            KNOWN_SLOTS.firstOrNull { it.service == dto.service && it.account == dto.account && it.shared == dto.shared }
                ?.let { items[it] = SecureStoreRead.Found(dto.value, StoredProtection.valueOf(dto.protection)) }
        }
    }

    companion object {
        private val KNOWN_SLOTS = listOf(
            SecureSlots.DEVICE_ID,
            SecureSlots.ATTEST_TOKEN,
            SecureSlots.ATTEST_KEY_ID,
        )

        fun of(items: Map<SecureSlot, SecureStoreRead.Found>) = KeychainDto(
            items.map { (slot, found) ->
                SlotDto(
                    slot.service,
                    slot.account,
                    slot.shared,
                    found.value,
                    found.protection.name,
                )
            },
        )
    }
}

@Serializable
private class EnclaveDto(val generated: Int, val held: List<String>)

@Serializable
private class ClockDto(val nowEpochMillis: Long, val zone: String)

@Serializable
private class SystemUiDto(
    val shared: List<String>,
    val opened: List<String>,
    val settings: Int,
    // Defaulted: a state saved before shares carried a title still restores.
    val sharedTitles: List<String> = emptyList(),
)

@Serializable
private class StartedDto(
    val url: String,
    val description: String,
    val cancelled: Boolean,
    val finished: Boolean,
    /** The rule the transfer started under; a state saved before rules existed reads as any network. */
    val network: String = TransferNetwork.ANY.name,
)

@Serializable
private class DownloadsDto(val started: List<StartedDto>)

@Serializable
private class TriggerDto(
    val id: String,
    val kind: String,
    val earliestMillis: Long? = null,
    /** The form from before the mobile-data rule: `true` read as [WakeNetwork.ANY], `false` as [WakeNetwork.NONE]. */
    val requiresNetwork: Boolean? = null,
    val maxDelayMillis: Long? = null,
    val cadence: String? = null,
    val network: String? = null,
) {
    fun trigger(): WakeTrigger = when (kind) {
        LIBRARY_CHANGE -> WakeTrigger.LibraryChange(maxDelayMillis!!.milliseconds)
        else -> WakeTrigger.After(
            earliestMillis!!.milliseconds,
            network?.let(WakeNetwork::valueOf) ?: if (requiresNetwork == true) WakeNetwork.ANY else WakeNetwork.NONE,
            cadence?.let(WakeCadence::valueOf) ?: WakeCadence.BUSY,
        )
    }

    companion object {
        private const val AFTER = "after"
        private const val LIBRARY_CHANGE = "libraryChange"

        fun of(id: WakeId, trigger: WakeTrigger): TriggerDto = when (trigger) {
            is WakeTrigger.After -> TriggerDto(
                id.name,
                AFTER,
                trigger.earliest.inWholeMilliseconds,
                cadence = trigger.cadence.name,
                network = trigger.network.name,
            )
            is WakeTrigger.LibraryChange -> TriggerDto(
                id.name,
                LIBRARY_CHANGE,
                maxDelayMillis = trigger.maxDelay.inWholeMilliseconds,
            )
        }
    }
}

@Serializable
private class WakeDto(val pending: List<TriggerDto>, val scheduled: Int) {
    fun into(wakes: WakeMock) {
        wakes.pending.value = pending.associate { WakeId.valueOf(it.id) to it.trigger() }
        wakes.scheduled = scheduled
    }

    companion object {
        fun of(wakes: WakeMock) = WakeDto(
            pending = wakes.pending.value.map { (id, trigger) -> TriggerDto.of(id, trigger) },
            scheduled = wakes.scheduled,
        )
    }
}

@Serializable
private class CrumbDto(val level: String, val message: String?, val category: String?, val data: Map<String, String>)

@Serializable
private class CrashDto(
    val message: String?,
    val formatted: String?,
    val params: List<String>?,
    val exceptionValues: List<String?>,
    val breadcrumbs: List<CrumbDto>,
    val tags: Map<String, String>,
    val contexts: Map<String, Map<String, String>>,
) {
    /** The event as it left — its throwable is the process's, and stays behind. */
    fun event() = CrashEvent(
        message = message,
        formatted = formatted,
        params = params,
        exceptionValues = exceptionValues,
        breadcrumbs = breadcrumbs.map { Crumb(CrashLevel.valueOf(it.level), it.message, it.category, it.data) },
        tags = tags,
        contexts = contexts,
    )

    companion object {
        fun of(event: CrashEvent) = CrashDto(
            event.message,
            event.formatted,
            event.params,
            event.exceptionValues,
            event.breadcrumbs.map { CrumbDto(it.level.name, it.message, it.category, it.data) },
            event.tags,
            event.contexts,
        )
    }
}

@Serializable
private class CrashDumpsDto(val dumps: List<CrashDto>)

@Serializable
private class JobDto(
    /** The id of the library asset the job sends — the in-memory library's handle; `""` for another library's. */
    val asset: String,
    val contentType: String,
    val url: String,
    val headers: Map<String, String>,
    val state: String,
    /** What the platform said of the job's failure, `null` while it has none. */
    val failure: String?,
    val retriedOnce: Boolean,
    /** The rule the job was created under; a state saved before rules existed reads as any network. */
    val network: String = TransferNetwork.ANY.name,
)

@Serializable
private class QueueDto(
    val jobs: List<JobDto>,
    val created: List<Pair<String, String>>,
    val jobLimit: Int,
    val failCreate: Boolean,
) {
    fun into(queue: UploadQueueMock) {
        // A job's payload is the library resource the process that created it held: restored as the in-memory library's
        // handle of the same asset.
        jobs.forEach {
            queue.jobs += UploadQueueMock.Job(
                it.contentType,
                InMemoryResource(PlatformAssetId(it.asset)),
                UploadTarget(it.url, it.headers, TransferNetwork.valueOf(it.network)),
            ).apply {
                state = UploadJobState.valueOf(it.state)
                failure = it.failure?.let(::TransferFailure)
                retriedOnce = it.retriedOnce
            }
        }
        created.forEach { queue.created += CreatedUpload(it.first, it.second) }
        queue.jobLimit = jobLimit
        queue.failCreate = failCreate
    }

    companion object {
        fun of(queue: UploadQueueMock) = QueueDto(
            jobs = queue.jobs.map {
                JobDto(
                    (it.resource as? InMemoryResource)?.asset?.value.orEmpty(),
                    it.contentType,
                    it.target.url,
                    it.target.headers,
                    it.state.name,
                    it.failure?.detail,
                    it.retriedOnce,
                    it.target.network.name,
                )
            },
            created = queue.created.map { it.job to it.contentType },
            jobLimit = queue.jobLimit,
            failCreate = queue.failCreate,
        )
    }
}

@Serializable
private class EventDto(
    val name: String,
    val createdAtMillis: Long,
    val startsAtMillis: Long,
    val endsAtMillis: Long,
    /** Absent in a state saved before open-until dates: such an event is open until its end. */
    val openUntilMillis: Long? = null,
    val closed: Boolean = false,
    val completed: Boolean = false,
    val keyId: String? = null,
)

@Serializable
private class MembershipDto(
    val event: String,
    val device: String,
    val departed: Boolean,
    val manifest: String?,
    val manifestVersion: Long?,
    val receivedPosition: Long? = null,
)

@Serializable
private class CountDto(val event: String, val device: String, val count: Int)

@Serializable
private class StoredFileDto(
    val asset: String,
    val role: String,
    val filename: String,
    /** The SHA-256 its bytes were declared with; absent for an upload that declared none, or a state saved before. */
    val sha256: String? = null,
)

/** The backend's operator-set levers and counters. */
@Serializable
private class BackendLeversDto(
    val offline: Boolean,
    val failDeviceListing: Boolean,
    val failUnion: Boolean = false,
    val refuseNextCredential: Boolean,
    val refuseAttestation: DeviceRefusal? = null,
    val refuseAttestationDetail: String? = null,
    val minAppVersion: String?,
    /** Absent in a state saved before the lever existed: the fallback bearer served then. */
    val fallbackBearer: Boolean = true,
) {
    fun into(state: BackendState) {
        state.offline = offline
        state.failDeviceListing = failDeviceListing
        state.failUnion = failUnion
        state.refuseNextCredential = refuseNextCredential
        state.refuseAttestation = refuseAttestation
        state.refuseAttestationDetail = refuseAttestationDetail
        state.minAppVersion = minAppVersion
        state.fallbackBearer = fallbackBearer
    }
}

/** The backend's attestation: the challenges it issued, the tokens it minted, the key each device attested with. */
@Serializable
private class AttestDto(
    val challenges: List<String> = emptyList(),
    val minted: List<String> = emptyList(),
    val attestedKeys: Map<String, String> = emptyMap(),
) {
    fun into(state: BackendState) {
        state.challenges.addAll(challenges)
        state.minted.addAll(minted)
        state.attestedKeys.putAll(attestedKeys)
    }
}

@Serializable
private class BackendDto(
    val storedFiles: Map<String, List<StoredFileDto>>,
    val events: Map<String, EventDto>,
    val memberships: List<MembershipDto>,
    val deviceConfigs: Map<String, Triple<String, String, String>>,
    val deviceConfigWrites: Map<String, Int>,
    val publishes: List<CountDto>,
    val pushes: List<Triple<String, String, String>>,
    val levers: BackendLeversDto,
    /** The attestation the backend holds; defaulted, so a state saved before it loads (its tokens then re-minted). */
    val attest: AttestDto = AttestDto(),
    // The union log and its counter (decision record `changes/incremental-union`, D4): a device keeps its cursor across
    // a relaunch, so the positions it points into must survive one too. Defaulted, so a state saved before them loads.
    val unionLog: List<UnionChangeDto> = emptyList(),
    val nextSeq: Long = 1,
    val fetches: Map<String, List<UnionFetchDto>> = emptyMap(),
    /** Each push's announced position, by index into [pushes]; absent for a state saved before positions. */
    val pushSeqs: List<Long?> = emptyList(),
) {
    fun into(state: BackendState) {
        // Keyed `<event>/<device>` since `per-event-storage-layout`; a key of a state saved before it names no event,
        // and its bytes belonged to none — they are not restored.
        storedFiles.forEach { (key, files) ->
            val event = key.substringBefore('/', missingDelimiterValue = "").ifEmpty { return@forEach }
            state.storedFiles[event to key.substringAfter('/')] = files.mapTo(mutableSetOf()) {
                DeviceFile(AssetId(it.asset), ResourceRole.entries.first { role -> role.wire == it.role }, it.filename)
            }
            files.forEach { file -> file.sha256?.let { state.landedSha256["$key/${file.asset}/${file.role}"] = it } }
        }
        levers.into(state)
        events.forEach { (id, e) ->
            state.events[id] = BackendState.Event(
                e.name,
                Instant.fromEpochMilliseconds(e.createdAtMillis),
                Instant.fromEpochMilliseconds(e.startsAtMillis),
                Instant.fromEpochMilliseconds(e.endsAtMillis),
                Instant.fromEpochMilliseconds(e.openUntilMillis ?: e.endsAtMillis),
                e.closed,
                e.completed,
                e.keyId,
            )
        }
        memberships.forEach {
            state.memberships[it.event to it.device] =
                BackendState.Membership(
                    it.departed,
                    it.manifest?.let(::deviceManifestFromJson),
                    it.manifestVersion,
                    it.receivedPosition,
                )
        }
        deviceConfigs.forEach { (device, config) -> state.deviceConfigs[device] = PushEndpoint(config.first, config.second, config.third) }
        state.deviceConfigWrites.putAll(deviceConfigWrites)
        publishes.forEach { state.publishes[it.event to it.device] = it.count }
        pushes.forEachIndexed { i, push -> state.pushes += SentPush(push.first, push.second, push.third, pushSeqs.getOrNull(i)) }
        unionLog.forEach { state.changes += BackendState.Change(it.seq, it.event, it.device, AssetId(it.asset), it.gained) }
        state.nextSeq = nextSeq
        fetches.forEach { (event, list) ->
            state.fetches[event] = list.mapTo(mutableListOf()) { UnionFetch(it.device, it.trigger, it.from, it.to, it.served) }
        }
        attest.into(state)
    }

    companion object {
        fun of(state: BackendState) = BackendDto(
            storedFiles = state.storedFiles.entries.associate { (key, files) ->
                "${key.first}/${key.second}" to files.map {
                    val sha256 = state.landedSha256["${key.first}/${key.second}/${it.assetId.value}/${it.role.wire}"]
                    StoredFileDto(it.assetId.value, it.role.wire, it.filename, sha256)
                }
            },
            events = state.events.mapValues { (_, e) ->
                EventDto(
                    e.name,
                    e.createdAt.toEpochMilliseconds(),
                    e.startsAt.toEpochMilliseconds(),
                    e.endsAt.toEpochMilliseconds(),
                    e.openUntil.toEpochMilliseconds(),
                    e.closed,
                    e.completed,
                    e.keyId,
                )
            },
            memberships = state.memberships.map { (key, m) ->
                MembershipDto(
                    key.first,
                    key.second,
                    m.departed,
                    m.manifest?.encodeToJson(),
                    m.manifestVersion,
                    m.receivedPosition,
                )
            },
            deviceConfigs = state.deviceConfigs.mapValues { Triple(it.value.kind, it.value.token, it.value.env) },
            deviceConfigWrites = state.deviceConfigWrites.toMap(),
            publishes = state.publishes.map { CountDto(it.key.first, it.key.second, it.value) },
            pushes = state.pushes.map { Triple(it.eventId, it.deviceId, it.token) },
            pushSeqs = state.pushes.map { it.seq },
            unionLog = state.changes.map {
                UnionChangeDto(
                    it.seq,
                    it.eventId,
                    it.deviceId,
                    it.assetId.value,
                    it.gained,
                )
            },
            nextSeq = state.nextSeq,
            fetches = state.fetches.mapValues { (_, list) ->
                list.map { UnionFetchDto(it.deviceId, it.trigger, it.from, it.to, it.served) }
            },
            attest = AttestDto(state.challenges.toList(), state.minted.toList(), state.attestedKeys.toMap()),
            levers = BackendLeversDto(
                offline = state.offline,
                failDeviceListing = state.failDeviceListing,
                failUnion = state.failUnion,
                refuseNextCredential = state.refuseNextCredential,
                refuseAttestation = state.refuseAttestation,
                refuseAttestationDetail = state.refuseAttestationDetail,
                minAppVersion = state.minAppVersion,
                fallbackBearer = state.fallbackBearer,
            ),
        )
    }
}

@Serializable
private class UnionChangeDto(
    val seq: Long,
    val event: String,
    val device: String,
    val asset: String,
    val gained: Boolean,
)

@Serializable
private class UnionFetchDto(val device: String?, val trigger: String?, val from: Long?, val to: Long, val served: Int)

@Serializable
private class ResourceDto(val kind: String, val contentType: String, val filename: String)

@Serializable
private class AssetDto(
    val id: String,
    val created: String?,
    val resources: List<ResourceDto>,
    val isScreenshot: Boolean,
    val isScreenRecording: Boolean,
    val isVideo: Boolean,
    val isEdited: Boolean,
    val pixelArea: Long?,
) {
    /** The photo as the library holds it — its resources under the in-memory library's handle. */
    fun asset() = GalleryAssetResources(
        GalleryAsset(
            id = PlatformAssetId(id),
            created = created?.let(Instant::parse),
            isScreenshot = isScreenshot,
            isScreenRecording = isScreenRecording,
            isVideo = isVideo,
            isEdited = isEdited,
            pixelArea = pixelArea,
        ),
        resources.map {
            GalleryResource(
                ResourceKind.valueOf(it.kind),
                it.contentType,
                it.filename,
                InMemoryResource(PlatformAssetId(id)),
            )
        },
    )

    companion object {
        fun of(asset: GalleryAssetResources) = AssetDto(
            id = asset.id.value,
            created = asset.asset.created?.toString(),
            resources = asset.resources.map { ResourceDto(it.kind.name, it.contentType, it.originalFilename) },
            isScreenshot = asset.asset.isScreenshot,
            isScreenRecording = asset.asset.isScreenRecording,
            isVideo = asset.asset.isVideo,
            isEdited = asset.asset.isEdited,
            pixelArea = asset.asset.pixelArea,
        )
    }
}

@Serializable
private class LibraryDto(
    val library: List<AssetDto>,
    val access: String,
    val answer: String,
    val userAlbums: Map<String, List<String>>,
    val selection: List<AssetDto>?,
    val attempts: List<Pair<String, Int>>,
    val albumCounter: Int,
    val created: Map<String, Pair<String, List<String>>>,
    val createdLog: List<Pair<String, String>>,
    val addedLog: List<Pair<String, List<String>>>,
    val deletedAlbums: List<String>,
    val failNextEnumeration: Boolean,
    val byIdReadable: Boolean,
    val imported: List<String>,
) {
    fun into(state: LibraryState) {
        state.library.value = library.map { it.asset() }
        state.access.value = LibraryGrant.valueOf(access)
        state.answer = LibraryGrant.valueOf(answer)
        state.writableAlbums?.value = userAlbums.mapValues { (_, ids) -> ids.mapTo(mutableSetOf(), ::PlatformAssetId) }
        state.selection.value = selection?.map { it.asset() }
        attempts.forEach { (correlation, n) -> state.attempts[correlation] = n }
        state.albumCounter = albumCounter
        created.forEach { (id, album) ->
            state.created[id] = LibraryState.Album(album.first, album.second.mapTo(mutableSetOf(), ::PlatformAssetId))
        }
        state.createdLog.addAll(createdLog)
        addedLog.forEach { (album, ids) -> state.addedLog += album to ids.map(::PlatformAssetId) }
        state.deletedAlbums.addAll(deletedAlbums)
        state.failNextEnumeration = failNextEnumeration
        state.byIdReadable = byIdReadable
        state.imports.restoreImported(imported)
    }

    companion object {
        fun of(state: LibraryState) = LibraryDto(
            library = state.library.value.map(AssetDto::of),
            access = state.access.value.name,
            answer = state.answer.name,
            userAlbums = state.userAlbums.value.mapValues { (_, ids) -> ids.map { it.value } },
            selection = state.selection.value?.map(AssetDto::of),
            attempts = state.attempts.map { (correlation, n) -> correlation to n },
            albumCounter = state.albumCounter,
            created = state.created.mapValues { (_, album) -> album.title to album.members.map { it.value } },
            createdLog = state.createdLog.toList(),
            addedLog = state.addedLog.map { (album, ids) -> album to ids.map { it.value } },
            deletedAlbums = state.deletedAlbums.toList(),
            failNextEnumeration = state.failNextEnumeration,
            byIdReadable = state.byIdReadable,
            imported = state.imports.imported,
        )
    }
}

/** A network access as the state file spells it — the names the enum this replaced wrote, plus RESTRICTED. */
private fun accessName(access: NetworkAccess): String = when (access) {
    is NetworkAccess.Online -> if (access.restricted) "RESTRICTED" else "ONLINE"
    NetworkAccess.Offline -> "OFFLINE"
    NetworkAccess.Blocked -> "BLOCKED"
}

private fun accessNamed(name: String): NetworkAccess = when (name) {
    "RESTRICTED" -> NetworkAccess.Online(restricted = true)
    "OFFLINE" -> NetworkAccess.Offline
    "BLOCKED" -> NetworkAccess.Blocked
    else -> NetworkAccess.Online(restricted = false)
}
