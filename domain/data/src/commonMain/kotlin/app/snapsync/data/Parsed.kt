package app.snapsync.data

import app.snapsync.data.store.Stored
import app.snapsync.domain.event.EventName
import app.snapsync.domain.event.EventNameVerdict
import app.snapsync.domain.time.Timestamp
import app.snapsync.format.TimestampText

/**
 * A value read from outside the core — a stored row, a server's reply — as the domain takes it, or why the domain
 * refuses it. Nothing refused is guessed around: the caller answers "could not read".
 */
internal sealed interface Parsed<out T> {
    class Ok<T>(val value: T) : Parsed<T>

    class Refused(val detail: String) : Parsed<Nothing>
}

/** [next] of this value, or this refusal. */
internal fun <A, B> Parsed<A>.then(next: (A) -> Parsed<B>): Parsed<B> = when (this) {
    is Parsed.Ok -> next(value)
    is Parsed.Refused -> this
}

/** The id [stored] holds, or that its [column] spells none. */
internal fun <T : Any> parsed(stored: Stored<T>, column: String): Parsed<T> = when (stored) {
    is Stored.Valid -> Parsed.Ok(stored.value)
    is Stored.Invalid -> Parsed.Refused("its $column is no id")
}

/** The ids [stored] hold, or that one of its [column]s spells none. */
internal fun <T : Any> parsedAll(stored: Collection<Stored<T>>, column: String): Parsed<List<T>> =
    stored.fold<Stored<T>, Parsed<List<T>>>(Parsed.Ok(emptyList())) { ids, next ->
        ids.then { held -> parsed(next, column).then { Parsed.Ok(held + it) } }
    }

/** [text] as an event's name, or why it is none. */
internal fun eventName(text: String): Parsed<EventName> = when (val name = EventName.of(text)) {
    is EventNameVerdict.Ok -> Parsed.Ok(name.name)
    EventNameVerdict.Blank, EventNameVerdict.TooLong -> Parsed.Refused("its name is $name")
}

/** [text] as an instant, or why it is none. */
internal fun instant(text: String): Parsed<Timestamp> =
    TimestampText.parseOrNull(text)?.let { Parsed.Ok(it) } ?: Parsed.Refused("$text is no instant")
