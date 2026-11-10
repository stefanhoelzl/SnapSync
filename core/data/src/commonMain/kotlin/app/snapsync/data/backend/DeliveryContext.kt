package app.snapsync.data.backend

import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.network.NetworkReadings
import app.snapsync.domain.delivery.ConditionFacts
import app.snapsync.domain.delivery.DeliveryFacts
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.Clock
import app.snapsync.ports.DeviceConditions
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * **The delivery context** (decision record `changes/delivery-record`, D8; `docs/architecture.md`, "The delivery
 * record"): the device a request comes from, its platform and its conditions, which every request carries in ONE header
 * for the service's request log — `x-snapsync-context: device=<id>;os=ios/18.6;model=iPhone12,8;…`, in the words
 * [DeliveryFacts] gives each fact. The service reads nothing else from it and refuses nothing for it.
 *
 * Each process hands the [ContextPart]s it can read: the app every one, the upload extension what it has. A part that
 * cannot be read now says nothing, so a request is never held up or failed for its context.
 */
class DeliveryContext(private vararg val parts: ContextPart) {
    /** The context header — none when no part has anything to say. */
    suspend fun headers(): Map<String, String> = DeliveryFacts.header(parts.flatMap { it.facts().toList() })
}

/** One group of the context's facts, as `key → value`, read now. */
interface ContextPart {
    suspend fun facts(): Map<String, String>
}

/** The build and its platform: `os`, `model`, `flavour`. */
class BuildContext(private val build: BuildInfo) : ContextPart {
    override suspend fun facts(): Map<String, String> = DeliveryFacts.os(build.platform, build.osVersion) +
        mapOf("model" to build.deviceModel, "flavour" to DeliveryFacts.flavour(build.reporterEnvironment))
}

/** This install's device id, when it can be read: `device`. */
class IdentityContext(private val identity: DeviceIdentity) : ContextPart {
    override suspend fun facts(): Map<String, String> = when (val read = identity.id()) {
        is DeviceIdRead.Ok -> mapOf("device" to read.value.value)
        DeviceIdRead.Absent, is DeviceIdRead.Unavailable -> emptyMap()
    }
}

/** The photo access the member gave: `access`. */
class AccessContext(private val grant: GrantRead) : ContextPart {
    override suspend fun facts(): Map<String, String> = mapOf("access" to DeliveryFacts.access(grant.access()))
}

/** The member's mobile-data choice: `mobile_data`. */
class MobileDataContext(private val mobileData: MobileDataStore) : ContextPart {
    override suspend fun facts(): Map<String, String> =
        mapOf("mobile_data" to DeliveryFacts.mobileData(mobileData.photoNetwork()))
}

/** The network: `network`, the platform's first answer within [timeout]. */
class NetworkContext(
    private val network: NetworkReadings,
    private val timeout: Duration = 300.milliseconds,
) : ContextPart {
    override suspend fun facts(): Map<String, String> =
        DeliveryFacts.network(withTimeoutOrNull(timeout) { network.watch().firstOrNull() })
}

/**
 * The device's power and background allowance: `low_power`, `background`. Read at most once per [refresh] — a read may
 * hop onto the main thread, and every request would otherwise wait on it — and within [timeout], or the last reading
 * stands.
 */
class ConditionsContext(
    private val conditions: DeviceConditions,
    private val clock: Clock,
    private val refresh: Duration = 1.minutes,
    private val timeout: Duration = 300.milliseconds,
) : ContextPart {
    private var last: Map<String, String> = emptyMap()
    private var readAt: Instant? = null

    override suspend fun facts(): Map<String, String> = when (ConditionFacts.due(readAt, clock.now(), refresh)) {
        true -> read()
        false -> last
    }

    private suspend fun read(): Map<String, String> {
        readAt = clock.now()
        last = ConditionFacts.of(withTimeoutOrNull(timeout) { conditions.read() }, last)
        return last
    }
}
