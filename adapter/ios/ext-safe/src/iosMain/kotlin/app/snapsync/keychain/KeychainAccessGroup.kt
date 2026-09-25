package app.snapsync.keychain

/**
 * The Keychain access group both processes share (`keychain-access-groups` in **both** entitlements
 * files, as `$(AppIdentifierPrefix)app.snapsync.shared`).
 *
 * Named explicitly rather than left to the platform's default, and pinned as a runtime-identity
 * literal (`docs/architecture.md`) because it is part of the item's identity: re-valuing it
 * strands every device in the field exactly as re-valuing the service or account would, and it does so
 * **silently** — the item is simply written to a different real group, where every read still
 * succeeds and merely returns a different item.
 *
 * The guard composes it from `TEAM_ID` in the GENERATED `Deployment.xcconfig` plus the group declared
 * in the two entitlements files, so the three cannot drift apart unnoticed.
 */
const val SHARED_KEYCHAIN_ACCESS_GROUP: String = "E9Z8BADH58.app.snapsync.shared"
