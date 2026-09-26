package app.snapsync.feature.support

import app.snapsync.model.EventConfig
import app.snapsync.services.config.ConfigService
import app.snapsync.ports.Clock

/**
 * What a membership feature wrote, read at the port: the membership file's saves and clears, over [files]. [saved] is
 * the membership the last save persisted — `null` until one lands, whatever the file was seeded with.
 */
class ConfigWrites(val files: RecordingFiles = RecordingFiles()) {
    val saved: EventConfig? get() = if (files.configSaves > 0) files.persistedConfig() else null
    val saveCount: Int get() = files.configSaves
    val cleared: Boolean get() = files.configCleared

    /** The real membership service over these files, seeded with [initial]. */
    fun service(initial: EventConfig?, clock: Clock = testClock()): ConfigService = configService(initial, files, clock)
}
