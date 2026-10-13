package app.snapsync.services.secure

import app.snapsync.model.SecureStoreUnavailable
import app.snapsync.ports.SecureSlot
import app.snapsync.ports.SecureStore
import app.snapsync.ports.SecureStoreRead
import app.snapsync.ports.StoredProtection
import app.snapsync.ports.WriteOutcome

/**
 * The mint-once-then-read core, shared by every [SecureStore]-backed store and tested in `commonTest`
 * (on the JVM). Pure: the platform supplies the effects.
 *
 * The order below is normative, and each step exists because the one above it was once skipped:
 *
 * - [SecureStoreRead.Found] → return the stored value verbatim, reporting the protection the store
 *   gave for it. The value is never rewritten.
 * - [SecureStoreRead.Absent] → mint and persist ([SecureStoreResolution.Minted]).
 * - [SecureStoreRead.Unavailable] → throw [SecureStoreUnavailable]. Never mints, never writes.
 * - A write that persists a minted value and is **refused** throws [SecureStoreUnavailable] too — the
 *   value is never handed out unsaved (an id used and not stored would be a different id on the next launch).
 *
 * Unavailability outranks absence. "I could not look" is not "there is nothing there", and conflating
 * them is what mints a duplicate identity on a locked device — the failure this ordering is built against.
 */
fun resolveOrMint(
    store: SecureStore,
    slot: SecureSlot,
    onResolution: (SecureStoreResolution) -> Unit = {},
    generate: () -> String,
): String = when (val read = store.read(slot)) {
    is SecureStoreRead.Found -> {
        onResolution(SecureStoreResolution.Found(read.protection))
        read.value
    }

    SecureStoreRead.Absent -> generate().also {
        persist(store, slot, it)
        onResolution(SecureStoreResolution.Minted)
    }

    is SecureStoreRead.Unavailable -> throw SecureStoreUnavailable(read.detail)
}

/**
 * Read an existing value without ever minting: `null` when the item is genuinely
 * [SecureStoreRead.Absent], throwing [SecureStoreUnavailable] when it could not be read. Used by
 * stores (the attestation token) that have nothing to mint — they persist only what a backend or a
 * user action produced.
 *
 * Absence: null means **absent, and only absent**. This function is where that separation is
 * enforced for every [SecureStore]-backed store: an unreadable item throws rather than answering
 * empty, so no caller can mistake "the device is locked" for "this device never had a token". It is
 * the reference implementation of the rule, not an exception to it (`docs/architecture.md`,
 * "Absence is never silent").
 */
fun readExisting(
    store: SecureStore,
    slot: SecureSlot,
    onResolution: (SecureStoreResolution) -> Unit = {},
): String? =
    when (val read = store.read(slot)) {
        is SecureStoreRead.Found -> {
            onResolution(SecureStoreResolution.Found(read.protection))
            read.value
        }
        SecureStoreRead.Absent -> null
        is SecureStoreRead.Unavailable -> throw SecureStoreUnavailable(read.detail)
    }

/**
 * Persist [value] at [slot], or throw [SecureStoreUnavailable]: where the old throwing port threw, so a refused
 * write fails the operation at the same place — the identity is not handed out, the token not accepted.
 */
fun persist(store: SecureStore, slot: SecureSlot, value: String) {
    when (val written = store.write(slot, value)) {
        WriteOutcome.Ok -> Unit
        is WriteOutcome.Failed -> throw SecureStoreUnavailable("write refused: ${written.detail}")
    }
}

/**
 * Which branch of [resolveOrMint] produced the returned value — reported to the caller so it can be
 * *observed*, not merely trusted.
 *
 * This exists because a silent mint is indistinguishable from a successful read at every layer above
 * this one, and the difference is the whole ballgame: a second mint hands the process a **new
 * identity**, orphaning its `/files/devices/<deviceId>/` partition and making its own uploads read as
 * another contributor's (`DownloadController` skips an asset only when `asset.deviceId ==
 * myDeviceId`, so a re-minted id re-downloads and re-imports every photo the device itself
 * contributed — one duplicate per photo, in the user's own library).
 *
 * That is not hypothetical: on 2026-07-20 an SE2 ran for nine hours with the app on one id and the
 * upload extension on another, and **nothing anywhere logged either one** — the symptom reached the
 * screen as an indefinite "pending" and reached the library as duplicated photos. [Found] carries the
 * item's protection because an unexpected protection is the leading suspect for a read that reports
 * *absent* against an item that does exist.
 */
sealed interface SecureStoreResolution {

    /** An existing item was read, and the [protection] the store reported for it (a diagnostic). */
    data class Found(val protection: StoredProtection) : SecureStoreResolution

    /** No item existed anywhere, so one was generated and persisted. The only new-identity branch. */
    data object Minted : SecureStoreResolution
}

/**
 * The items this app keeps in the `SecureStore` — runtime identity: the installed base holds them under exactly
 * these addresses, and a re-valued one silently addresses a different real item (`docs/architecture.md`).
 */
object SecureSlots {

    /** The device id, in the shared group both processes read. */
    val DEVICE_ID = SecureSlot(service = "app.snapsync.deviceid", account = "deviceid", shared = true)

    /** The attestation token and its App Attest key id. Unscoped (pinned). */
    val ATTEST_TOKEN = SecureSlot(service = "app.snapsync.attest", account = "token", shared = false)
    val ATTEST_KEY_ID = SecureSlot(service = "app.snapsync.attest", account = "keyid", shared = false)

    /**
     * The joined event's key, when it is ENCRYPTED (the encrypted file format, `docs/architecture.md`): base64url, in
     * the shared group, because the upload extension derives each file's key from it. One slot — one membership.
     */
    val EVENT_KEY = SecureSlot(service = "app.snapsync.eventkey", account = "key", shared = true)
}
