package app.snapsync.mock

import app.snapsync.model.CrashEvent
import app.snapsync.ports.AttestStore
import app.snapsync.ports.Backend
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.model.DeviceFile
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.ProcessInfo
import app.snapsync.model.Availability
import app.snapsync.model.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * **The port-typed factories** (`docs/architecture.md`; law "The module set withholds"): the contract bindings and
 * the tests build a port's in-memory implementation here, over cells they hold — the same `internal` classes a mock's
 * port face is (`docs/testing.md`, "Mocks").
 *
 * The implementations behind these functions are `internal`. `internal` is module-scoped, so a consumer in another
 * module cannot name them, cannot widen them, and cannot reach a member the port does not declare. Honesty is
 * therefore not a rule about what a double may expose; it is a property of what a consumer can express, enforced by
 * the compiler. What an operator may do to a system is its mock's operator face — a separate type — never a member
 * of the port's implementation.
 *
 * A factory needing operator-visible state takes that state as a **parameter**: the caller keeps its own reference
 * and observes it there.
 */
fun inMemoryDeviceIntegrity(available: Boolean = true): DeviceIntegrity = InMemoryDeviceIntegrity(available)

/**
 * A [BackendMock]'s port, over a byte store the caller holds: [storedFiles] is what the OS's uploader writes, keyed by
 * device id; [minimumAppVersion] set is a backend refusing this build. Every other default is [BackendMock]'s.
 */
fun inMemoryBackend(
    storedFiles: MutableMap<String, MutableSet<DeviceFile>> = mutableMapOf(),
    minimumAppVersion: String? = null,
): Backend = BackendMock(storedFiles).also { it.operator.minAppVersion = minimumAppVersion }.port()

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

/** [access] is the caller's own cell: what the operating system reports about the network. */
fun inMemoryNetworkMonitor(access: MutableStateFlow<NetworkAccess>): NetworkMonitor = InMemoryNetworkMonitor(access)
