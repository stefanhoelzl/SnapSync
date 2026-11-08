package app.snapsync.domain.request

import kotlin.jvm.JvmInline

/**
 * One request a member made — a join confirmed, settings saved, a rename — answered later. Its outcome is published
 * under it, so an earlier attempt's outcome never answers a later one.
 */
@JvmInline
value class RequestId(val value: Long)
