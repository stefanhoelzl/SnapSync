package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.contracts.answerVariant

/**
 * What a recording proxy writes into its clause's [CallLog]: the grid's own cell text under one OWNER — a port, or a
 * handle that port hands out (`Download.Completion`), counted under the port as the grid counts it.
 *
 * The proxies are hand-written, one per port of the grid (`docs/testing.md`, "A declared cell must occur"), and each
 * names its member by the same text the grid derives from `:domain:ports`; the completeness gate in `:test:architecture`
 * drives every cell of the grid through them and fails on one recorded differently.
 */
class Recorder internal constructor(private val log: CallLog, private val owner: String) {

    /** [member]'s answer, rendered as a variant ([answerVariant]): a sealed leaf, an enum entry, a `Boolean`, `null`. */
    fun <R> answer(member: String, value: R): R {
        log.record("$owner.$member → ${answerVariant(value)}")
        return value
    }

    /** [member]'s answer of a type that is neither sealed, enum nor `Boolean`: `returns`, or `null` when nullable. */
    fun <R> returns(member: String, value: R): R {
        log.record("$owner.$member → ${if (value == null) "null" else RETURNS}")
        return value
    }

    /**
     * A call the adapter makes into what the core handed it: a handler (`handlers.onLink`) or a member's callback
     * (`begin.onExpiry`), with each argument's variant ([arg]).
     */
    fun called(path: String, vararg args: String) = log.record("$owner.$path(${args.joinToString(", ")})")

    /** The recorder of a handle this owner hands out, whose cells are counted under this owner. */
    fun handle(name: String): Recorder = Recorder(log, "$owner.$name")

    /** A recorder over the same log for another port — a member [Gallery][app.snapsync.ports.Gallery] inherits. */
    fun port(name: String): Recorder = Recorder(log, name)

    companion object {
        const val RETURNS = "returns"

        /**
         * A handler or callback argument's variant: `null`, or for an argument whose type is neither sealed, enum nor
         * `Boolean` the type's name as the grid writes it ([plain]); otherwise the value's variant.
         */
        fun arg(
            value: Any?,
            plain: String? = null,
        ): String = if (value == null || plain == null) answerVariant(value) else plain
    }
}

/** The recorder of port [name] over [log]. */
internal fun CallLog.recorder(name: String) = Recorder(this, name)
