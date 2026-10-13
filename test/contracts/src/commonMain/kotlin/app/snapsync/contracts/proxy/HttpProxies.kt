package app.snapsync.contracts.proxy

import app.snapsync.contracts.CallLog
import app.snapsync.ports.HttpRequest
import app.snapsync.ports.HttpTransport

/** [HttpTransport] as its clause's [CallLog] sees it. */
fun HttpTransport.recorded(log: CallLog): HttpTransport = HttpTransportProxy(log.wraps(this), log)

internal class HttpTransportProxy(private val inner: HttpTransport, log: CallLog) : HttpTransport {
    private val r = log.recorder("HttpTransport")

    override suspend fun send(request: HttpRequest) = r.answer("send", inner.send(request))
}
