package app.snapsync.buildlogic

/**
 * The store listing's language files held to the app's languages (`docs/architecture.md`, "Localization"), since
 * the store pages speak exactly the languages the app does: each family (`metadata/listing/`,
 * `metadata/screenshots/`) holds one `<store-locale>.json` per language the app speaks — no more, no fewer — and
 * every translation carries exactly the base's keys, so a dropped or misspelt key fails the PR that made it rather
 * than a render or a promote. `_comment` is documentation, exempt.
 *
 * Pure, so its judgement is tested on its own ([NativeStringsTask] reads the files and throws what this answers).
 */
object StoreListings {
    const val COMMENT = "_comment"

    /**
     * What is wrong with [families] (a family's name → its files, store locale → parsed JSON) given the [expected]
     * store locales, base first. Empty when everything agrees.
     */
    fun problems(families: Map<String, Map<String, Any?>>, expected: List<String>): List<String> =
        families.flatMap { (family, files) ->
            val present = files.keys
            val missing = expected.filterNot { it in present }
            val extra = present.filterNot { it in expected }.sorted()
            val set = buildList {
                if (missing.isNotEmpty()) add("$family has no ${missing.joinToString { "$it.json" }}")
                if (extra.isNotEmpty()) {
                    add("$family has ${extra.joinToString { "$it.json" }}, a language the app does not speak")
                }
            }
            val base = expected.first()
            val parity = files[base]?.let { baseJson ->
                val want = keys(baseJson)
                expected.drop(1).filter { it in files }.flatMap { locale ->
                    val got = keys(files.getValue(locale))
                    listOfNotNull(
                        (want - got).takeIf { it.isNotEmpty() }
                            ?.let { "$family/$locale.json lacks ${it.sorted()}, which $base.json has" },
                        (got - want).takeIf { it.isNotEmpty() }
                            ?.let { "$family/$locale.json has ${it.sorted()}, which $base.json lacks" },
                    )
                }
            }.orEmpty()
            set + parity
        }

    /** Every leaf's dotted path in [json]; an object recurses, anything else is a leaf. [COMMENT] is skipped. */
    fun keys(json: Any?, prefix: String = ""): Set<String> =
        if (json is Map<*, *>) {
            json.entries.filter { it.key != COMMENT }.flatMap { (key, value) ->
                keys(value, if (prefix.isEmpty()) "$key" else "$prefix.$key")
            }.toSet()
        } else {
            setOf(prefix)
        }
}
