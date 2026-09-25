package app.snapsync.contracts

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.EventConfig
import app.snapsync.model.encodeConfigFile
import app.snapsync.ports.ConfigRead
import app.snapsync.ports.ConfigReader
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The states the persisted membership can be found in, as far as a clause cares.
 *
 * Names say what is behind the ports, never how a binding produced it: [INACCESSIBLE] is a device before
 * first unlock to a fake and an unentitled process to a real store, and the ports answer both alike.
 */
enum class ConfigStoreState {
    /** Nothing can be read or written — no reachable storage, or protected data unavailable. */
    INACCESSIBLE,

    /** Readable, and holding no membership: this device is not joined. */
    ABSENT,

    /** Holding [ConfigStoreContract.seedConfig] for the clause being run. */
    JOINED,

    /** Holding [ConfigStoreContract.FOREIGN_FILE]: a record another build's format wrote. */
    FOREIGN,

    /** Holding [ConfigStoreContract.UNUSABLE_FILE]: this build's format, with a payload that does not decode. */
    UNUSABLE,

    /** A record is present, but reading it fails with an error that is not "it does not exist". */
    FILE_UNREADABLE,
}

/**
 * The three config ports as one subject: one adapter implements all three, and the obligations that matter
 * cross them — a save is visible to both the read and the flow, a clear turns the read into `None` AND the
 * flow into `null` (decision record: `changes/contract-app-group-stores`, D1).
 */
class ConfigPorts(val source: ConfigSource, val store: ConfigStore, val reader: ConfigReader)

/**
 * What the persisted membership promises (`docs/architecture.md` — this list IS the specification of
 * the ports' obligations).
 *
 * The point of the surface is that ONE answer — [ConfigRead.None] — means "this device left the event", and
 * it is reached from exactly one fact: the record is definitively missing. Every other failure, of any kind,
 * reads as [ConfigRead.Unavailable] and defers (capability `photo-sharing`). Seeds derive from
 * the clause id so every binding writes the same thing.
 *
 * Not covered, because no host can enter it: a background wake before first unlock, where the file is
 * present and encrypted. That belief lives in `isConfigFileAbsence`'s documentation with its evidence.
 */
object ConfigStoreContract : Contract<ConfigStoreState, ConfigPorts>("ConfigStore") {

    /** The membership a [ConfigStoreState.JOINED] store holds for [clauseId]. Bindings seed exactly this. */
    fun seedConfig(clauseId: String) = config("seed:$clauseId")

    /** The record text a file-backed store holds in [ConfigStoreState.JOINED]: this build's own encoding. */
    fun seedFile(clauseId: String) = encodeConfigFile(seedConfig(clauseId))

    /** A successor's envelope — the revert-build case. */
    const val FOREIGN_FILE = """{"v":2,"payload":{"eventId":"from-a-later-build"}}"""

    /** This build's envelope, whose payload lacks every required field. */
    const val UNUSABLE_FILE = """{"v":1,"payload":{"eventId":"no-cutoff-no-name"}}"""

    private fun config(eventId: String) = EventConfig(
        eventId = eventId,
        name = "Contract $eventId",
        minPhotoDate = CaptureCutoff(CaptureDate("2026-09-01T00:00:00Z")),
        maxPhotoDate = CaptureCeiling(CaptureDate("2026-09-08T00:00:00Z")),
    )

    private fun written(clauseId: String) = config("written:$clauseId")

    private fun ConfigPorts.assertDefers(why: String) =
        assertIs<ConfigRead.Unavailable>(reader.read(), "$why: must defer, never read as a leave")

    override val clauses = clauses {

        clause("INACCESSIBLE_READ_IS_UNAVAILABLE", ConfigStoreState.INACCESSIBLE) { ports ->
            ports.assertDefers("an unreachable store")
        }

        clause("INACCESSIBLE_SAVE_REFUSES", ConfigStoreState.INACCESSIBLE) { ports ->
            val before = ports.source.config.value
            assertFails { ports.store.save(written("INACCESSIBLE_SAVE_REFUSES")) }
            assertEquals(before, ports.source.config.value, "a refused save changes nothing")
            ports.assertDefers("after a refused save")
        }

        clause("INACCESSIBLE_CLEAR_REFUSES", ConfigStoreState.INACCESSIBLE) { ports ->
            val before = ports.source.config.value
            assertFails("a clear that could not reach its store must fail, or the leave half-completes") {
                ports.store.clear()
            }
            assertEquals(before, ports.source.config.value, "a refused clear changes nothing")
            ports.assertDefers("after a refused clear")
        }

        clause("ABSENT_READ_IS_NONE", ConfigStoreState.ABSENT) { ports ->
            assertEquals(ConfigRead.None, ports.reader.read())
            assertNull(ports.source.config.value)
        }

        clause("ABSENT_SAVE_THEN_READ", ConfigStoreState.ABSENT) { ports ->
            val config = written("ABSENT_SAVE_THEN_READ")
            ports.store.save(config)
            assertEquals(ConfigRead.Joined(config), ports.reader.read())
            assertEquals(config, ports.source.config.value)
        }

        clause("ABSENT_CLEAR_IS_A_NOOP", ConfigStoreState.ABSENT) { ports ->
            ports.store.clear()
            assertEquals(ConfigRead.None, ports.reader.read())
            assertNull(ports.source.config.value)
        }

        clause("JOINED_READ_IS_JOINED", ConfigStoreState.JOINED) { ports ->
            assertEquals(ConfigRead.Joined(seedConfig("JOINED_READ_IS_JOINED")), ports.reader.read())
        }

        clause("JOINED_SOURCE_IS_SEEDED_AT_CONSTRUCTION", ConfigStoreState.JOINED) { ports ->
            assertEquals(seedConfig("JOINED_SOURCE_IS_SEEDED_AT_CONSTRUCTION"), ports.source.config.value)
        }

        clause("JOINED_CLEAR_THEN_READ_IS_NONE", ConfigStoreState.JOINED) { ports ->
            ports.store.clear()
            assertEquals(ConfigRead.None, ports.reader.read(), "a completed leave reads as not joined")
            assertNull(ports.source.config.value)
        }

        clause("JOINED_SAVE_REPLACES", ConfigStoreState.JOINED) { ports ->
            val config = written("JOINED_SAVE_REPLACES")
            ports.store.save(config)
            assertEquals(ConfigRead.Joined(config), ports.reader.read())
            assertEquals(config, ports.source.config.value)
        }

        clause("JOINED_SAVE_EQUAL_KEEPS_THE_VALUE", ConfigStoreState.JOINED) { ports ->
            val seed = seedConfig("JOINED_SAVE_EQUAL_KEEPS_THE_VALUE")
            ports.store.save(seed)
            assertEquals(ConfigRead.Joined(seed), ports.reader.read())
            assertEquals(seed, ports.source.config.value)
        }

        clause("FOREIGN_READ_IS_UNAVAILABLE", ConfigStoreState.FOREIGN) { ports ->
            ports.assertDefers("a successor's record")
            assertNull(ports.source.config.value, "the flow cannot show what this build cannot read")
        }

        clause("UNUSABLE_READ_IS_UNAVAILABLE", ConfigStoreState.UNUSABLE) { ports ->
            ports.assertDefers("an undecodable current-version record")
        }

        clause("FILE_UNREADABLE_READ_IS_UNAVAILABLE", ConfigStoreState.FILE_UNREADABLE) { ports ->
            ports.assertDefers("a present record whose read fails")
        }
    }
}
