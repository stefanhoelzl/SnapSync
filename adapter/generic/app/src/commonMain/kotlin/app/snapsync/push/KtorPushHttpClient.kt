package app.snapsync.push

import app.snapsync.ports.PushHttpClient

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/** [PushHttpClient] over an injected Ktor [HttpClient] (the shared Darwin client on iOS). */
class KtorPushHttpClient(private val client: HttpClient) : PushHttpClient {
    override suspend fun put(url: String, jsonBody: String): Result<Unit> = runCatching {
        val res = client.put(url) {
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        check(res.status.isSuccess()) { "config PUT $url: HTTP ${res.status.value} ${res.bodyAsText()}" }
    }

    override suspend fun post(url: String): Result<Unit> = runCatching {
        val res = client.post(url)
        check(res.status.isSuccess()) { "notify POST $url: HTTP ${res.status.value} ${res.bodyAsText()}" }
    }
}
