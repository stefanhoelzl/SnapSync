package app.snapsync.contracts

import app.snapsync.model.AssetId
import app.snapsync.model.ChangeOutcome
import app.snapsync.model.ResourceRole
import app.snapsync.model.TransferNetwork
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJob
import app.snapsync.model.UploadJobSet
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadSourceKind
import app.snapsync.model.UploadTarget
import app.snapsync.model.destinationPathOf
import app.snapsync.model.uploadKey
import app.snapsync.ports.Completion
import app.snapsync.ports.Upload
import app.snapsync.ports.UploadHandlers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the platform's upload jobs stand when a clause starts. */
enum class UploadState {
    /** Nothing in flight: every creation below the limit. */
    IDLE,

    /** As many transfers in flight, to routes that never answer, as the platform takes at once. */
    AT_CAP,

    /*
     * The PhotoKit queue's own vocabulary: a transfer the OS has ALREADY settled and now presents, prepared by the
     * binding before the clause — on a device, across operating-system calls, because a job the upload extension creates
     * is uploaded only after its `process()` call returns (measured, SE2, iOS 26.6). The prepared transfer is the
     * clause's own, to route [UploadContract.preparedRoute]. The URLSession uploader reports a transfer's end at once
     * and offers no free retry, so its bindings declare all three unreachable.
     */

    /** A transfer the destination accepted, presented as succeeded. */
    PRESENTED_SUCCEEDED,

    /** A transfer the destination refused once, presented for its single free retry. */
    PRESENTED_REFUSED_ONCE,

    /** A transfer refused again after its free retry to the identical destination, presented as terminal. */
    PRESENTED_RETRY_SPENT,

    /**
     * Nothing in flight, and the device on a restricted network (capability `mobile-data`) — mobile data, a metered
     * Wi-Fi, a hotspot, Low Data Mode — which the binding can lift ([UploadUnderTest.liftRestriction]).
     */
    RESTRICTED_NETWORK,

    /*
     * What each platform's uploads take, and how it tells a transfer's end — facts of the platform, so each binding
     * reaches only the states its platform is.
     */

    /** Nothing in flight, on a platform that takes the library's own resource handles and also takes a file. */
    TAKES_RESOURCES_AND_FILES,

    /** Nothing in flight, on a platform that takes only a file of the photo's bytes. */
    TAKES_FILES,

    /** On a platform that takes only the library's own resources — so an encrypted event's upload is sealed elsewhere. */
    TAKES_RESOURCES_ONLY,

    /** Nothing in flight, on a platform that reports each transfer's end as it happens and offers no free retry. */
    REPORTS_AS_IT_HAPPENS,

    /** On a platform that holds each settled transfer until asked, and is owed an acknowledgement for every one. */
    PRESENTS_WHEN_ASKED,

    /**
     * The app relaunched by the operating system in the background to deliver its session's events: a transfer the
     * binding prepared — to the clause's [UploadContract.relaunchedTag] — ended while the app was not running, and the
     * relaunch is held for the clause to hand over ([UploadUnderTest.relaunch]). A device's, recorded across the two
     * processes: no CI host's session outlives its app.
     */
    RELAUNCHED_WITH_EVENTS,
}

/**
 * The port as a clause receives it (`docs/architecture.md`). [upload] is the port; the rest is what the clause needs that
 * the port does not answer.
 *
 * - [base] is the fixture's address; a route is `base + TransferFixture.path(...)`.
 * - [usable] builds a source this platform CAN upload for a key — a photo-library resource handle, or a file of the
 *   photo's bytes, whichever it [accepts][Upload.accepts]; [unusable] one it cannot.
 * - [ended] is every job the port reported through its handlers (the binding listened): how a platform that reports
 *   as it happens tells its owner a transfer ended. A platform that presents terminal jobs when asked answers through
 *   [Upload.jobs] instead; a clause reads both.
 * - [objects] is what landed on the fixture.
 */
