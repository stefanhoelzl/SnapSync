package app.snapsync.fake

import app.snapsync.model.FileArea
import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.ports.Files
import app.snapsync.ports.SecureStore
import app.snapsync.ports.Preferences

// The thin storage ports' mocks: port-typed factories over `internal` classes, as every fake here
// (`docs/testing.md`). Their own file because `Factories.kt` is at its function ceiling.

/**
 * The storage mocks' state is the caller's own cells: [shared] and [private] are the two areas' files (`null`
 * for an area this process cannot reach), and [denied] names present files the process may not touch.
 */
fun inMemoryFiles(
    shared: MutableMap<String, ByteArray>? = mutableMapOf(),
    private: MutableMap<String, ByteArray>? = mutableMapOf(),
    denied: Set<Pair<FileArea, String>> = emptySet(),
): Files = InMemoryFiles(mapOf(FileArea.SHARED to shared, FileArea.PRIVATE to private), denied)

/** [values] is the caller's own cell. */
fun inMemoryPreferences(values: MutableMap<String, String> = mutableMapOf()): Preferences = InMemoryPreferences(values)

/**
 * [items] is the caller's own cell (slot → value and protection); [unavailable] is a store that cannot be read at
 * all (a device not unlocked since boot).
 */
fun inMemorySecureStore(
    items: MutableMap<SecureSlot, SecureStoreRead.Found> = mutableMapOf(),
    unavailable: Boolean = false,
): SecureStore = InMemorySecureStore(items, unavailable)

/**
 * Real in-memory SQLite databases (see [InMemoryDatabases]). [refusals] is the operator's cell: while it names a
 * database, every open of that name answers the refusal given — `Missing`, `OldSchema` or `Failed` — so a caller can
 * reach the branches a device reaches only by accident.
 */
fun inMemoryDatabases(refusals: Map<String, app.snapsync.ports.DbOpen> = emptyMap()): app.snapsync.ports.Databases =
    InMemoryDatabases(refusals)
