package app.snapsync.services.secure

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.SecureStoreResolution
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.SecureStore
import app.snapsync.model.SecureStoreUnavailable

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
        WriteOutcome.Unsupported -> throw SecureStoreUnavailable("write unsupported for $slot")
    }
}
