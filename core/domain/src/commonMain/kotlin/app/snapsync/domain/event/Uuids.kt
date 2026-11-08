package app.snapsync.domain.event

/** The canonical UUID spelling the backend declares for its ids (`api/openapi.yaml`, `Uuid`): `8-4-4-4-12` hex. */
internal object Uuids {
    private val CANONICAL = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun isCanonical(text: String): Boolean = CANONICAL.matches(text)
}
