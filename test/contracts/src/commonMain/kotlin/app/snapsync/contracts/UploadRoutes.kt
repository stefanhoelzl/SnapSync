package app.snapsync.contracts

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.model.destinationPathOf
import app.snapsync.model.uploadKey
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.UploadTarget

// What both upload contracts' clauses send and where: the routes on the transfer fixture, and the key a transfer is
// tagged with.

/** The ledger key an upload clause uploads under (`<assetId>-<role>.<ext>`), distinct per clause and per [n]. */
fun uploadClauseKey(clauseId: String, n: Int = 1): String =
    uploadKey(AssetId.parse("contract-${clauseId.lowercase()}-$n")!!, ResourceRole.PRIMARY, "IMG_0001.JPG")

/** The route [contract]'s clause [clauseId] sends its upload [n] to. */
fun uploadClausePath(contract: String, clauseId: String, answer: FixtureAnswer, n: Int = 1): String =
    TransferFixture.path(contract, clauseId, "upload-$n", answer)

/** The type every upload clause's transfer declares. */
const val UPLOAD_CONTENT_TYPE: String = "image/jpeg"

/** An accepting route's answer. */
val UPLOAD_ACCEPT: FixtureAnswer = FixtureAnswer.Respond(200)

/** A refusing route's answer. */
val UPLOAD_REJECT: FixtureAnswer = FixtureAnswer.Respond(500)

/** The target an upload to [route] on the fixture at [base] declares. */
fun uploadTarget(base: String, route: String, network: TransferNetwork = TransferNetwork.ANY): UploadTarget =
    UploadTarget(base + route, mapOf("Content-Type" to UPLOAD_CONTENT_TYPE), network)

/** The path the destination of [route] on the fixture at [base] names — what a job is known by. */
fun uploadDestination(base: String, route: String): String = destinationPathOf(base + route)
