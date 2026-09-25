package app.snapsync.fake

import app.snapsync.model.EventConfig
import app.snapsync.model.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `NSFileReadNoPermissionError`: the class Apple documents for reading a protected file before first
 * unlock, which is what an unreadable membership is on a device. A diagnostic only — no decision reads it —
 * chosen so a world's skip forensics read like a device log. It replaces the Keychain status this double
 * reported while the config was still a Keychain item.
 */
private const val UNREADABLE_STATUS: Int = 257

/**
 * The honest in-memory config ports: a [persisted] membership — the "file" — and a [readable] cell saying
 * whether it can be read at all. Held to `ConfigStoreContract`, as the App-Group file store is.
 *
 * Two cells because the three-state read collapses to two questions, *is there a membership* and *may I
 * look*. With [readable] false the read is [ConfigRead.Unavailable], never [ConfigRead.None] — that
 * confusion is a false leave — and writes are refused, as a store that cannot reach its storage must refuse
 * them rather than half-complete a leave. [config] keeps showing [persisted] meanwhile: a process that could
 * read it once retains the last good value (`configAfterReload`).
 *
 * State arrives by constructor and the cells are the caller's; levers belong in `:test:world`.
 */
internal class InMemoryConfigStore(
    private val persisted: MutableStateFlow<EventConfig?>,
    private val readable: MutableStateFlow<Boolean>,
) : ConfigSource, ConfigStore, ConfigReader {

    override val config: StateFlow<EventConfig?> = persisted.asStateFlow()

    override fun read(): ConfigRead = when {
        !readable.value -> ConfigRead.Unavailable(UNREADABLE_STATUS)
        else -> persisted.value?.let { ConfigRead.Joined(it) } ?: ConfigRead.None
    }

    override suspend fun save(config: EventConfig) {
        check(readable.value) { "config store unreadable — cannot persist config" }
        persisted.value = config
    }

    override suspend fun clear() {
        check(readable.value) { "config store unreadable — cannot clear config" }
        persisted.value = null
    }
}
