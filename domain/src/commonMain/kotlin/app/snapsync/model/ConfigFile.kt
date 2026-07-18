package app.snapsync.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The **config-file envelope** (capability `event-link`, migration step 11a): the persisted
 * [EventConfig] rides in an App-Group file as `{"v": <version>, "payload": <EventConfig JSON>}`.
 *
 * The envelope exists for exactly one reason: a **future build must be able to change the format
 * without a past build misreading the result as a leave**. The file read is what decides "this
 * device left the event" (capability `event-rejoin-reconciliation`), so a revert build that opens a
 * successor's file must land on *unreadable* ([ConfigFileDecode.Foreign]) — deferring, membership
 * intact — never on *absent*. A bare `EventConfig` JSON could not make that distinction: any
 * unparseable content would be indistinguishable from a corrupt current-format file.
 *
 * Version handling, exhaustively:
 * - `v == 1`, payload decodes → [ConfigFileDecode.Valid].
 * - `v == 1`, payload does not decode (e.g. no `minPhotoDate`) → [ConfigFileDecode.Unusable] —
 *   which the port mapping (`configReadViaFile`) treats as **unreadable**, never absent: this
 *   adapter's own atomic writes should make an unusable current-version file unreachable, so one
 *   is an unexplained state, and an unexplained state defers rather than driving a leave. (The
 *   Keychain legacy-item rule — undecodable reads as no config — deliberately does NOT transfer;
 *   it stays in force on the Keychain side only.) Nothing uploads meanwhile either way
 *   (capability `photo-selection-policy`), and a re-scan overwrites the file.
 * - any other `v` (a future format) → [ConfigFileDecode.Foreign] — this build cannot interpret it,
 *   which is *unreadable*, never *absent* and never a crash.
 * - text that is not an envelope at all → [ConfigFileDecode.Foreign]: only content this build can
 *   **positively** interpret may drive a decision; everything else defers.
 *
 * Unknown *keys* are ignored on both the envelope and the payload, so a same-version additive
 * change needs no version bump (the [EventConfig] defaults handle it, as they always have).
 */
const val CONFIG_FILE_VERSION: Int = 1

/** The three outcomes of decoding config-file text. See [CONFIG_FILE_VERSION] for the full table. */
sealed interface ConfigFileDecode {

    /** The envelope is this build's version and its payload decodes. */
    data class Valid(val config: EventConfig) : ConfigFileDecode

    /**
     * The envelope is this build's version but carries no usable config (payload absent or
     * undecodable). Mapped to **unreadable** by `configReadViaFile` — never a leave; see the
     * version table on [CONFIG_FILE_VERSION] for why the Keychain legacy-item rule does not apply.
     */
    data object Unusable : ConfigFileDecode

    /**
     * The file exists but this build cannot positively interpret it (a future envelope version, or
     * not an envelope at all). Reads as **unreadable** — never absent, so it can never be taken
     * for a leave.
     */
    data class Foreign(val reason: String) : ConfigFileDecode
}

@Serializable
private class ConfigFileEnvelope(val v: Int, val payload: JsonElement? = null)

private val configFileJson = Json { ignoreUnknownKeys = true }

/** Serialize [config] into the versioned envelope this build's [decodeConfigFile] reads back. */
fun encodeConfigFile(config: EventConfig): String = configFileJson.encodeToString(
    ConfigFileEnvelope.serializer(),
    ConfigFileEnvelope(
        v = CONFIG_FILE_VERSION,
        payload = configFileJson.encodeToJsonElement(EventConfig.serializer(), config),
    ),
)

/** Decode config-file text per the version table on [CONFIG_FILE_VERSION]. Total: never throws. */
fun decodeConfigFile(text: String): ConfigFileDecode {
    val envelope = runCatching { configFileJson.decodeFromString(ConfigFileEnvelope.serializer(), text) }
        .getOrElse { return ConfigFileDecode.Foreign("not a config envelope") }
    if (envelope.v != CONFIG_FILE_VERSION) {
        return ConfigFileDecode.Foreign("envelope version ${envelope.v}; this build reads $CONFIG_FILE_VERSION")
    }
    val payload = envelope.payload ?: return ConfigFileDecode.Unusable
    return runCatching { configFileJson.decodeFromJsonElement(EventConfig.serializer(), payload) }
        .fold(onSuccess = ConfigFileDecode::Valid, onFailure = { ConfigFileDecode.Unusable })
}

/**
 * Whether a file-read error means the file is **genuinely absent** — the only error class that may
 * read as "no config" (settle-list ⑥, decision record: `changes/archive/…-migrate-config-to-app-group-file`).
 *
 * Grounded on Apple's data-protection contract: reading a **protected** file before first unlock
 * fails with a permission-class error (`NSFileReadNoPermissionError` 257 / POSIX `EPERM`), never
 * with not-found — so not-found (`NSFileReadNoSuchFileError` 260, `NSFileNoSuchFileError` 4, POSIX
 * `ENOENT` 2) is definitive absence, and **any other error whatsoever** is *unreadable*: the
 * caller must defer, exactly as an unreadable Keychain item defers. Admitting an unknown error
 * into the absent class would recreate the false-leave bug this whole seam exists to prevent.
 */
fun isConfigFileAbsence(domain: String?, code: Long): Boolean = when (domain) {
    "NSCocoaErrorDomain" -> code == 260L || code == 4L
    "NSPOSIXErrorDomain" -> code == 2L
    else -> false
}
