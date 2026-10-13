@file:OptIn(InboundImplementation::class)

package app.snapsync.contracts

import app.snapsync.inbound.Answered
import app.snapsync.inbound.Background
import app.snapsync.inbound.Completion
import app.snapsync.inbound.Granted
import app.snapsync.inbound.Inbound
import app.snapsync.inbound.InboundImplementation
import app.snapsync.inbound.Running

// The handlers a clause registers on an event port: the clause's own observation of each delivery, one per inbound
// kind. A clause plays the core here — it is the one place outside the entry-point bases that receives a delivery.

/** A synchronous delivery: [deliver] sees it and answers it. */
fun <A, R> inboundOf(deliver: (A) -> R): Inbound<A, R> = object : Inbound<A, R> {
    override fun deliver(arg: A): R = deliver(arg)
}

/** A delivery answered later: [handle] sees it with its completion. */
fun <A> backgroundOf(handle: (A, Completion) -> Unit): Background<A> = object : Background<A> {
    override fun handle(arg: A, completion: Completion) = handle(arg, completion)
}

/** A grant of time: [handle] sees it with its completion; [expired] sees the adapter expire the run. */
fun <A> grantedOf(expired: () -> Unit = {}, handle: (A, Completion) -> Unit): Granted<A> = object : Granted<A> {
    override fun handle(arg: A, completion: Completion): Running {
        handle(arg, completion)
        return object : Running {
            override fun expire() = expired()
        }
    }
}

/** A synchronous delivery answered on its thread: [answer] sees it and answers. */
fun <A, R> answeredOf(answer: (A) -> R): Answered<A, R> = object : Answered<A, R> {
    override fun answer(arg: A): R = answer(arg)
}
