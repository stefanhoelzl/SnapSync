package app.snapsync.services.logs

import app.snapsync.ports.BuildInfo

/**
 * The process's boot banner, its first lines:
 *
 * 1. the process and its build, so a reader who concatenates the app's and the extension's logs can tell runs apart —
 *    a process with no id of its own (a test executable) by its platform;
 * 2. the backend this build talks to. It names the one fact that makes an otherwise-silent failure legible: point a
 *    build at a different backend without a device reset and the ledger still says COMPLETED, so the device uploads
 *    nothing — no error, no failed request. Beside the cycle's own enumeration line, a changed host beside an
 *    unchanged ledger names the cause at once.
 *
 * What only a root knows — the adapters it chose, where the extension's log goes — the root logs after composing.
 */
fun BuildInfo.bootBanner(): List<String> = listOf(
    "=== ${processId ?: platform.tag} process start build=$appVersion($buildNumber) ===",
    "[boot] upload base = $uploadHost",
)
