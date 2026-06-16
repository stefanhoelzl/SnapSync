package app.snapsync.s3

/**
 * The S3 presigner's injected input contract (docs/design.md §4): where to upload and which
 * credentials to sign with. A plain value type of literal strings — deliberately free of any
 * build-time-config (BuildKonfig) coupling, so the presigner stays a pure library testable with
 * hand-constructed config. Wiring `BuildKonfig → S3Config` is a later app-side concern; this type
 * is merely one of its consumers.
 *
 * [endpoint] is a scheme + authority base URL (e.g. `https://s3.eu-central-1.amazonaws.com` or
 * `http://localhost:9090`) with no path — path-style object URLs are built as
 * `<endpoint>/<bucket>/<key>`, and the signed `Host` is the endpoint authority (including a
 * non-default port). Credentials are plain strings (the design accepts IPA-extractable keys); no
 * STS/session token in v1.
 */
class S3Config(
    val bucket: String,
    val region: String,
    val endpoint: String,
    val accessKeyId: String,
    val secretAccessKey: String,
)
