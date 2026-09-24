package app.snapsync.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **ObjC boundaries contain every throw** (capability `module-architecture`, law "ObjC boundaries contain every
 * throw"; decision record `harden-seam-bug-classes`, D9).
 *
 * Two directions, one gate:
 *
 *  - **Inbound.** A Kotlin exception that unwinds into Objective-C frames terminates the process (B10: a SQLite
 *    write failing inside a PhotoKit change block). So every body ObjC calls — a delegate method of an
 *    `NSObject()` subclass, a block handed to a block-taking API, a completion that resumes a coroutine — must
 *    begin with `objcBoundary(`.
 *  - **Outbound.** An ObjC call's `Boolean`/`NSError**` failure must be read, not dropped (B8: a refused
 *    `submitTaskRequest` left no trace). So `error = null` is refused, and the `NSError**` out-parameter is
 *    allocated only inside `checkedObjC`.
 *
 * Scope: the hand-written `iosMain` source of the iOS adapters and shells. Test and rig source is out — it never
 * ships.
 *
 * **Heuristic, and it says so.** It reads text, not a call graph:
 *  - a block-taking ObjC API is recognised by NAME from [blockApis]; one that is not on the list is caught only if
 *    its block resumes a coroutine (the `cont.resume` rule), which is the shape every such use in this tree has;
 *  - an error-reporting selector is recognised by NAME from [errorSelectors]; a new one is seen only when passed
 *    `error = null` by name — a positional `null` to an unlisted selector is not seen;
 *  - a string literal holding an unbalanced brace would confuse the block walk (none exists; strings are blanked
 *    first, templates included).
 */
class ObjCBoundaryGateTest {

    private val helperFile = "/adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/objc/ObjCBoundary.kt"

    /** ObjC APIs this tree hands Kotlin blocks to. Every `{ … }` among their arguments must start at the boundary. */
    private val blockApis = listOf(
        "dispatch_async", "addObserverForName", "performChanges", "performChangesAndWait",
        "requestAuthorizationForAccessLevel", "getAllTasksWithCompletionHandler", "generateKeyWithCompletionHandler",
        "attestKey", "generateAssertion", "writeDataForAssetResource", "beginBackgroundTaskWithName",
    )

    /**
     * ObjC selectors this tree calls that end in `error:` — they report failure through `Boolean`/`nil` plus an
     * `NSError**`, so each call must sit inside `checkedObjC`/`checkedObjCValue`, which reads it.
     */
    private val errorSelectors = listOf(
        "submitTaskRequest", "performChangesAndWait", "setUploadJobExtensionEnabled", "removeItemAtPath",
        "removeItemAtURL", "moveItemAtPath", "moveItemAtURL", "createDirectoryAtPath", "createDirectoryAtURL",
        "setAttributes", "attributesOfItemAtPath", "dataWithContentsOfFile", "writeToFile",
    )

    private fun inScope(path: String) =
        (path.startsWith("/adapter/ios/") || path.startsWith("/app/ios/")) && "/src/iosMain/" in path

    private fun line(code: String, at: Int) = code.take(at).count { it == '\n' } + 1

    /** Every violation in [raw] (a whole file's text). */
    internal fun offenders(raw: String, isHelper: Boolean = false): List<String> {
        val code = blankStrings(ZoneGates.stripComments(raw))
        val out = mutableListOf<String>()
        Regex("""\berror\s*=\s*null\b""").findAll(code).forEach {
            out += "${line(code, it.range.first)}: `error = null` drops the call's failure — use checkedObjC"
        }
        if (!isHelper) {
            Regex("""alloc<\s*ObjCObjectVar<\s*NSError\?\s*>\s*>""").findAll(code).forEach {
                out += "${line(code, it.range.first)}: a hand-allocated NSError** — use checkedObjC"
            }
        }
        out += delegateOffenders(code)
        out += blockApiOffenders(code)
        out += resumeOffenders(code)
        out += uncheckedOffenders(code)
        return out
    }

