@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.harness

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.snapsync.desktop.FORGE_HEIGHT
import app.snapsync.desktop.FORGE_WIDTH
import app.snapsync.desktop.ForgeHarnessRoot
import app.snapsync.desktop.PHONE_TAG
import app.snapsync.desktop.WORLD_HEIGHT
import app.snapsync.desktop.WORLD_WIDTH
import app.snapsync.desktop.WorldHarnessRoot
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Serves a desktop harness **headlessly** over HTTP so an agent can drive it turn by turn.
 *
 * It composes the shipped harness root — [ForgeHarnessRoot] or [WorldHarnessRoot] — into an offscreen
 * Compose scene. That scene is a CPU raster Skia surface: **no window, no AWT peer, no `Robot`**, so it
 * needs no X server and never raises the desktop's screen-capture consent prompt. Clicks land on the
 * real buttons of the real control panel, and pixels come out of the real render — the caller sees what
 * an operator would see, not a reconstruction.
 *
 * Dev infrastructure, non-gating, no spec (same posture as `ssh-mac.yml`). Runbook: see CLAUDE.md.
 *
 * ## Threading
 * Compose's test API is single-threaded: every interaction must run on the thread inside
 * [runDesktopComposeUiTest]'s block. HTTP handlers therefore never touch the scene — they enqueue a
 * [Command] and block on its future while the loop below executes it and completes the reply. This is
 * also why a request naturally means "settled": each command ends in `waitForIdle()` before answering.
 */
private const val IDLE_TIMEOUT_MINUTES = 30L
private const val REQUEST_TIMEOUT_SECONDS = 120L

private class Reply(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun text(s: String, status: Int = 200) = Reply(status, "text/plain; charset=utf-8", s.toByteArray())
        fun png(bytes: ByteArray) = Reply(200, "image/png", bytes)
    }
}

/** One scene interaction, handed from an HTTP thread to the scene thread. */
private class Command(val quit: Boolean = false, val run: DesktopComposeUiTest.() -> Reply) {
    val reply = CompletableFuture<Reply>()
}

private fun HttpExchange.query(): Map<String, String> =
    (requestURI.rawQuery ?: "")
        .split('&')
        .filter { it.isNotEmpty() }
        .associate { pair ->
            val i = pair.indexOf('=')
            fun dec(s: String) = URLDecoder.decode(s, Charsets.UTF_8)
            if (i < 0) dec(pair) to "" else dec(pair.take(i)) to dec(pair.substring(i + 1))
        }

private fun ImageBitmap.toPng(): ByteArray =
    Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes

/**
 * Resolves a node from the query: `tag=`, `desc=` (content description), or `text=` (button label),
 * plus `index=` and `substring=`.
 *
 * [index] is not a nicety: the world inspector renders one `✓` / `✕` / `Net` / `Http` / `Cxl` / `Unk`
 * per upload job, so those labels are ambiguous by construction and the caller must say which row.
 */
private fun DesktopComposeUiTest.select(q: Map<String, String>): SemanticsNodeInteraction {
    val substring = q["substring"].toBoolean()
    val matcher = when {
        q["tag"] != null -> hasTestTag(q.getValue("tag"))
        q["desc"] != null -> hasContentDescription(q.getValue("desc"), substring = substring)
        q["text"] != null -> hasText(q.getValue("text"), substring = substring)
        else -> error("need one of: text=, tag=, desc=")
    }
    val matches = onAllNodes(matcher)
    val count = matches.fetchSemanticsNodes().size
    val index = q["index"]?.toIntOrNull() ?: 0
    if (count == 0) error("no node matched $q")
    if (index >= count) error("index=$index out of range — $count node(s) matched $q")
    return matches[index]
}

fun main() {
    val name = System.getProperty("harness.name") ?: "forge"
    val portFile = File(System.getProperty("harness.portFile") ?: "harness-driver.port")
    val (width, height) = when (name) {
        // Match each harness's real window size. The scene default is 1024x768, which would clip the
        // world inspector (1240 wide) and silently truncate captures.
        "forge" -> FORGE_WIDTH to FORGE_HEIGHT
        "world" -> WORLD_WIDTH to WORLD_HEIGHT
        else -> error("unknown harness '$name' (expected: forge | world)")
    }

    val queue = LinkedBlockingQueue<Command>()
    // Port 0 = the OS picks a free port. Every CodeHydra workspace is its own worktree, so writing the
    // chosen port into THIS worktree's build dir makes concurrent sessions structurally collision-free.
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun route(path: String, quit: Boolean = false, build: (Map<String, String>) -> DesktopComposeUiTest.() -> Reply) {
        server.createContext(path) { exchange ->
            val reply = try {
                val command = Command(quit = quit, run = build(exchange.query()))
                queue.put(command)
                command.reply.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                Reply.text("error: ${t.message ?: t}\n", 400)
            }
            exchange.responseHeaders.add("Content-Type", reply.contentType)
            exchange.sendResponseHeaders(reply.status, reply.body.size.toLong())
            exchange.responseBody.use { it.write(reply.body) }
        }
    }

    route("/health") { { Reply.text("harness=$name scene=${width}x$height\n") } }

    // Default to the phone pane: the whole-window tree is ~14x larger and mostly inspector chrome the
    // caller already knows. `?scope=all` when you need to find a control's exact label.
    route("/tree") { q ->
        {
            val node = if (q["scope"] == "all") onRoot() else onNodeWithTag(PHONE_TAG)
            Reply.text(node.printToString(maxDepth = Int.MAX_VALUE))
        }
    }

    route("/click") { q ->
        {
            val node = select(q)
            // Both panels are scrollable columns, so a control below the viewport is not clickable
            // until scrolled to. Harmless where there is no scrollable ancestor.
            runCatching { node.performScrollTo() }
            node.performClick()
            waitForIdle()
            Reply.text("ok\n")
        }
    }

    route("/input") { q ->
        {
            val value = q["value"] ?: error("need value= (the text to type)")
            val node = select(q)
            runCatching { node.performScrollTo() }
            node.performTextInput(value)
            waitForIdle()
            Reply.text("ok\n")
        }
    }

    route("/phone.png") { { Reply.png(onNodeWithTag(PHONE_TAG).captureToImage().toPng()) } }
    route("/shot.png") { { Reply.png(onRoot().captureToImage().toPng()) } }
    route("/quit", quit = true) { { Reply.text("bye\n") } }

    runDesktopComposeUiTest(width, height) {
        setContent { if (name == "forge") ForgeHarnessRoot() else WorldHarnessRoot() }
        waitForIdle()

        server.executor = null // handlers just enqueue and wait; the scene thread does the work.
        server.start()
        val port = server.address.port
        portFile.parentFile?.mkdirs()
        portFile.writeText(port.toString())
        println("harness-driver: $name on http://127.0.0.1:$port (port file: ${portFile.absolutePath})")

        while (true) {
            // An orphaned session dies on its own rather than holding a port and a JVM forever.
            val command = queue.poll(IDLE_TIMEOUT_MINUTES, TimeUnit.MINUTES) ?: run {
                println("harness-driver: idle ${IDLE_TIMEOUT_MINUTES}m — exiting")
                null
            } ?: break
            val reply = try {
                command.run(this)
            } catch (t: Throwable) {
                Reply.text("error: ${t.message ?: t}\n", 400)
            }
            command.reply.complete(reply)
            if (command.quit) break
        }

        server.stop(1) // let the in-flight /quit response flush.
        portFile.delete()
    }
    // Compose/Skiko leave non-daemon threads behind; don't hang the Gradle worker.
    exitProcess(0)
}
