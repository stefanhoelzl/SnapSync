package app.snapsync.config

import app.snapsync.s3.S3Config
import kotlinx.coroutines.flow.StateFlow

/**
 * The state port for the active S3 config: a level-triggered holder whose current value is always
 * available synchronously — the persisted [S3Config], or `null` when none has been provisioned yet.
 * The setup gate observes this to decide whether the "storage" step is satisfied. Like the
 * permission seam, truth arrives here and nowhere else.
 */
interface ConfigSource {
    val config: StateFlow<S3Config?>
}

/**
 * The command port for provisioning config: persist [config] and update the [ConfigSource].
 * Saving a config equal (field-for-field) to the current one is an idempotent no-op; saving a
 * different one replaces it silently (the ledger is deliberately left untouched — see design.md
 * D6). Implementations typically also implement [ConfigSource] as one platform adapter; consumers
 * depend on each port separately.
 */
interface ConfigStore {
    suspend fun save(config: S3Config)
}

/** Field-wise equality for the secret-bearing value type (S3Config is not a data class). */
internal fun S3Config.sameAs(other: S3Config): Boolean =
    bucket == other.bucket &&
        region == other.region &&
        endpoint == other.endpoint &&
        accessKeyId == other.accessKeyId &&
        secretAccessKey == other.secretAccessKey
