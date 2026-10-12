package app.snapsync.ports

/** Which networks the platform may send a transfer's bytes over — fixed when the transfer is made. */
enum class TransferNetwork {
    /** Any network the phone offers. */
    ANY,

    /** Only a network that is neither costly nor data-restricted. */
    UNRESTRICTED_ONLY,
}

/**
 * Where an upload sends its bytes: the destination URL and the request headers — and which networks the platform may
 * send them over ([network]), fixed when the upload is made.
 */
data class UploadTarget(val url: String, val headers: Map<String, String>, val network: TransferNetwork)

/**
 * What the platform answered a request to make an upload: it exists ([CREATED]), the platform's in-flight limit is
 * reached ([LIMIT_EXCEEDED]), or it could not be made and does not exist ([FAILED]: an unusable source or destination).
 */
enum class CreateOutcome { CREATED, LIMIT_EXCEEDED, FAILED }

/**
 * Where an OS-held upload job stands, as the platform reports it. [UNKNOWN] is a state the platform reported that this
 * build does not know — kept apart from [PENDING]: a guess must read as a guess.
 */
enum class UploadJobState { SUCCEEDED, FAILED, CANCELLED, PENDING, REGISTERED, UNKNOWN }

/**
 * That a transfer did not succeed, and what the platform said of it ([detail]: a status the server answered, the
 * platform's own error, a cancellation). One fact, not a taxonomy: nothing above the adapter decides on why — a failed
 * upload is retried whatever it was — so the reason is carried for the log alone.
 */
data class TransferFailure(val detail: String)

/** What the platform answered a change to an upload (a retry, an acknowledgement, a cancel). */
sealed interface ChangeOutcome {
    data object Applied : ChangeOutcome

    /** The platform refused it; [code] and [detail] are its own, for the diagnostic line. */
    data class Refused(val code: Long?, val detail: String?) : ChangeOutcome
}

/**
 * What a finished download turned out to be, read off the platform's response: the HTTP [statusCode] (`null` when the
 * response carried none), the response's declared length ([expectedBytes], negative when it declared none) and the
 * length actually delivered ([receivedBytes]).
 */
data class TransferOutcome(
    val statusCode: Int?,
    val expectedBytes: Long,
    val receivedBytes: Long,
)

/**
 * Whether the platform took a download request. The causes of [NotStarted] are collapsed on purpose: every one leaves
 * the resource pending until the next reconcile, exactly as a started-then-failed transfer does.
 */
enum class StartResult { Started, NotStarted }
