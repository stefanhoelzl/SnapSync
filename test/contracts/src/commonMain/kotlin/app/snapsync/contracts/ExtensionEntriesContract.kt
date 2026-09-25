package app.snapsync.contracts

import app.snapsync.ports.CycleResult
import app.snapsync.ports.ExtensionEntries
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The states the process an [ExtensionEntries] drives can be found in, as far as a clause cares. */
enum class ExtensionEntriesState {
    /** No membership at all. */
    UNJOINED,

    /** Joined, full photo access, and one in-window photo taken that nothing has uploaded yet. */
    JOINED_WITH_A_NEW_PHOTO,

    /** Joined, full photo access, and nothing left to upload. */
    JOINED_WITH_NOTHING_NEW,
}

/**
 * What a clause reads to see [ExtensionEntries.process]'s outcome beyond its result — a snapshot of the system the
 * binding built, never a record of calls (`docs/architecture.md`).
 */
interface ExtensionEntriesObservations {
    /** How many uploads have been handed to the transfer mechanism. */
    fun uploadsStarted(): Int
}

/** What a clause is handed: the port under contract and the handle it observes outcomes through. */
class ExtensionEntriesSubject(val entries: ExtensionEntries, val observe: ExtensionEntriesObservations)

/**
 * What the upload extension's inbound port promises (`docs/architecture.md` — this list IS the specification
 * of the port's obligations; `docs/architecture.md`, "OS entry points cross an inbound port").
 *
 * [ExtensionEntries.process] answers the operating system with how the cycle ended, and the extension shell turns
 * that into the platform's result — so a cycle that did work must say "call me again" while it waits on transfers,
 * and one with nothing to do must let the system rest (capability `background-upload`).
 *
 * [ExtensionEntries.onTerminate] has **no clause**, deliberately: it records a line and changes nothing a clause
 * could observe, and a clause that asserted the line would be the call transcript this mechanism refuses.
 *
 * One implementation, no double — for the reason [PlatformEntriesContract] states.
 */
object ExtensionEntriesContract : Contract<ExtensionEntriesState, ExtensionEntriesSubject>("ExtensionEntries") {

    override val clauses = clauses {

        clause("PROCESS_STARTS_A_NEW_UPLOAD_AND_ASKS_AGAIN", ExtensionEntriesState.JOINED_WITH_A_NEW_PHOTO) { subject ->
            val result = subject.entries.process()
            assertTrue(subject.observe.uploadsStarted() > 0, "the new photo's upload is started")
            assertEquals(CycleResult.PROCESSING, result, "and the system is asked to call again while it is in flight")
        }

        clause("PROCESS_WITH_NOTHING_NEW_COMPLETES", ExtensionEntriesState.JOINED_WITH_NOTHING_NEW) { subject ->
            assertEquals(CycleResult.COMPLETED, subject.entries.process())
            assertEquals(0, subject.observe.uploadsStarted(), "nothing is uploaded")
        }

        clause("PROCESS_WITHOUT_A_MEMBERSHIP_DECLINES", ExtensionEntriesState.UNJOINED) { subject ->
            assertEquals(CycleResult.SKIPPED, subject.entries.process())
            assertEquals(0, subject.observe.uploadsStarted(), "nothing is uploaded")
        }
    }
}
