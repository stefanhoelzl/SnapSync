package app.snapsync.services.secure

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.SecureStoreResolution
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.SecureStore
import app.snapsync.model.SecureStoreUnavailable

/**
 * The mint-once-then-read core, shared by every [SecureStore]-backed store and tested in `commonTest`
 * (so it runs on JVM **and** `iosSimulatorArm64`). Pure: the platform supplies the effects.
 *
 * The order below is normative, and each step exists because the one above it was once skipped:
 *
 * - [SecureStoreRead.Found] → return the stored value verbatim, upgrading its protection first if it
 *   is not what the store requires ([needsMigration]). The value is never rewritten.
 * - [SecureStoreRead.Absent] → consult [legacy] **before** minting. A value found there is adopted
 *   verbatim ([SecureStoreResolution.Adopted]); only a second absence mints. Callers that pass no
 *   [legacy] store mint straight away, which is correct for items with no legacy placement.
 * - [SecureStoreRead.Unavailable] → throw [SecureStoreUnavailable]. Never mints, never writes.
 * - A write that persists an adopted or minted value and is **refused** throws [SecureStoreUnavailable] too — the
 *   value is never handed out unsaved (an id used and not stored would be a different id on the next launch).
 *
 * Unavailability outranks both absence and adoption, on **either** read. "I could not look" is not
 * "there is nothing there", and conflating them is what mints a duplicate identity on a locked
 * device — the failure this ordering is built against.
 */
fun resolveOrMint(
    store: SecureStore,
    slot: SecureSlot,
    onResolution: (SecureStoreResolution) -> Unit = {},
    /** Where an older build may have placed the value; `null` for an item with no legacy placement. */
    legacy: SecureSlot? = null,
    generate: () -> String,
): String = when (val read = store.read(slot)) {
    is SecureStoreRead.Found -> {
        val migrated = needsMigration(read.protection)
        if (migrated) store.migrateProtection(slot) // best-effort: retried on the next read
        onResolution(SecureStoreResolution.Found(read.protection, migrated))
        read.value
    }

    // Absence in the addressed item is NOT yet permission to mint: an older build may have written
    // the value somewhere this query does not reach (see [SecureStoreResolution] for how that
    // happens). Consult [legacy] first and adopt whatever it finds, verbatim.
    SecureStoreRead.Absent -> when (val legacyRead = legacy?.let(store::read) ?: SecureStoreRead.Absent) {
        is SecureStoreRead.Found -> legacyRead.value.also {
            persist(store, slot, it)
            onResolution(SecureStoreResolution.Adopted)
        }

        SecureStoreRead.Absent -> generate().also {
            persist(store, slot, it)
            onResolution(SecureStoreResolution.Minted)
        }

        // "I could not look" on the LEGACY read is as disqualifying as on the primary one: minting
        // here would generate a second identity for a device that may already have one, which is the
        // unrecoverable outcome this whole ordering exists to prevent. Defer instead.
        is SecureStoreRead.Unavailable -> throw SecureStoreUnavailable(legacyRead.detail)
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
            val migrated = needsMigration(read.protection)
            if (migrated) store.migrateProtection(slot) // best-effort: retried on the next read
            onResolution(SecureStoreResolution.Found(read.protection, migrated))
            read.value
        }
        SecureStoreRead.Absent -> null
        is SecureStoreRead.Unavailable -> throw SecureStoreUnavailable(read.detail)
    }

/**
 * Whether a stored item must be upgraded in place to the protection this store requires.
 *
 * Migration is not optional book-keeping, and the argument is a property of **this seam**, not of any
 * one platform: a [SecureStore] **outlives the app install** by contract (that is the
 * reinstall-stability capability `photo-sharing` depends on), and the device id is written exactly
 * once, at mint. Nothing in the device's remaining lifetime will therefore ever rewrite the item — no
 * reinstall, no app update, no later write of any kind — so an item a pre-fix build filed as
 * unreadable-in-background stays that way **forever** unless the read path upgrades it.
 */
fun needsMigration(protection: StoredProtection): Boolean =
    protection != StoredProtection.BACKGROUND_READABLE

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
