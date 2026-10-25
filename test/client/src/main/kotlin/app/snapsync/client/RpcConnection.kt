package app.snapsync.client

import app.snapsync.levers.LeverRefused
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import java.net.ConnectException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One WebSocket to a host's kRPC route (`RigServer`'s `/rpc`) — replaced once it is gone. An app the operating system
 * suspended, or one a caller stopped and started again, comes back with its sockets gone; the next call then goes out
 * on a new connection rather than failing on the old one.
 *
 * What is sent again, and what never is:
 * - A call whose connection never opened — refused, as by a host still starting — was never sent: it is sent on a new
 *   one, until [OPENING_BOUND].
 * - A call whose connection had opened may have reached the host before it failed. It is NEVER sent again — a lever
 *   run twice is a different test — and its failure is the caller's. The next call goes out on a new connection.
 * - A stream ([follow]) is opened again on every drop: what it serves is a stream, never one act.
 */
class RpcConnection(baseUrl: String) : AutoCloseable {
    /** The route, as a WebSocket URL. */
    val url: String = "ws${baseUrl.trimEnd('/').removePrefix("http")}$RPC_PATH"

    private val http = HttpClient(CIO) { installKrpc() }
    private var opened: KtorRpcClient? = null

    /** The client in use while its socket is open or still opening; a new one once it closed or never opened. */
    val client: KtorRpcClient
        @Synchronized get() = opened?.takeIf { it.usable() } ?: open()

    /** Calls [block] with the live client, sending it again on a new connection only while none would open. */
    suspend fun <R> sending(block: suspend (KtorRpcClient) -> R): R {
        val giveUp = TimeSource.Monotonic.markNow() + OPENING_BOUND
        while (true) {
            val client = client
            try {
                return block(client)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                currentCoroutineContext().ensureActive()
                val unsent = e.refusedConnect() || client.neverOpened()
                // A client whose socket is not open is of no use to the next call either.
                if (!client.open()) drop(client)
                if (!unsent || giveUp.hasPassedNow()) throw e
                delay(REOPEN_AFTER)
            }
        }
    }

    /**
     * Follows the stream [open] answers, handing each element to [each] until it answers `true`; opened again on a new
     * connection whenever the connection drops, [dropped] hearing why. Answers `true` when [each] ended it, `false`
     * when the host ended the stream. A refusal, the caller's cancellation and whatever [each] throws end it.
     *
     * An opened-again stream serves what the host serves from where it starts, so [each] may see an element twice: a
     * device host serves from where its latest act began.
     */
    suspend fun <T> follow(
        open: (KtorRpcClient) -> Flow<T>,
        dropped: (String) -> Unit = {},
        each: suspend (T) -> Boolean,
    ): Boolean {
        while (true) {
            val client = client
            try {
                var stopped = false
                open(client).takeWhile { element -> !handed(each, element).also { stopped = it } }.collect()
                return stopped
            } catch (e: EachFailed) {
                throw e.cause
            } catch (e: LeverRefused) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // kRPC cancels a stream whose socket closed; only the caller's own cancellation ends the follow.
                currentCoroutineContext().ensureActive()
                dropped(e.message ?: e::class.simpleName.orEmpty())
                drop(client)
                delay(REOPEN_AFTER)
            }
        }
    }

    /** Drops the connection in use: the next call or stream opens a new one. */
    @Synchronized
    fun drop() {
        opened?.let { runCatching { it.close() } }
        opened = null
    }

    override fun close() {
        drop()
        http.close()
    }

    @Synchronized
    private fun drop(client: KtorRpcClient) {
        if (opened === client) drop()
    }

    private fun open(): KtorRpcClient {
        drop()
        return http.rpc(url) { rpcConfig { serialization { json() } } }.also { opened = it }
    }

    private companion object {
        /** How long a call goes on waiting for a connection that will not open: a host starting takes seconds. */
        val OPENING_BOUND: Duration = 10.seconds
        val REOPEN_AFTER: Duration = 500.milliseconds
    }
}

/** What [RpcConnection.follow]'s `each` threw: never read as a dropped connection. */
private class EachFailed(override val cause: Exception) : Exception(cause)

private suspend fun <T> handed(each: suspend (T) -> Boolean, element: T): Boolean = try {
    each(element)
} catch (e: CancellationException) {
    throw e
} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
    throw EachFailed(e)
}

/** Open, or opening; not once its socket closed, nor when it never opened. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun KtorRpcClient.usable(): Boolean {
    val session = webSocketSession
    return when {
        !session.isCompleted -> true
        session.isCancelled -> false
        else -> session.getCompleted().isActive
    }
}

/** Its socket opened and is still open. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun KtorRpcClient.open(): Boolean =
    webSocketSession.let { it.isCompleted && !it.isCancelled && it.getCompleted().isActive }

/** Its socket never opened, so nothing went out over it. */
private fun KtorRpcClient.neverOpened(): Boolean = webSocketSession.isCancelled

/** The host refused the connection — kRPC leaves its session open-ended then — so nothing went out. */
private fun Throwable.refusedConnect(): Boolean = generateSequence(this) { it.cause }.any { it is ConnectException }

/** The rig's kRPC route (`RigServer`). */
private const val RPC_PATH = "/rpc"
