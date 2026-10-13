@file:OptIn(InboundImplementation::class)

package app.snapsync.feature.support

import app.snapsync.inbound.Inbound
import app.snapsync.inbound.InboundImplementation

/** A synchronous delivery a test registers: [deliver] sees it. */
fun <A> inboundOf(deliver: (A) -> Unit): Inbound<A, Unit> = object : Inbound<A, Unit> {
    override fun deliver(arg: A) = deliver(arg)
}
