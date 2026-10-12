package app.snapsync.ports

/**
 * What the photo library lets this process read, as the platform reports it. What a grant permits — reading at all,
 * walking authoritatively, registering the upload extension — is the core's reading of it.
 *
 * | PhotoKit | Android | reads as |
 * |---|---|---|
 * | `.authorized` | the media permissions (or storage, before API 33) | [GRANTED] |
 * | `.limited` | only "Select photos and videos" (API 34+) | [LIMITED] |
 * | `.denied`, `.restricted` | nothing, after the app asked | [DENIED] |
 * | `.notDetermined` | nothing, never asked | [NOT_DETERMINED] |
 */
enum class LibraryGrant { NOT_DETERMINED, DENIED, LIMITED, GRANTED }
