package app.snapsync.ports

/**
 * An event port (`docs/architecture.md`, "Events arrive through `listen`"): the platform pushes what happened into
 * the [H] handlers the composition registered. [listen] is called **once per adapter instance**, by the host zone,
 * and only registers: it runs no handler, reads nothing and builds no feature — the handlers themselves are the
 * composition's, each a flow command, a service call or a presentation intent.
 */
interface Listenable<H> {
    fun listen(handlers: H)
}
