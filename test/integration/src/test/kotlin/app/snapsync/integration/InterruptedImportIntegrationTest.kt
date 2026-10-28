package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The duplicate-import defect, end to end over the real stack, driven through the control protocol (Bugsink
 * `SNAPSYNC-6`).
 *
 * An import commits in the photo library and is recorded as done in the store at two different moments.
 * If the confirmation never arrives — a process death, or a wait abandoned on its deadline — the row is
 * left `PENDING` while carrying the marker of an asset that genuinely exists. Every idempotency query
 * reads the *state* and ignores the *marker*, so the next pass imports the same photo again; and because
 * a row holds one marker, confirming the second import overwrites the first, dropping the first copy out
 * of the suppression set. That copy is then uploaded back into the event, where every other member
 * imports it as a photo they have never seen.
 *
 * These assert both halves, because only the second is the reported harm: **one** asset in the library, and
 * **no upload job and no object** for the orphan. A fix that stopped the duplicate but left the first copy
 * unsuppressed would still send someone else's photo back into the event.
 *
 * The "next pass" that must adjudicate an inherited row is a **relaunch**: the startup interrupted-import sweep
 * runs at host assembly, which the relaunched app performs when the operating system first brings it to the front.
 */
class InterruptedImportIntegrationTest {

    private val foreignDevice = DEV_F
    private val foreignAsset = "FQ"

