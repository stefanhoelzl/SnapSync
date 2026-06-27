package app.snapsync.rejoin

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * The iOS HTTP client for the re-join list fetch: NSURLSession via Ktor's Darwin engine, so the
 * fetch honours default ATS (HTTPS-only). Lives here so the engine choice stays in the capability
 * and `:app:ios` only wires it into [HttpEventFilesSource].
 */
fun darwinHttpClient(): HttpClient = HttpClient(Darwin)
