package app.snapsync.fake

import app.snapsync.model.CrashEvent
import app.snapsync.ports.AttestStore
import app.snapsync.ports.Backend
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.model.DeviceFile
import kotlin.time.Instant
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.ProcessInfo
import app.snapsync.model.Availability
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * **The honest doubles' only public surface: a factory per port, returning the PORT type**
 * (`docs/architecture.md`; law `docs/architecture.md` "The module set withholds").
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
fun inMemoryDeviceIntegrity(available: Boolean = true): DeviceIntegrity = InMemoryDeviceIntegrity(available)

/**
 * The in-memory backend (see [InMemoryBackend]). [storedFiles] is the byte store the OS's uploader writes — a cell
 * the caller holds, keyed by device id; [minimumAppVersion] set is a backend refusing this build.
 */
fun inMemoryBackend(
    storedFiles: MutableMap<String, MutableSet<DeviceFile>> = mutableMapOf(),
    capacity: Int = 10,
    minimumAppVersion: String? = null,
    createdAt: Instant = Instant.fromEpochSeconds(0),
): Backend = InMemoryBackend(storedFiles, capacity, minimumAppVersion, createdAt)

fun inMemoryAttestStore(token: String? = null, keyId: String? = null): AttestStore =
    InMemoryAttestStore(token, keyId)

fun inMemoryCrashReporter(
    started: MutableStateFlow<Boolean>,
    dumps: MutableStateFlow<List<CrashEvent>>,
): CrashReporter = InMemoryCrashReporter(started, dumps)

fun inMemoryCrashReporter(): CrashReporter = InMemoryCrashReporter()

/** [readable] is the caller's own cell: a device unlocked since boot by default. */
fun inMemoryProcessInfo(
    protectedData: MutableStateFlow<Availability> = MutableStateFlow(Availability.AVAILABLE),
): ProcessInfo = InMemoryProcessInfo(protectedData)