class UploadUnderTest(
    val upload: Upload,
    val base: String,
    val usable: suspend (key: String) -> UploadSource,
    val unusable: (key: String) -> UploadSource,
    val ended: () -> List<UploadJob>,
    val objects: FixtureObjects,
    /** Puts the device back on an unrestricted network; required of a binding that reaches [UploadState.RESTRICTED_NETWORK]. */
    val liftRestriction: (suspend () -> Unit)? = null,
    /** A file of the photo's bytes for a key; required of a binding that reaches [UploadState.TAKES_RESOURCES_AND_FILES]. */
    val fileSource: (suspend (key: String) -> UploadSource)? = null,
    /** The relaunch the operating system made; required of a binding that reaches [UploadState.RELAUNCHED_WITH_EVENTS]. */
    val relaunch: Relaunch? = null,
)

/**
 * A relaunch the operating system made to deliver a session's events, held by the binding — which listened to the port
 * — until the clause hands it over.
 */
class Relaunch(
    /** Hands the port the relaunch, with the system's completion handler, as the app's delegate does. */
    val deliver: () -> Unit,
    /** Every completion the port handed its owner for a relaunch, in order. */
    val handedOver: () -> List<Completion>,
    /** How many drain reports the port gave its owner. */
    val drains: () -> Int,
)

/**
 * What every [Upload] owes the upload services, whichever platform it is (`docs/architecture.md` — this list IS the
 * specification). One contract for both iOS uploaders, because the port is one interface. What a job MEANS for the
 * ledger is the services' (`UploadTransferService`, tested over doubles); what is contracted here is that the port
 * creates what it is asked and refuses what it cannot, reports how a transfer ended truthfully and under the destination
 * it was created with, and presents, retries and acknowledges what it holds.
 *
 * A platform learns a transfer's end one of two ways: the URLSession uploader the moment its delegate is called
 * ([UploadUnderTest.ended]), the PhotoKit queue when it presents a terminal job to [Upload.jobs]. Every wait below reads
 * both, as the services do.
 */
object UploadContract : Contract<UploadState, UploadUnderTest>("Upload") {

    /** The ledger key a clause uploads under (`<assetId>-<role>.<ext>`), distinct per clause and per [n]. */
    fun key(clauseId: String, n: Int = 1): String =
        uploadKey(AssetId("contract-${clauseId.lowercase()}-$n"), ResourceRole.PRIMARY, "IMG_0001.JPG")

    /** The route a clause's transfer [n] goes to. */
    fun path(clauseId: String, answer: FixtureAnswer, n: Int = 1): String =
        TransferFixture.path(name, clauseId, "upload-$n", answer)

    /** The states whose transfer the binding prepares and the OS settles before the clause. */
    val PRESENTED: Set<UploadState> = setOf(
        UploadState.PRESENTED_SUCCEEDED,
        UploadState.PRESENTED_REFUSED_ONCE,
        UploadState.PRESENTED_RETRY_SPENT,
    )

    /** The tag a relaunched state's prepared transfer was created under, which its end is reported with. */
    fun relaunchedTag(clauseId: String): String = key(clauseId)

    /** The route a presented state's prepared transfer goes to: accepted for a success, refused otherwise. */
    fun preparedRoute(clauseId: String, state: UploadState): String =
        path(clauseId, if (state == UploadState.PRESENTED_SUCCEEDED) ACCEPT else REJECT)

    /** The type every clause's transfer declares. */
    const val CONTENT_TYPE = "image/jpeg"

    private val ACCEPT = FixtureAnswer.Respond(200)
    private val REJECT = FixtureAnswer.Respond(500)

    private fun UploadUnderTest.target(route: String, network: TransferNetwork = TransferNetwork.ANY) =
        UploadTarget(base + route, mapOf("Content-Type" to CONTENT_TYPE), network)