    @Test
    @Verifies(spec = "receiving-photos", requirement = "Nothing arrives twice", scenario = "Killed while saving")
    fun an_interrupted_import_is_not_repeated_on_the_next_pass() = behaviour {
        // Precondition, in the step: the asset really was created, and the import really is unconfirmed. Without both,
        // this test would pass for the wrong reason.
        val abandoned = given {
            joinedEvent()
            abandonedImport()
        }

        // The next pass — a relaunch, then a foreground and a download wake. It must adjudicate the row, not import it
        // again.
        whenever {
            host.relaunch()
            os.foreground()
            downloadSession.finish()
            advance(STATUS_POLL)
        }
        then {
            screen<Layer.Joined> { it.received() == SETTLED } // the row settles against the asset that exists
            never<WorldEvent.LibraryAssetCreated>()
            // exactly one asset created for this photo — no second copy
            settled { it.photos() == abandoned.photos + 1 }
        }
    }

    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun the_first_copy_is_never_uploaded_back_into_the_event() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            abandonedImport()
            relaunchedAndSettled()
        }

        // The reported harm. `SNAPSYNC-6` uploaded key `BB4F7765-…-primary.heic` — the orphaned first copy —
        // and every other member imported it as a photo they had never seen.
        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>() // the first copy stays suppressed and creates no upload job
            never<WorldEvent.BytesStored>() // and nothing of it lands on the backend
        }
    }

    /**
     * Staged bytes are the **only** source for a retry — a resource already recorded as staged is never
     * re-downloaded. So releasing them before a row is settled does not cost a retry; it loses the photo
     * permanently and silently. These pin the ordering from both sides.
     */
    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos do not take up space twice",
        scenario = "Space is released after saving",
    )
    fun staged_bytes_survive_an_unconfirmed_import_and_are_released_once_it_settles() = behaviour {
        val abandoned = given {
            joinedEvent()
            abandonedImport()
        }
        assertTrue(abandoned.staged > abandoned.base, "the transfer staged bytes")

        // A full trigger cycle against the unconfirmed row leaves them where they are.
        whenever {
            os.foreground()
            heartbeat()
        }
        then {
            event<WorldEvent.CompletionReleased> { !it.again }
            settled { it.storage() >= abandoned.staged } // unconfirmed → the bytes stay; the retry needs them
        }

        // The startup sweep adjudicates → present → settles the row.
        whenever { relaunched() }
        then {
            screen<Layer.Joined> { it.received() == SETTLED }
            settled { it.storage() <= abandoned.base } // confirmed → the bytes are redundant and released
        }
    }

    @Test
    @Verifies(spec = "delivery", requirement = "Download problems delay photos, never corrupt or lose them")
    fun a_failed_import_keeps_its_bytes_for_the_retry() = behaviour {
        // The first import fails, and the pass its staging requested ends with it — so the retry below is a pass of its
        // own, never one the staging's pass is joined by. What the bytes are for is: every retry — here the next wake's
        // tail, whose first unit is the import drain — imports off those same bytes, and would fail had the failure
        // taken them (DownloadControllerTest pins the failure keeping them, step by step).
        val before = given {
            joinedEvent()
            downloadStarted(foreignDevice, foreignAsset).also {
                step("the first import fails", act = {
                    library.failNextImport()
                    downloadSession.finish()
                }, until = {
                    never<WorldEvent.LibraryAssetCreated>()
                    settled { true }
                })
            }
        }

        whenever {
            heartbeat()
            advance(STATUS_POLL)
        }
        then {
            // the failed attempt's id is spent, so the photo arrives under the library's next one
            val photo = imported(foreignAsset, foreignDevice)
            times(1) { event<WorldEvent.LibraryAssetCreated> { it.asset.startsWith(photo) } }
            screen<Layer.Joined> { it.received() == SETTLED }
            settled { it.photos() == before.photos() + 1 } // the photo still arrives
        }
    }

    /** The full leave/switch shape: the row survives, adjudicates correctly afterwards, and never echoes. */
    @Test
    @Verifies(spec = "receiving-photos", requirement = "Nothing arrives twice")
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun after_a_leave_the_interrupted_import_still_settles_without_a_duplicate() = behaviour {
        val abandoned = given {
            extensionUploadsOnly()
            joinedEvent()
            abandonedImport().also {
                step("left", act = { tap(UiIntent.LeaveEvent) }, until = {
                    screen<Layer.CreateEvent>()
                    settled { true }
                })
                createdEvent(name = "Second Event")
                step("joined the second event", act = { confirmJoin() }, until = {
                    screen<Layer.Joined>()
                    settled { true }
                })
            }
        }
        assertTrue(abandoned.staged > abandoned.base, "precondition: the transfer staged bytes")

        // The sweep settled the inherited row: its now-redundant bytes are released.
        whenever {
            host.relaunch()
            os.foreground()
        }
        then {
            never<WorldEvent.LibraryAssetCreated>()
            settled { it.storage() <= abandoned.base }
        }

        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>() // and nothing echoed into the new event either
            never<WorldEvent.BytesStored>()
            settled { it.photos() == abandoned.photos + 1 } // still exactly one asset created for this photo
        }
    }

    /**
     * Download progress has to *settle*. An unconfirmed row counts toward the denominator but not the
     * numerator, so a guard that merely stopped the duplicate — without settling the row — would peg the
     * status line below 100% forever, which reads as a broken app and would ship silently.
     */
    @Test
    @Verifies(spec = "sync-status", requirement = "The joined screen counts what was shared and received")
    fun download_progress_settles_after_an_interrupted_import() = behaviour {
        given {
            joinedEvent()
            abandonedImport() // unconfirmed, so progress is genuinely short
        }

        whenever { relaunched() }
        then { screen<Layer.Joined> { it.received() == SETTLED } } // imported reaches total — not left stuck
    }

    /**
     * The whole change, end to end: a transaction held OPEN while a full trigger cycle runs against it.
     *
     * This is the state the field defect was adjudicated in — the library answering *absent* about an
     * asset whose change block has committed nothing yet. Every trigger must complete without waiting on
     * it, nothing may act on that *absent* answer, and when the import finally lands there must be exactly
     * ONE asset, and it must never echo back into the event.
     *
     * Judged on how many assets EXIST: creating the second asset is the harm.
     */
    @Test
    @Verifies(spec = "receiving-photos", requirement = "Nothing arrives twice")
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun a_live_transaction_survives_a_full_trigger_cycle_without_a_duplicate() = behaviour {
        val before = given {
            extensionUploadsOnly()
            joinedEvent()
            downloadStarted(foreignDevice, foreignAsset).also {
                // Park BEFORE the commit: the library will answer *absent* about a transaction that is alive.
                step(
                    "its bytes staged, the import parked before the library commits it",
                    act = {
                        library.suspendNextImport()
                        downloadSession.finish()
                    },
                    until = {
                        event<WorldEvent.LibraryImportParked>()
                        never<WorldEvent.LibraryAssetCreated>() // the commit has not landed
                        settled { true }
                    },
                )
            }
        }

        // A full cycle of triggers against the live transaction. None may block, and none may act on the library's honest
        // "absent" about it.
        whenever {
            os.foreground()
            heartbeat()
        }
        then {
            event<WorldEvent.CompletionReleased> { !it.again }
            // no second asset was created while the first transaction was still open
            never<WorldEvent.LibraryAssetCreated>()
            settled { it.photos() == before.photos() }
        }

        // The commit finally lands and reports.
        whenever {
            library.resumeImport(succeeded = true)
            advance(STATUS_POLL)
        }
        then {
            times(1) { event<WorldEvent.LibraryAssetCreated>() }
            screen<Layer.Joined> { it.received() == SETTLED } // the row settles
            settled { it.photos() == before.photos() + 1 } // exactly one asset for this photo
        }

        // The REPORTED harm, judged rather than inferred: run the real upload cycle and require that the downloaded
        // photo produces no upload job. A suppression handle that exists but is not consulted would still send someone
        // else's photo back into their event.
        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>() // the downloaded photo creates no upload job
            never<WorldEvent.BytesStored>()
        }
    }

    /**
     * THE ORDERING GUARANTEE, asserted directly because nothing enforces it at compile time: adjudication is no
     * longer any trigger's business, so the only thing that settles a
     * row a dead process left behind is the composition's own startup sweep. If a future edit drops that
     * call, every interrupted import stalls forever and no other test notices — the rows simply sit there,
     * correct and unimported.
     */
    @Test
    @Verifies(spec = "receiving-photos", requirement = "Nothing arrives twice")
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun the_composition_startup_sweep_settles_what_a_dead_process_left() = behaviour {
        val abandoned = given {
            extensionUploadsOnly()
            joinedEvent()
            abandonedImport()
        }
        // Exactly what a killed process leaves: an asset in the library, a row that does not know it. No trigger will
        // settle this — that is the point.
        whenever {
            os.foreground()
            heartbeat()
            advance(STATUS_POLL)
        }
        then {
            event<WorldEvent.CompletionReleased> { !it.again }
            // a full trigger cycle leaves it alone — triggers do not adjudicate any more
            neverScreen<Layer.Joined> { it.received() == SETTLED }
            settled { true }
        }

        // Host assembly is what runs the sweep: the relaunched app assembles its host when first brought to the front.
        whenever { relaunched() }
        then {
            screen<Layer.Joined> { it.received() == SETTLED } // the startup sweep settled it
            never<WorldEvent.LibraryAssetCreated>()
            settled { it.photos() == abandoned.photos + 1 } // against the asset that exists — no second copy
        }
        whenever { cycle() }
        then { never<WorldEvent.UploadJobCreated>() } // and the FIRST copy is suppressed
    }

    /**
     * D3's SECOND ordering requirement, and the one whose failure would be systematic rather than racy.
     *
     * Under a partial grant the presence source answers from the held selection snapshot, which is `null`
     * until the observer's first emission. A sweep that ran before it would answer *unknown* for every
     * inherited row — and with one sweep per process and no re-arm, that row waits for the NEXT LAUNCH.
     * On a relaunch driven by a `URLSession` staging callback, which may never foreground, the same
     * ordering could repeat every launch and the photo would never arrive.
     *
     * So the sweep waits for a snapshot rather than asking a question the source cannot answer yet. The
     * settlement is observed through the staging directory: a settled row releases its bytes.
     */
    @Test
    @Verifies(
        spec = "receiving-photos",
        requirement = "Other members' photos arrive in the photo library automatically",
        scenario = "Receiving under limited access",
    )
    fun under_a_partial_grant_the_sweep_waits_for_the_selection_snapshot() = behaviour {
        given {
            joinedEvent()
            step(
                "a member's photo on its way, its import to be abandoned once the library has created it",
                act = {
                    publicBackend.foreignDevice(foreignDevice, listOf(foreignAsset))
                    library.suspendNextImport(afterCommit = true)
                    os.foreground()
                },
                until = {
                    event<WorldEvent.DownloadStarted>()
                    settled { true }
                },
            )
            // The import fires from the staged-resource callback, and parks there: the asset exists, the row does not know.
            step("its bytes staged", act = { downloadSession.finish() }, until = { settled { true } })
            step(
                "the grant narrowed",
                act = { library.permission(PhotoGrant.LIMITED) },
                until = { settled { true } },
            )
            // No emission yet, so the relaunched app's sweep has no snapshot to ask: one that ran anyway would read
            // UNKNOWN and settle nothing — permanently, because nothing re-arms it.
            step(
                "the app relaunched and opened",
                act = {
                    host.relaunch()
                    os.foreground()
                },
                until = { settled { true } },
            )
        }

        // The observer emits, and the created asset is in the member's hand-picked selection; they look again.
        whenever {
            library.changeSelection(listOf("imported-$foreignDevice-$foreignAsset"))
            os.foreground()
        }
        then { screen<Layer.Joined> { it.received() == DirectionCount.Progress(1, 1) } }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * A foreign asset driven all the way to staged, with its import abandoned after the library commit — the state a
     * killed process leaves: the asset exists, the row does not know it. The import stays parked inside the library, so
     * the transaction is genuinely open while the rest of the test runs against it; the park is awaited, and the joined
     * screen shows the photo genuinely short of received.
     */
    private suspend fun Given.abandonedImport(): Abandoned {
        val before = downloadStarted(foreignDevice, foreignAsset)
        lateinit var parked: Seen<WorldEvent.Settled>
        // The import fires from the staged-resource callback, so THAT is what parks.
        step(
            "its bytes staged, its import abandoned once the library created the asset",
            act = {
                library.suspendNextImport(afterCommit = true)
                downloadSession.finish()
                advance(STATUS_POLL)
            },
            until = {
                event<WorldEvent.LibraryImportParked>()
                event<WorldEvent.LibraryAssetCreated> { it.asset == imported(foreignAsset, foreignDevice) }
                screen<Layer.Joined> { it.received() == SHORT } // but the import was never confirmed
                parked = settled { true }
            },
        )
        assertEquals(before.photos() + 1, parked.value.photos(), "the asset was created")
        return Abandoned(photos = before.photos(), base = before.storage(), staged = parked.value.storage())
    }

    /**
     * What an abandoned import left: the library's [photos] before it, the storage the app took before its bytes
     * arrived ([base]) and with them staged ([staged]).
     */
    private class Abandoned(val photos: Int, val base: Long, val staged: Long)

    /** Process death and a cold launch, then the operating system brings the new app to the front — which assembles it. */
    private suspend fun Acts.relaunched() {
        host.relaunch()
        os.foreground()
    }

    /** The app relaunched and in front, the row the dead process left settled by its startup sweep. */
    private suspend fun Given.relaunchedAndSettled() = step("relaunched", act = { relaunched() }, until = {
        screen<Layer.Joined> { it.received() == SETTLED }
        settled { true }
    })

    /** What the joined screen counts as received. */
    private fun Layer.Joined.received() = counts?.received

    private fun WorldEvent.Settled.photos(): Int = state.libraryPhotos.orEmpty().size

    /** The storage the app takes, as the device's storage page shows it. */
    private fun WorldEvent.Settled.storage(): Long = checkNotNull(state.appStorageBytes)

    private companion object {
        /** The one photo planned, not yet imported. */
        val SHORT = DirectionCount.Progress(0, 1)

        /** The one photo planned, imported. */
        val SETTLED = DirectionCount.Progress(1, 1)

        /** Longer than the screen's status poll, on mocked time. */
        val STATUS_POLL = 5.seconds
    }
}
