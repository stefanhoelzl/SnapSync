package app.snapsync.model

/**
 * The longest event name the backend accepts (capability `event-creation`).
 *
 * **The backend's rule, mirrored — not a second rule.** `api/src/validators.ts` holds the authority
 * (`MAX_EVENT_NAME_LENGTH`); this is the client's copy of it, so the name field can cap typing and make
 * an over-long name unreachable rather than rejected on a round trip. `EventNameLimitTest` asserts the
 * two agree, because a mirror nobody checks is just a second rule with a shared value today.
 *
 * It lives in `model/` for the reason [Arrow] and the range presets do: both surfaces that collect a
 * name — the create form and the rename dialog — need it, and they are in different modules. Before
 * this it was a private constant in one screen and a bare `100` in the other, which is how one of them
 * could have drifted with nothing to say so.
 */
const val EVENT_NAME_MAX_LENGTH = 100
