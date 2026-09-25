package app.snapsync.model

/**
 * What a join request answered.
 *
 * The refusals are kept **apart**, because their consequences differ and a caller must be able to act on
 * that (`docs/architecture.md`, "Absence is never silent"): [EVENT_FULL] is a refusal the user can act on
 * and a screen can explain, [EVENT_NOT_FOUND] means the event is gone, and [FAILED] is a transport
 * failure that a retry may heal. Collapsing them into one boolean is what made "the event is full" and
 * "the network blipped" the same sentence on the join surface.
 */
enum class JoinResult { JOINED, EVENT_FULL, EVENT_NOT_FOUND, FAILED }
