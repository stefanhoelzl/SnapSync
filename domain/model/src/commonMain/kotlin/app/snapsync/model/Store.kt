package app.snapsync.model

/**
 * A build's store page: the store's [name] AND its page's [url], one value so neither can be set without the other.
 * The update notice names the store on its one button and opens [url]. Nothing decides on either: the name is shown as
 * given (a brand, untranslated), and inferring the store from the URL's host stays rejected as fragile (decision
 * record: `changes/archive/2026-10-01-play-badge-and-install-referrer`, D6).
 */
data class Store(val name: String, val url: String)
