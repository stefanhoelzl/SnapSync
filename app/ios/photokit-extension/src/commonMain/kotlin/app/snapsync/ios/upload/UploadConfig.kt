package app.snapsync.ios.upload

import app.snapsync.config.S3ConfigPayload
import app.snapsync.s3.S3Config

/**
 * Combine the runtime Keychain [payload] (bucket/region/creds) with the compile-time upload [host]
 * (`BackgroundUploadURLBase`) into the provider's [S3Config]. Returns `null` — meaning "skip this
 * cycle, there is nothing to do" — when either input is absent: a `null` payload (setup not done
 * yet) or a missing/blank host (a build misconfiguration). Pure and platform-free, so the
 * assemble-or-skip decision is unit-tested off-device while the iOS root stays trivial glue.
 */
fun buildS3Config(payload: S3ConfigPayload?, host: String?): S3Config? {
    if (payload == null || host.isNullOrEmpty()) return null
    return S3Config(
        bucket = payload.bucket,
        region = payload.region,
        endpoint = host,
        accessKeyId = payload.accessKeyId,
        secretAccessKey = payload.secretAccessKey,
    )
}
