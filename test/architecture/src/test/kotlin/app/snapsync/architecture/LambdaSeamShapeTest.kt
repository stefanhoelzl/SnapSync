package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The callback-slot and lambda-default gates** (`docs/architecture.md`; laws: `docs/architecture.md`,
 * "Callbacks are bound at construction" and "Function-typed parameters have no defaults in production").
 *
 * Both close a way for a seam to be silently unwired:
 *
 *  - **A function-typed `var`** is a slot someone must remember to fill. `QueuedPhotoDownloadJobs.onStaged`
 *    was one, assigned while building the download controller — so a process the OS relaunched only to deliver
 *    download-session events, which builds the jobs and nothing else, dropped every staged photo without a log
 *    line. A callback is a constructor parameter; a callee built later is resolved when the callback runs.
 *  - **A defaulted function-typed constructor parameter** compiles green in a caller that forgot it. Three
 *    hosts copied the status screen's wiring table by hand over bundles whose every action defaulted to `{}`;
 *    one of them dropped "Choose more photos", and nothing said so. `mayCreate = { true }` and exclusion readers
 *    defaulting to nothing-excluded were the same shape on a safety gate.
 *
 * **Scope.** Production source: every Kotlin file under `domain/`, `adapter/`, `app/` and `ui/` outside a test
 * source set. The `:test:*` modules are test equipment and may carry defaults, which is where the test
 * builders live. Composable function types (`@Composable () -> Unit`, a content slot) are exempt from the
 * default rule. Ordinary function parameters are not constructor parameters and are not covered: a log
 * formatter's `result = { "" }` is not a seam.
 *
 * **What it cannot see.** It reads declarations. A seam typed as a named `fun interface` is not a function type
 * and passes both gates, which is the point: a named type states what it is. A callback smuggled in as a field
 * of some other object is not seen at all.
 */
class LambdaSeamShapeTest {

    private val productionRoots = listOf("/domain/", "/adapter/", "/app/", "/ui/")

    private fun isProduction(path: String): Boolean =
        productionRoots.any { path.startsWith(it) } && !Regex("""/src/(\w*[Tt]est)/""").containsMatchIn(path)

    private val production: List<SourceScan.Source> by lazy {
        SourceScan.kotlinFiles().filter { isProduction(it.path) }.also {
            assertTrue(
                it.size >= 200,
                "the seam-shape gates scanned only ${it.size} production files — the scope is broken and both " +
                    "gates are passing on nothing",
            )
        }
    }

    private fun code(source: SourceScan.Source) = ZoneGates.stripComments(source.text)

    @Test
    fun `no production source declares a function-typed var`() {
        val slots = production.flatMap { source ->
            KotlinDecls.varDecls(code(source))
                .filter { KotlinDecls.isFunctionType(it.type) }
                .map { "  ${source.path}:${it.line} :: var ${it.name}: ${it.type}" }
        }
        assertTrue(
            slots.isEmpty(),
            "a function-typed `var` is a callback slot someone must remember to fill (law \"Callbacks are bound " +
                "at construction\"). Take the callback as a constructor parameter; if its callee is built later, " +
                "have the callback resolve it when invoked (read a `lazy`). A callback that may be absent is a " +
                "nullable constructor parameter, stated at construction.\n" + slots.joinToString("\n"),
        )
    }

    /**
     * A `*Handlers` bundle is a slot of callbacks, so the slot rule reaches it too — with the one exception an event
     * port needs: an adapter's `listen` stores what the composition registered. So a `var` of a `*Handlers` type is
     * allowed only in a class that overrides `listen`, and nowhere else (`docs/architecture.md`, "Events arrive
     * through `listen`").
     */
    @Test
    fun `a Handlers-typed var lives only behind a listen`() {
        val found = production.flatMap { source ->
            val text = code(source)
            KotlinDecls.varDecls(text).filter { HANDLERS_TYPE.matches(it.type.trim()) }.map { Triple(source, text, it) }
        }
        assertTrue(found.isNotEmpty(), "no adapter stores its handlers — the scan is broken, and this gate passes on nothing")
        val slots = found.filterNot { (_, text, _) -> "override fun listen(" in text }
            .map { (source, _, decl) -> "  ${source.path}:${decl.line} :: var ${decl.name}: ${decl.type}" }
        assertTrue(
            slots.isEmpty(),
            "a `*Handlers` slot outside an event port's `listen` is a callback slot someone must remember to fill. " +
                "Register handlers only through `Listenable.listen`.\n" + slots.joinToString("\n"),
        )
    }

    @Test
    fun `no production constructor parameter defaults a function type`() {
        val defaulted = production.flatMap { source ->
            KotlinDecls.constructorParams(code(source))
                .filter { it.hasDefault && KotlinDecls.isFunctionType(it.type) && "@Composable" !in it.type }
                .map { "  ${source.path}:${it.line} :: ${it.owner}(${it.name}: ${it.type} = …)" }
        }
        assertTrue(
            defaulted.isEmpty(),
            "a defaulted function-typed constructor parameter lets a caller ship without wiring it (law " +
                "\"Function-typed parameters have no defaults in production\"). Make it required; a production " +
                "binding a test replaces belongs in a secondary constructor, and a test's inert default belongs " +
                "in a test builder.\n" + defaulted.joinToString("\n"),
        )
    }

    /**
     * Both gates fail open silently if the parser does: miss the arrow and every slot passes, mis-split a
     * constructor and every default does. Pinned on a sample so the property is checked while the tree is clean.
     */
    @Test
    fun `the declaration parser sees slots, defaults and function types`() {
        val sample = ZoneGates.stripComments(
            """
            /** Was `var ghost: () -> Unit = {}` once. */
            class Jobs(
                private val newTransport: (Host) -> Transport,
                private val onStaged: suspend (String, String) -> Unit = { _, _ -> },
                private val onEdit: (() -> Unit)? = null,
                private val content: @Composable () -> Unit = {},
                private val log: Logger = Logger.withTag("x"),
                private val retries: Int = 3,
            ) {
                var slot: (suspend (Int) -> Unit)? = null
                var count: Int = 0
                constructor(host: Host, mint: () -> String = { "id" }) : this({ Transport(it) })
            }
            """.trimIndent(),
        )
        val params = KotlinDecls.constructorParams(sample).associateBy { it.name }
        assertEquals(
            setOf("newTransport", "onStaged", "onEdit", "content", "log", "retries", "host", "mint"),
            params.keys,
            "the constructor scan lost or invented a parameter",
        )
        val flagged = params.values
            .filter { it.hasDefault && KotlinDecls.isFunctionType(it.type) && "@Composable" !in it.type }
            .map { it.name }.toSet()
        assertEquals(setOf("onStaged", "onEdit", "mint"), flagged, "the default rule flags the wrong parameters")

        val vars = KotlinDecls.varDecls(sample).filter { KotlinDecls.isFunctionType(it.type) }.map { it.name }
        assertEquals(listOf("slot"), vars, "the slot rule flags the wrong vars (and must ignore a commented one)")
    }

    private companion object {
        val HANDLERS_TYPE = Regex("""\w*Handlers\??""")
    }
}
