package app.snapsync.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val refusalJson = Json { ignoreUnknownKeys = true }

@Serializable
private class RefusalBody(@SerialName("minAppVersion") val minAppVersion: String? = null)

/**
 * The minimum version out of a `426 Upgrade Required` body, or `null` when it does not carry one
 * (capability `min-app-version`).
 *
 * A pure codec, here in `model/` rather than in the interceptor that reads it, for the reason every
 * codec is here: it is the one definition of a wire shape, and it is unit-testable without a client, a
 * server or a platform.
 *
 * **Null is a real answer and must stay distinguishable from a version.** The refusal is legible from
 * the status alone — this build is too old — but only the minimum makes it *actionable*, and inventing
 * one would put a specific, wrong number on the screen. A caller that gets `null` says "this build is
 * out of date" without naming a version, which is true; naming a version we guessed is not
 * (`module-architecture`, "Absence is never silent").
 *
 * Absence: null means the refusal named no version this build could read, and every cause collapses to
 * it — malformed JSON, a JSON value that is not an object, a missing field, a null field, a blank one.
 * That is safe for all of them because the REFUSAL itself is carried by the `426` status, which the
 * caller has already read before calling this: what is lost is only the version to print, and the screen
 * that would have printed it says "a newer version is needed" instead, which is true whatever the cause.
 * Nothing branches on this value other than that one sentence, so no cause absorbed here can produce a
 * different consequence than any other.
 */
fun minAppVersionFromRefusal(body: String): String? =
    runCatching { refusalJson.decodeFromString(RefusalBody.serializer(), body).minAppVersion }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
