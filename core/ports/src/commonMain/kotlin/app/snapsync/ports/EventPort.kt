package app.snapsync.ports

import app.snapsync.inbound.CompositionOnly

/**
 * An event port (`docs/patterns.md`, "Event ports"): the platform delivers what happened through the [H] handlers a
 * composition hands it — each one an inbound kind (`Inbound`, `Background`, `Answered`), implemented by an entry
 * point.
 */
interface EventPort<H> : Port {
    /**
     * Hands the port its handlers **and starts the source**: nothing is delivered before this returns, so a
     * composition calls it once per adapter, after the graph the deliveries reach is built. Handler state is the
     * adapter's.
     */
    @CompositionOnly
    fun inbound(handlers: H)
}
