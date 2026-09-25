package app.snapsync.ports

/**
 * The record of the **last push registration the backend accepted** (capability `receiving-photos`,
 * "Registration timing — launch, join, and rotation"), for publish-only-on-a-change: the app asks the OS for its
 * APNs token at every app entry, and a delivered token is published only when what it would register differs from
 * this record. On iOS a file in the App-Group container; the world and the tests use the in-memory double.
 *
 * The value is opaque here: which facts make up a registration — the token, its environment and the device id it
 * was written under — and how they are joined into one string are the push feature's, so a new fact joins the key
 * without a change to this seam or its adapters. An instance addresses one record, the way `ConfigStore` addresses
 * one config.
 *
 * It is a belief about a **remote** resource, like the device manifest's last-uploaded record, and it has the same
 * two directions of being wrong. `null` when a registration exists costs one idempotent `PUT` (last-write-wins at
 * the endpoint). A stale non-null — the backend lost a registration this record still claims — suppresses the
 * publish until the next join, rotation or fresh credential, which publish whatever the record holds; that is the
 * accepted residual of decision record `changes/own-work-per-wake`, D12.
 */
interface PushRegistrationRecord {

    /**
     * The last accepted registration, as the push feature encoded it.
     *
     * Absence: `null` covers "nothing registered yet" and "could not read the record" alike. Both publish, which is
     * the safe direction — one redundant idempotent `PUT` — and never a wrong belief that a registration exists.
     */
    fun loadLastRegistered(): String?

    /**
     * Record [value] as the last accepted registration, replacing any earlier one. Called only after the backend
     * accepted it. A store that cannot be written degrades to writing nothing, never to raising: a lost record
     * costs one publish at the next app entry.
     */
    fun saveLastRegistered(value: String)
}