    /** Every `override fun` inside a class extending `NSObject()` must be `= objcBoundary(` or `{ objcBoundary(`. */
    private fun delegateOffenders(code: String): List<String> =
        Regex("""\bclass\s+(\w+)[^{]*?:\s*NSObject\(\)""").findAll(code).flatMap { cls ->
            val open = code.indexOf('{', cls.range.last)
            if (open < 0) return@flatMap emptySequence()
            val close = closing(code, open)
            Regex("""\boverride\s+fun\s+(\w+)\s*\(""").findAll(code.substring(open, close)).mapNotNull { fn ->
                val at = open + fn.range.first
                val paramsOpen = open + fn.range.last
                var i = closingParen(code, paramsOpen) + 1
                // Skip a declared return type up to the body.
                while (i < code.length && code[i] != '=' && code[i] != '{') i++
                val bodyStart = code.substring(i + 1).trimStart()
                if (bodyStart.startsWith("objcBoundary(")) {
                    null
                } else {
                    "${line(code, at)}: ${cls.groupValues[1]}.${fn.groupValues[1]} is called by ObjC and does not " +
                        "start at objcBoundary"
                }
            }
        }.toList()

    /** Every `{ … }` argument of a [blockApis] call must begin with `objcBoundary(` (after its parameter list). */
    private fun blockApiOffenders(code: String): List<String> =
        Regex("""\b(${blockApis.joinToString("|")})\s*\(""").findAll(code)
            .filter { m -> !Regex("""fun\s+$""").containsMatchIn(code.take(m.range.first)) }
            .flatMap { m ->
                val open = m.range.last
                val close = closingParen(code, open)
                val blocks = topLevelBlocks(code, open + 1, close).toMutableList()
                // A trailing lambda after the argument list.
                val after = code.substring(close + 1).trimStart()
                if (after.startsWith("{")) blocks += code.indexOf('{', close + 1)
                blocks.filterNot { startsAtBoundary(code, it) }.map {
                    "${line(code, it)}: a block handed to ${m.groupValues[1]} does not start at objcBoundary"
                }.asSequence()
            }.toList()

    /** A `cont.resume…` must sit inside an `objcBoundary(…) { … }` block: its caller is an ObjC completion. */
    private fun resumeOffenders(code: String): List<String> {
        val guarded = blocksOf(code, "objcBoundary")
        return Regex("""\bcont\.resume(?:WithException)?\(""").findAll(code)
            .filter { m -> guarded.none { m.range.first in it } }
            .map { "${line(code, it.range.first)}: a completion resumes a coroutine outside objcBoundary" }
            .toList()
    }

    /** A call to an [errorSelectors] selector must sit inside a `checkedObjC`/`checkedObjCValue` block. */
    private fun uncheckedOffenders(code: String): List<String> {
        val checked = blocksOf(code, "checkedObjC") + blocksOf(code, "checkedObjCValue")
        return Regex("""\b(${errorSelectors.joinToString("|")})\s*\(""").findAll(code)
            .filter { m -> !Regex("""fun\s+$""").containsMatchIn(code.take(m.range.first)) }
            .filter { m -> checked.none { m.range.first in it } }
            .map { "${line(code, it.range.first)}: ${it.groupValues[1]} reports failure through NSError** — call it inside checkedObjC" }
            .toList()
    }

    /** The body ranges of every `helper(…) { … }` call in [code] (the block inside the parens, or trailing them). */
    private fun blocksOf(code: String, helper: String): List<IntRange> =
        Regex("""\b$helper\(""").findAll(code).mapNotNull { m ->
            val closeParen = closingParen(code, m.range.last)
            val brace = code.indexOf('{', closeParen)
            val inline = code.indexOf('{', m.range.last).takeIf { it in 0 until closeParen }
            val open = inline ?: brace.takeIf { it >= 0 && code.substring(closeParen + 1, it).isBlank() } ?: return@mapNotNull null
            open..closing(code, open)
        }.toList()

    private fun startsAtBoundary(code: String, open: Int): Boolean {
        var body = code.substring(open + 1, closing(code, open)).trimStart()
        // Drop a lambda parameter list: `a, b ->` or `_: NSNotification? ->`.
        Regex("""^[\w\s,:?<>_]*->""").find(body)?.let { body = body.substring(it.range.last + 1).trimStart() }
        return body.startsWith("objcBoundary(")
    }

    /** Offsets of the `{` of each block at depth 0 of the argument list between [from] and [to]. */
    private fun topLevelBlocks(code: String, from: Int, to: Int): List<Int> {
        val out = mutableListOf<Int>()
        var i = from
        var parens = 0
        while (i < to) {
            when (code[i]) {
                '(' -> parens++
                ')' -> parens--
                '{' -> { if (parens == 0) out += i; i = closing(code, i) }
            }
            i++
        }
        return out
    }

