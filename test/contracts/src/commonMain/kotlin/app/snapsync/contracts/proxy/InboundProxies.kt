@file:OptIn(InboundImplementation::class)

package app.snapsync.contracts.proxy

import app.snapsync.inbound.Answered
import app.snapsync.inbound.Background
import app.snapsync.inbound.Completion
import app.snapsync.inbound.Granted
import app.snapsync.inbound.Inbound
import app.snapsync.inbound.InboundImplementation
import app.snapsync.inbound.Running

// The deliveries an event port makes, as a clause's [CallLog][app.snapsync.contracts.CallLog] sees them: each handler
// wrapped so its delivery is recorded as `Port.handlers.field(Arg, …)` before it reaches the handler the clause
// registered. One wrapper per inbound kind.

/** A synchronous delivery, recorded with its argument (nothing for `Unit`). */
internal class InboundProxy<A, R>(
    private val inner: Inbound<A, R>,
    private val r: Recorder,
    private val field: String,
    private val plain: String? = null,
) : Inbound<A, R> {
    override fun deliver(arg: A): R {
        r.called("handlers.$field", *argsOf(arg, plain))
        return inner.deliver(arg)
    }
}

/** A delivery answered later, recorded with its argument and its completion — whose answer is recorded too. */
internal class BackgroundProxy<A>(
    private val inner: Background<A>,
    private val r: Recorder,
    private val field: String,
    private val plain: String? = null,
) : Background<A> {
    override fun handle(arg: A, completion: Completion) {
        r.called("handlers.$field", *argsOf(arg, plain), Recorder.arg(completion, "Completion"))
        inner.handle(arg, CompletionProxy(completion, r.handle("Completion")))
    }
}

/** A grant of time, recorded as a [BackgroundProxy] is — and the run it answers, as the adapter expires it. */
internal class GrantedProxy<A>(
    private val inner: Granted<A>,
    private val r: Recorder,
    private val field: String,
    private val plain: String? = null,
) : Granted<A> {
    override fun handle(arg: A, completion: Completion): Running {
        r.called("handlers.$field", *argsOf(arg, plain), Recorder.arg(completion, "Completion"))
        return RunningProxy(inner.handle(arg, CompletionProxy(completion, r.handle("Completion"))), r.handle("Running"))
    }
}

/** A synchronous delivery answered on its thread, recorded with its argument and its answer. */
internal class AnsweredProxy<A, R>(
    private val inner: Answered<A, R>,
    private val r: Recorder,
    private val field: String,
) : Answered<A, R> {
    override fun answer(arg: A): R {
        r.called("handlers.$field", *argsOf(arg, null))
        return inner.answer(arg)
    }
}

/** A run, as the adapter it was handed to expires it. */
internal class RunningProxy(private val inner: Running, private val r: Recorder) : Running {
    override fun expire() = r.returns("expire", inner.expire())
}

private fun argsOf(arg: Any?, plain: String?): Array<String> =
    if (arg == Unit) emptyArray() else arrayOf(Recorder.arg(arg, plain))
