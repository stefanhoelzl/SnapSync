package app.snapsync.config

import app.snapsync.model.EventConfig
import app.snapsync.ports.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import app.snapsync.ports.configReadFrom

import app.snapsync.keychain.ACCESSIBLE_AFTER_FIRST_UNLOCK
import app.snapsync.keychain.IosKeychain
import app.snapsync.ports.Keychain
import app.snapsync.ports.KeychainRead
import app.snapsync.ports.needsMigration
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * The iOS [ConfigSource]/[ConfigStore]/[ConfigReader]: persists the serialized [EventConfig] as a
 * single Keychain generic-password item (encrypted at rest, survives app updates and process death).
 *
 * All Keychain access goes through `:domain:keychain` (capability `architecture-guards`: `SecItem*`
 * lives in exactly one module), which is what makes the item **background-readable**: it is stored
 * `kSecAttrAccessibleAfterFirstUnlock`, so the upload extension — which the OS invokes when the device
 * is *idle*, and therefore usually *locked* — can actually read it. Under the old iOS default
 * (`WhenUnlocked`) it could not, and the read failure was indistinguishable from "no event joined".
 * A legacy item written under the weaker class is migrated in place on the first successful read.
 *
 * No `kSecAttrAccessGroup` is set: with the app's `keychain-access-groups` entitlement declaring the
 * shared group as its (only/first) entry, items land in that shared group by default, so the
 * background upload extension — declaring the same entitlement — reads the same item (its `eventId`
 * **and** its `minPhotoDate` cutoff, capability `photo-selection-policy`; not its `direction`, which is an
 * app-side upload-arm gate — capability `join-event`). The whole [EventConfig] is serialized regardless,
 * so `direction` round-trips; a legacy item written before the field existed decodes to `Direction.Both`
 * (`ignoreUnknownKeys`/default). This avoids hardcoding the team-id prefix in code; sharing is purely an
 * entitlement concern.
 *
 * Seeds [config] synchronously at construction, mirroring the permission adapter's synchronous-real
 * guarantee. **Readers that act on the absence of a config must use [read], not [config]** — see
 * [ConfigRead].
 */
class KeychainConfigStore(
    private val keychain: Keychain = IosKeychain(service = "app.snapsync.config", account = "eventconfig"),
    private val log: Logger = Logger.withTag("keychainConfig"),
) : ConfigSource, ConfigStore, ConfigReader {

    private val configJson = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(read().joinedOrNull())
    override val config: StateFlow<EventConfig?> = state

    override suspend fun save(config: EventConfig) {
        // Idempotent: re-saving an equal config (eventId + name + minPhotoDate + direction) is a no-op;
        // any difference replaces (data-class equality covers every field).
        if (state.value == config) return
        keychain.write(configJson.encodeToString(EventConfig.serializer(), config))
        state.value = config
    }

    override suspend fun clear() {
        // Idempotent: deleting an absent item is treated as success (the leave path tolerates it).
        keychain.delete()
        state.value = null
    }

    /**
     * The three-state read (capability `event-link`). Migrates a legacy item's accessibility class
     * in place — value untouched — the first time it can be read.
     *
     * The extension's cycle reads through **this**, never through [config]: only a definite
     * [ConfigRead.None] may be taken as "this device left the event".
     */
    override fun read(): ConfigRead {
        val raw = keychain.read()
        if (raw is KeychainRead.Found && needsMigration(raw.accessibility, ACCESSIBLE_AFTER_FIRST_UNLOCK)) {
            keychain.migrateAccessibility()
        }
        if (raw is KeychainRead.Unavailable) {
            log.w { "config keychain unreadable (status=${raw.status}) — NOT 'no config'; caller must defer" }
        }
        return configReadFrom(raw) { stored ->
            // A legacy item that does not decode reads as no config, deliberately (capability
            // `photo-selection-policy`): the device falls back to the setup gate and the user re-scans, and
            // nothing uploads meanwhile. No default cutoff is substituted — this store seeds
            // synchronously and cannot fetch the event's `createdAt`, and the empty string is not a
            // legal cutoff (it would admit the whole library).
            runCatching { configJson.decodeFromString(EventConfig.serializer(), stored) }
                .onFailure {
                    log.w(it) {
                        "keychain item did not decode (legacy item without a cutoff?) — reading as no config; re-join required"
                    }
                }
                .getOrNull()
        }
    }

    /**
     * Re-read the Keychain into [config]. A writer in another process (the app saving a new event)
     * does not notify this process's [StateFlow], so a cross-process **reader** — the upload
     * extension, which lives across multiple `process()` cycles — must refresh before each read or it
     * keeps serving the event it saw at construction (uploading to a stale, previously-joined event).
     *
     * The app also calls this when protected data becomes available, since a config that was
     * unreadable at construction (a background launch before the first unlock) seeded `null`.
     */
    fun reload() {
        state.value = read().joinedOrNull()
    }

    /** `null` for both *absent* and *unreadable* — acceptable for the UI-facing [config], never for the reconciler. */
    private fun ConfigRead.joinedOrNull(): EventConfig? = (this as? ConfigRead.Joined)?.config
}
