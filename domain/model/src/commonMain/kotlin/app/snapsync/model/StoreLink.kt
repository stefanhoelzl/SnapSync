package app.snapsync.model

import kotlinx.serialization.Serializable

/** The store a build is distributed through — what the update notice names on its one button (capability `app-update-required`). */
@Serializable
enum class StoreKind { APP_STORE, GOOGLE_PLAY }

/**
 * A build's store page: the address AND which store it is, one value so neither can be set without the other. The
 * update notice labels its button from [kind] and opens [url]; inferring the store from the URL's host was rejected as
 * fragile (decision record: `changes/archive/2026-10-01-play-badge-and-install-referrer`, D6).
 */
@Serializable
data class StoreLink(val url: String, val kind: StoreKind)
