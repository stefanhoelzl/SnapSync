package app.snapsync.services.preferences

/**
 * The keys the services keep in the `Preferences` — runtime identity: the installed base holds the values under exactly
 * these keys, and a re-valued one silently reads every device as never having written it (`RuntimeIdentityTest` pins
 * each). Gathered in one place so the test channel names each by what it is, never by its text. The older builds' keys
 * the legacy-records copy reads stay beside that copy.
 */
object PreferenceKeys {
    /** The device's mobile-data choice (`mobile-data-per-device` D1). */
    const val MOBILE_DATA = "app.snapsync.settings.mobileData"

    /** The last push registration the backend accepted. */
    const val LAST_REGISTERED = "app.snapsync.push.lastRegistered"
}
