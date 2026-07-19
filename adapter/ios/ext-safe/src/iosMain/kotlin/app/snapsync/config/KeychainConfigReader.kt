package app.snapsync.config

import app.snapsync.model.EventConfig
import app.snapsync.ports.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.configReadFrom

import app.snapsync.keychain.ACCESSIBLE_AFTER_FIRST_UNLOCK
import app.snapsync.keychain.IosKeychain
import app.snapsync.ports.Keychain
import app.snapsync.ports.KeychainRead
import app.snapsync.ports.needsMigration
import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json

/**
 * The legacy-Keychain config seat — what remains of the pre-11a store (`KeychainConfigStore`
 * died at the migration finale with the write-through): it can READ the legacy item
 * (`app.snapsync.config`/`eventconfig`, a runtime-identity pin — capability
 * `architecture-guards`) and DELETE it on the leave path, and nothing else. No save — no config
 * value is ever written to the Keychain again — so the revert direction stays sacrificed
 * (fix-forward). The leave-path [deleteLegacyItem] is NOT a write-through remnant; it is the 11a
 * clear contract's surviving half: were a leave to delete only the file, the very next read would
 * find file-missing + item-present — exactly the resurrection state the 11a clear ordering
 * existed to prevent — and silently undo the leave on every migrated device.
 *
 * **Why the read survives the write-through's end.** This branch ships to `main` — and therefore
 * to the whole installed base — as ONE merge: at ship time every joined production device is a
 * pre-11a device whose config file has never existed. `FileBackedConfigStore`'s missing-file read
 * consults this reader exactly once per unmigrated device (then migrates the found membership
 * into the file); without it, the update would silently read every joined device as left. The
 * per-step TestFlight soak the 11a→13b staging assumed never happened on this branch. Deleting
 * this reader — the true "reinstall = left the event" flip — is a designated POST-SHIP change,
 * gated on production soak (capability `event-rejoin-reconciliation`).
 *
 * The one non-value write kept: a legacy item stored under the weaker pre-11a accessibility class
 * is migrated in place (attributes only, value untouched) on the first successful read, exactly
 * as the 11a store did — without it a locked-device extension read of an old item would sit
 * unreadable until the app next ran unlocked.
 *
 * A legacy item that does not decode reads as no config, deliberately (capability
 * `photo-selection-policy`): the device falls back to the setup gate and the user re-scans, and
 * nothing uploads meanwhile — the pre-finale rule, unchanged, Keychain-side only.
 */
class KeychainConfigReader(
    private val keychain: Keychain = IosKeychain(service = "app.snapsync.config", account = "eventconfig"),
    private val log: Logger = Logger.withTag("keychainConfigReader"),
) : ConfigReader {

    private val configJson = Json { ignoreUnknownKeys = true }

    override fun read(): ConfigRead {
        val raw = keychain.read()
        if (raw is KeychainRead.Found && needsMigration(raw.accessibility, ACCESSIBLE_AFTER_FIRST_UNLOCK)) {
            keychain.migrateAccessibility()
        }
        if (raw is KeychainRead.Unavailable) {
            log.w { "legacy config keychain unreadable (status=${raw.status}) — NOT 'no config'; caller must defer" }
        }
        return configReadFrom(raw) { stored ->
            runCatching { configJson.decodeFromString(EventConfig.serializer(), stored) }
                .onFailure {
                    log.w(it) {
                        "legacy keychain item did not decode (pre-cutoff item?) — reading as no config; re-join required"
                    }
                }
                .getOrNull()
        }
    }

    /**
     * Delete the legacy item — the leave path's migration hygiene (see the class doc: without it
     * every leave on a migrated device resurrects from the fallback). Idempotent (deleting an
     * absent item is success) and best-effort at the call site.
     */
    fun deleteLegacyItem() {
        keychain.delete()
    }
}