    private fun closing(code: String, open: Int): Int = matching(code, open, '{', '}')
    private fun closingParen(code: String, open: Int): Int = matching(code, open, '(', ')')

    private fun matching(code: String, open: Int, up: Char, down: Char): Int {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                up -> depth++
                down -> { depth--; if (depth == 0) return i }
            }
        }
        return code.length - 1
    }

    /** [code] with every string literal's content blanked — templates included — so a brace in one is invisible. */
    private fun blankStrings(code: String): String {
        val out = StringBuilder(code)
        var i = 0
        while (i < code.length) {
            if (code[i] == '"') {
                val triple = code.startsWith("\"\"\"", i)
                val quote = if (triple) "\"\"\"" else "\""
                var j = i + quote.length
                var template = 0
                while (j < code.length) {
                    when {
                        template == 0 && !triple && code[j] == '\\' -> j++
                        template == 0 && code.startsWith(quote, j) -> break
                        code.startsWith("\${", j) -> { template++; j++ }
                        template > 0 && code[j] == '}' -> template--
                    }
                    j++
                }
                for (k in i + quote.length until minOf(j, code.length)) if (out[k] != '\n') out.setCharAt(k, ' ')
                i = j + quote.length
            } else {
                i++
            }
        }
        return out.toString()
    }

    @Test
    fun `every ObjC crossing goes through the boundary helpers`() {
        val files = SourceScan.kotlinFiles().filter { inScope(it.path) }
        assertTrue(files.size >= 40, "the ObjC-boundary gate scanned only ${files.size} files — the scope is broken")
        assertTrue(files.any { it.path == helperFile }, "the ObjC-boundary helpers moved — re-point $helperFile")
        val found = files.flatMap { f -> offenders(f.text, isHelper = f.path == helperFile).map { "  ${f.path}:$it" } }
        assertTrue(
            found.isEmpty(),
            "these Kotlin/ObjC crossings are unguarded (law \"ObjC boundaries contain every throw\"). A body ObjC " +
                "calls starts with `objcBoundary(log, name) { … }`, because a throw that unwinds into ObjC kills " +
                "the process; a call reporting failure through `Boolean`/`NSError**` goes through " +
                "`checkedObjC`/`checkedObjCValue`, and its failure is handled or visibly dropped.\n" +
                found.joinToString("\n"),
        )
    }

    @Test
    fun `the scan sees each unguarded shape and passes a guarded one`() {
        val bad = """
            class Delegate : NSObject(), NSURLSessionDelegateProtocol {
                override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) {
                    host.onInvalidated()
                }
            }
            fun a() {
                fm.removeItemAtPath(path, error = null)
                memScoped { val e = alloc<ObjCObjectVar<NSError?>>() }
                dispatch_async(dispatch_get_main_queue()) { open("{") }
                PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(changeBlock = { write() }, error = it)
                center.addObserverForName(name = n, `object` = null, queue = q, usingBlock = { onForeground() })
                service.somethingNew { data, _ -> cont.resume(data) }
                BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null)
            }
        """.trimIndent()
        // 7 unguarded shapes, plus 3 error-reporting calls outside checkedObjC (remove, performChangesAndWait, submit).
        assertEquals(10, offenders(bad).size, "the scan missed a shape: ${offenders(bad)}")

        val good = """
            class Delegate : NSObject(), NSURLSessionDelegateProtocol {
                override fun URLSession(session: NSURLSession, didBecomeInvalidWithError: NSError?) =
                    objcBoundary(log, "invalid") { host.onInvalidated() }
                override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
                    objcBoundary(log, "finished") { host.done() }
                }
            }
            fun a() {
                checkedObjC("remove") { fm.removeItemAtPath(path, error = it) }
                dispatch_async(dispatch_get_main_queue()) { objcBoundary(log, "open") { open("${'$'}{x}") } }
                checkedObjC("c") { e -> library.performChangesAndWait(changeBlock = { objcBoundary(log, "c") { write() } }, error = e) }
                center.addObserverForName(name = n, `object` = null, queue = q, usingBlock = { objcBoundary(log, "f") { f() } })
                PHPhotoLibrary.requestAuthorizationForAccessLevel(level) { _ -> objcBoundary(log, "r") { read() } }
                service.somethingNew { data, _ -> objcBoundary(log, "n") { cont.resume(data) } }
            }
        """.trimIndent()
        assertEquals(emptyList(), offenders(good), "the scan flagged a guarded crossing")
    }
}
