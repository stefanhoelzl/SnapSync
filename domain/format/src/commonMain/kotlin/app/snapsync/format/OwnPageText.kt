package app.snapsync.format

import app.snapsync.domain.request.OwnPage

/** Where each of the app's own site's pages is: on the invite link's own origin. */
object OwnPageText {
    fun url(page: OwnPage): String = when (page) {
        OwnPage.WEBSITE -> LINK_ORIGIN
        OwnPage.PRIVACY_POLICY -> "$LINK_ORIGIN/#privacy"
    }
}