    private fun UploadUnderTest.destination(route: String) = destinationPathOf(base + route)

    /** Every job the port has told of for [route]: reported through its handlers, or presented in either set. */
    private suspend fun UploadUnderTest.jobsAt(route: String): List<UploadJob> {
        val path = destination(route)
        return (ended() + upload.jobs(UploadJobSet.TERMINAL) + upload.jobs(UploadJobSet.RETRY_OFFERED))
            .filter { it.destinationPath == path }
    }

    /** Whether this live job is [clauseId]'s transfer to [route]: by its destination, or its tag where that is all it keeps. */
    private fun UploadJob.matches(subject: UploadUnderTest, route: String, clauseId: String) =
        destinationPath == subject.destination(route) || tag == key(clauseId)

    private suspend fun UploadUnderTest.presented(set: UploadJobSet, route: String): UploadJob? =
        upload.jobs(set).firstOrNull { it.destinationPath == destination(route) }

    override val clauses = clauses {

        clause(
            "CREATE_UNUSABLE_SOURCE",
            UploadState.IDLE,
            covers = cells {
                on<Upload>().answers(Upload::create).with(UploadCreateOutcome.FAILED)
            },
        ) { subject ->
            val id = "CREATE_UNUSABLE_SOURCE"
            val route = path(id, ACCEPT)
            assertEquals(
                UploadCreateOutcome.FAILED,
                subject.upload.create(subject.unusable(key(id)), subject.target(route), key(id)),
                "a source the platform cannot upload is not a job; the caller must not record REQUESTED for it",
            )
            transferSettle()
            assertNull(subject.objects.landed(route), "nothing was sent")
        }

        clause(
            "CREATE_LANDS_AND_ENDS_SUCCEEDED",
            UploadState.IDLE,
            covers = cells {
                on<Upload> {
                    answers(Upload::create).with(UploadCreateOutcome.CREATED)
                    answers(Upload::jobs).returns()
                    answers(Upload::acknowledge).with(ChangeOutcome.Applied::class)
                }
            },
        ) { subject ->
            val id = "CREATE_LANDS_AND_ENDS_SUCCEEDED"
            val route = path(id, ACCEPT)
            assertEquals(
                UploadCreateOutcome.CREATED,
                subject.upload.create(subject.usable(key(id)), subject.target(route), key(id)),
            )
            awaitWithin {
                subject.objects.landed(route) != null &&
                    subject.jobsAt(route).any { it.state == UploadJobState.SUCCEEDED }
            }
            val ended = subject.jobsAt(route).first { it.state == UploadJobState.SUCCEEDED }
            assertEquals(subject.destination(route), ended.destinationPath, "a job is told of under its destination")
            assertTrue(ended.tag == null || ended.tag == key(id), "and, where the platform keeps one, its tag")
            assertEquals(ChangeOutcome.Applied, subject.upload.acknowledge(ended), "a presented end is acknowledged")
        }

        clause(
            "A_TRANSFER_HELD_TO_UNRESTRICTED_NETWORKS_WAITS_FOR_ONE",
            UploadState.RESTRICTED_NETWORK,
            covers = cells {
                on<Upload> {
                    answers(Upload::create).with(UploadCreateOutcome.CREATED)
                    answers(Upload::jobs).returns()
                }
            },
        ) { subject ->
            val id = "A_TRANSFER_HELD_TO_UNRESTRICTED_NETWORKS_WAITS_FOR_ONE"
            val lift = assertNotNull(subject.liftRestriction, "a binding that reaches a restricted network can lift it")
            val route = path(id, ACCEPT)
            assertEquals(
                UploadCreateOutcome.CREATED,
                subject.upload.create(
                    subject.usable(key(id)),
                    subject.target(route, TransferNetwork.UNRESTRICTED_ONLY),
                    key(id),
                ),
                "a held transfer is still a job: the platform keeps it until the network allows it",
            )
            heldSettle()
            assertNull(
                subject.objects.landed(route),
                "a transfer held to unrestricted networks sends nothing on a restricted one",
            )
            assertTrue(
                subject.jobsAt(
                    route,
                ).none { it.state == UploadJobState.SUCCEEDED || it.state == UploadJobState.FAILED },
            )
            lift()
            awaitWithin {
                subject.objects.landed(route) != null &&
                    subject.jobsAt(route).any { it.state == UploadJobState.SUCCEEDED }
            }
            subject.jobsAt(
                route,
            ).filter { it.state == UploadJobState.SUCCEEDED }.forEach { subject.upload.acknowledge(it) }
        }

        clause(
            "CREATE_KEEPS_CONTENT_TYPE",
            UploadState.IDLE,
            covers = cells {
                on<Upload>().answers(Upload::create).with(UploadCreateOutcome.CREATED)
            },
        ) { subject ->
            val id = "CREATE_KEEPS_CONTENT_TYPE"
            val route = path(id, ACCEPT)
            assertEquals(
                UploadCreateOutcome.CREATED,
                subject.upload.create(subject.usable(key(id)), subject.target(route), key(id)),
            )
            awaitWithin { subject.objects.landed(route) != null }
            assertEquals(
                CONTENT_TYPE,
                subject.objects.landed(route)?.contentType,
                "the object is stored under the type it was created with, for the rest of its life",
            )
        }

        clause(
            "REFUSED_NEVER_ENDS_SUCCEEDED",
            UploadState.IDLE,
            covers = cells {
                on<Upload> {
                    answers(Upload::create).with(UploadCreateOutcome.CREATED)
                    answers(Upload::jobs).returns()
                }
            },
        ) { subject ->
            val id = "REFUSED_NEVER_ENDS_SUCCEEDED"
            val route = path(id, REJECT)
            assertEquals(
                UploadCreateOutcome.CREATED,
                subject.upload.create(subject.usable(key(id)), subject.target(route), key(id)),
            )
            awaitWithin { subject.jobsAt(route).isNotEmpty() }
            transferSettle()
            assertTrue(
                subject.jobsAt(route).none { it.state == UploadJobState.SUCCEEDED },
                "a destination that refused the bytes is not a completed upload",
            )
        }

        clause(
            "AT_CAP_DEFERS",
            UploadState.AT_CAP,
            covers = cells {
                on<Upload>().answers(Upload::create).with(UploadCreateOutcome.LIMIT_EXCEEDED)
            },
        ) { subject ->
            val id = "AT_CAP_DEFERS"
            val route = path(id, ACCEPT, n = 0)
            assertEquals(
                UploadCreateOutcome.LIMIT_EXCEEDED,
                subject.upload.create(subject.usable(key(id, n = 0)), subject.target(route), key(id, n = 0)),
                "at the in-flight limit the platform defers rather than starting another transfer",
            )
            transferSettle()
            assertNull(subject.objects.landed(route), "a deferred creation sends nothing")
        }

        clause(
            "TAKES_RESOURCES_AND_FILES_LANDS_BOTH",
            UploadState.TAKES_RESOURCES_AND_FILES,
            covers = cells {
                on<Upload> {
                    answers(Upload::accepts).with(UploadSourceKind.RESOURCE)
                    answers(Upload::acceptsFiles).with(true)
                    answers(Upload::create).with(UploadCreateOutcome.CREATED)
                }
            },
        ) { subject ->
            val id = "TAKES_RESOURCES_AND_FILES_LANDS_BOTH"
            val file = assertNotNull(subject.fileSource, "a binding that reaches this state can make a file source")
            assertEquals(UploadSourceKind.RESOURCE, subject.upload.accepts, "the library's own handle is what it takes")
            assertTrue(
                subject.upload.acceptsFiles,
                "and a file too, so an encrypted event's upload can be sealed first",
            )
            val resource = path(id, ACCEPT, n = 1)
            val sealed = path(id, ACCEPT, n = 2)
            assertIs<UploadSource.Resource>(subject.usable(key(id, n = 1)), "what it takes is a resource")
            assertEquals(
                UploadCreateOutcome.CREATED,
                subject.upload.create(subject.usable(key(id, n = 1)), subject.target(resource), key(id, n = 1)),
            )
            assertEquals(
                UploadCreateOutcome.CREATED,
                subject.upload.create(file(key(id, n = 2)), subject.target(sealed), key(id, n = 2)),
            )
            awaitWithin { subject.objects.landed(resource) != null && subject.objects.landed(sealed) != null }
        }

        clause(
            "TAKES_FILES_LANDS_A_FILE",
            UploadState.TAKES_FILES,
            covers = cells {
                on<Upload> {
                    answers(Upload::accepts).with(UploadSourceKind.FILE)
                    answers(Upload::acceptsFiles).with(true)
                    answers(Upload::create).with(UploadCreateOutcome.CREATED)
                }
            },
        ) { subject ->
            val id = "TAKES_FILES_LANDS_A_FILE"
            assertEquals(
                UploadSourceKind.FILE,
                subject.upload.accepts,
                "the photo's bytes are exported to a file first",
            )
            assertTrue(subject.upload.acceptsFiles)
            val source = subject.usable(key(id))
            assertIs<UploadSource.File>(source, "what it takes is a file")
            val route = path(id, ACCEPT)
            assertEquals(UploadCreateOutcome.CREATED, subject.upload.create(source, subject.target(route), key(id)))
            awaitWithin { subject.objects.landed(route) != null }
        }

        clause(
            "TAKES_RESOURCES_ONLY_IS_NO_SEALED_UPLOAD",
            UploadState.TAKES_RESOURCES_ONLY,
            covers = cells {
                on<Upload> {
                    answers(Upload::accepts).with(UploadSourceKind.RESOURCE)
                    answers(Upload::acceptsFiles).with(false)
                }
            },
        ) { subject ->
            assertEquals(UploadSourceKind.RESOURCE, subject.upload.accepts)
            assertFalse(
                subject.upload.acceptsFiles,
                "only the library's own bytes, so the edge seals an encrypted event's",
            )
        }

        clause(
            "PRESENTS_WHEN_ASKED_REFUSES_A_JOB_IT_NEVER_PRESENTED",
            UploadState.PRESENTS_WHEN_ASKED,
            covers = cells {
                on<Upload>().answers(Upload::acknowledge).with(ChangeOutcome.Refused::class)
            },
        ) { subject ->
            val id = "PRESENTS_WHEN_ASKED_REFUSES_A_JOB_IT_NEVER_PRESENTED"
            val never = UploadJob(
                null,
                key(id),
                subject.destination(path(id, ACCEPT)),
                CONTENT_TYPE,
                UploadJobState.SUCCEEDED,
                null,
                null,
            )
            assertIs<ChangeOutcome.Refused>(
                subject.upload.acknowledge(never),
                "only a job the platform presented is owed an acknowledgement; another is refused, not taken as dealt with",
            )
        }

        clause(
            "RELAUNCHED_EVENTS_ARE_HANDED_OVER_THEN_DRAINED",
            UploadState.RELAUNCHED_WITH_EVENTS,
            covers = cells {
                on<Upload> {
                    calls(UploadHandlers::onBackgroundEvents, Completion::class)
                    calls(UploadHandlers::onFinished, UploadJob::class)
                    calls(UploadHandlers::onEventsDrained)
                    handle<Completion>().answers(Completion::complete).returns()
                }
            },
        ) { subject ->
            val id = "RELAUNCHED_EVENTS_ARE_HANDED_OVER_THEN_DRAINED"
            val relaunch = assertNotNull(subject.relaunch, "a binding that reaches this state holds the relaunch")
            relaunch.deliver()
            awaitWithin { relaunch.drains() > 0 }
            val completion = assertNotNull(
                relaunch.handedOver().singleOrNull(),
                "the relaunch's completion is handed over once: ${relaunch.handedOver().size}",
            )
            val ended = subject.ended().filter { it.tag == relaunchedTag(id) }
            assertEquals(
                1,
                ended.size,
                "the transfer that ended while the app was gone is reported once, before the drain",
            )
            assertNotEquals(
                UploadJobState.SUCCEEDED,
                ended.single().state,
                "a transfer the server refused is not a success",
            )
            completion.complete()
            completion.complete()
        }

        clause(
            "REPORTS_AS_IT_HAPPENS_AN_END_REACHES_THE_HANDLER",
            UploadState.REPORTS_AS_IT_HAPPENS,
            covers = cells {
                on<Upload> {
                    answers(Upload::listen).returns()
                    answers(Upload::create).with(UploadCreateOutcome.CREATED)
                    calls(UploadHandlers::onFinished, UploadJob::class)
                }
            },
        ) { subject ->
            val id = "REPORTS_AS_IT_HAPPENS_AN_END_REACHES_THE_HANDLER"
            val route = path(id, ACCEPT)
            assertEquals(
                UploadCreateOutcome.CREATED,
                subject.upload.create(subject.usable(key(id)), subject.target(route), key(id)),
            )
            awaitWithin {
                subject.ended().any { it.destinationPath == subject.destination(route) && it.state == UploadJobState.SUCCEEDED }
            }
            assertTrue(
                subject.upload.jobs(UploadJobSet.TERMINAL).isEmpty(),
                "told as it happened, so nothing is presented",
            )
        }

        clause(
            "REPORTS_AS_IT_HAPPENS_A_LIVE_TRANSFER_IS_CANCELLED_AND_NEVER_RETRIED",
            UploadState.REPORTS_AS_IT_HAPPENS,
            covers = cells {
                on<Upload> {
                    answers(Upload::create).with(UploadCreateOutcome.CREATED)
                    answers(Upload::jobs).returns()
                    answers(Upload::retry).with(ChangeOutcome.Refused::class)
                    answers(Upload::cancel).with(ChangeOutcome.Applied::class)
                    answers(Upload::cancel).with(ChangeOutcome.Refused::class)
                }
            },
        ) { subject ->
            val id = "REPORTS_AS_IT_HAPPENS_A_LIVE_TRANSFER_IS_CANCELLED_AND_NEVER_RETRIED"
            val route = path(id, FixtureAnswer.Hold)
            assertEquals(
                UploadCreateOutcome.CREATED,
                subject.upload.create(subject.usable(key(id)), subject.target(route), key(id)),
            )
            awaitWithin { subject.upload.jobs(UploadJobSet.IN_FLIGHT).any { it.matches(subject, route, id) } }
            val live = subject.upload.jobs(UploadJobSet.IN_FLIGHT).first { it.matches(subject, route, id) }
            assertIs<ChangeOutcome.Refused>(
                subject.upload.retry(live, subject.target(route)),
                "a platform with no free retry refuses one: a failure is re-created instead",
            )
            assertEquals(ChangeOutcome.Applied, subject.upload.cancel(live), "a live transfer is stopped")
            awaitWithin { subject.ended().any { it.destinationPath == subject.destination(route) } }
            assertTrue(
                subject.ended().none { it.destinationPath == subject.destination(route) && it.state == UploadJobState.SUCCEEDED },
                "a stopped transfer is reported, and never as a success",
            )
            val never = UploadJob(
                null,
                key(id, n = 2),
                subject.destination(route),
                CONTENT_TYPE,
                UploadJobState.PENDING,
                null,
                null,
            )
            assertIs<ChangeOutcome.Refused>(
                subject.upload.cancel(never),
                "a job the platform never made cannot be stopped",
            )
        }

        /*
         * The PRESENTED_* clauses act on a transfer the OS already settled — they create nothing, so none waits on an
         * upload. A retry is taken, and what it then does has no clause: production retries to the identical destination,
         * and a fixture route answers one status for good (`TransferFixture`), so the retried transfer is refused again
         * and presented as spent — which [UploadState.PRESENTED_RETRY_SPENT] holds.
         */
        clause(
            "PRESENTED_SUCCESS_IS_PRESENTED_UNTIL_ACKNOWLEDGED",
            UploadState.PRESENTED_SUCCEEDED,
            covers = cells {
                on<Upload> {
                    answers(Upload::jobs).returns()
                    answers(Upload::acknowledge).with(ChangeOutcome.Applied::class)
                }
            },
        ) { subject ->
            val id = "PRESENTED_SUCCESS_IS_PRESENTED_UNTIL_ACKNOWLEDGED"
            val route = preparedRoute(id, UploadState.PRESENTED_SUCCEEDED)
            val presented =
                assertNotNull(subject.presented(UploadJobSet.TERMINAL, route), "a settled success is presented")
            assertEquals(UploadJobState.SUCCEEDED, presented.state)
            assertEquals(CONTENT_TYPE, presented.contentType, "under the type it was created with")
            assertEquals(ChangeOutcome.Applied, subject.upload.acknowledge(presented))
            assertNull(subject.presented(UploadJobSet.TERMINAL, route), "an acknowledged job is not presented again")
            assertEquals(CONTENT_TYPE, subject.objects.landed(route)?.contentType, "the object landed under its type")
        }

        clause(
            "PRESENTED_REFUSAL_IS_OFFERED_FOR_RETRY",
            UploadState.PRESENTED_REFUSED_ONCE,
            covers = cells {
                on<Upload> {
                    answers(Upload::jobs).returns()
                    answers(Upload::retry).with(ChangeOutcome.Applied::class)
                }
            },
        ) { subject ->
            val id = "PRESENTED_REFUSAL_IS_OFFERED_FOR_RETRY"
            val route = preparedRoute(id, UploadState.PRESENTED_REFUSED_ONCE)
            val offered = assertNotNull(
                subject.presented(UploadJobSet.RETRY_OFFERED, route),
                "a transfer the destination refused once is offered for its free retry",
            )
            assertNotEquals(UploadJobState.SUCCEEDED, offered.state, "a refused transfer is not a success")
            assertEquals(CONTENT_TYPE, offered.contentType, "a retried transfer keeps the type it was created with")
            assertEquals(
                ChangeOutcome.Applied,
                subject.upload.retry(offered, subject.target(route)),
                "the free retry it is offered is taken",
            )
        }

        clause(
            "PRESENTED_RETRY_SPENT_IS_PRESENTED_UNTIL_ACKNOWLEDGED",
            UploadState.PRESENTED_RETRY_SPENT,
            covers = cells {
                on<Upload> {
                    answers(Upload::jobs).returns()
                    answers(Upload::acknowledge).with(ChangeOutcome.Applied::class)
                }
            },
        ) { subject ->
            val id = "PRESENTED_RETRY_SPENT_IS_PRESENTED_UNTIL_ACKNOWLEDGED"
            val route = preparedRoute(id, UploadState.PRESENTED_RETRY_SPENT)
            val presented = assertNotNull(
                subject.presented(UploadJobSet.TERMINAL, route),
                "a transfer whose free retry was refused too is presented as terminal",
            )
            assertNotEquals(UploadJobState.SUCCEEDED, presented.state, "a refused transfer is not a success")
            assertEquals(ChangeOutcome.Applied, subject.upload.acknowledge(presented))
            assertNull(subject.presented(UploadJobSet.TERMINAL, route), "an acknowledged job is not presented again")
        }
    }
}
