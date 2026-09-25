package app.snapsync.fake

import app.snapsync.model.FileArea
import app.snapsync.ports.Files
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
