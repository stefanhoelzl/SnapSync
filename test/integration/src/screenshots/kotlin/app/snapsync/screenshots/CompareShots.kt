package app.snapsync.screenshots

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

/**
 * The PR gate over the marketing raws (`ci.yml`'s `screenshots`): a fresh capture against the committed set, so a change
 * that moves a listing image fails until its raws are committed with it.
 *
 *     CompareShots <captured dir> <committed dir> <diff dir> <ignored top rows>
 *
 * A pixel differs when one of its channels is off by more than [TOLERANCE]. That forgives the simulator's anti-aliasing
 * of the Dynamic Island's rim — 122 pixels, each at most 2/255, in 17 of 28 iOS runs over an unchanged UI (2026-07 to
 * 2026-10) — and Android's 1/255 on a handful. Android's status bar is left out by its rows: SystemUI's demo-mode clock
 * sits ~50 px further right after some emulator boots, in every shot of that run (4 of 10 runs). Each differing shot
 * gets an image in the diff dir: the capture dimmed, the differing pixels red.
 */
fun main(args: Array<String>) {
    if (args.size != 4) {
        System.err.println("usage: CompareShots <captured dir> <committed dir> <diff dir> <ignored top rows>")
        exitProcess(2)
    }
    val (captured, committed, diffs) = args.take(3).map(::File)
    val problems = compare(captured, committed, diffs.apply { mkdirs() }, args[3].toInt())
    problems.forEach { println("::error::$it") }
    if (problems.isEmpty()) println("every capture matches the committed raw in ${committed.path}")
    exitProcess(if (problems.isEmpty()) 0 else 1)
}

private fun compare(captured: File, committed: File, diffs: File, ignoredTop: Int): List<String> {
    val names = (captured.pngs() + committed.pngs()).toSortedSet()
    return names.mapNotNull { name ->
        val new = File(captured, name).takeIf { it.isFile }?.let(ImageIO::read)
        val old = File(committed, name).takeIf { it.isFile }?.let(ImageIO::read)
        when {
            new == null -> "$name is committed but was not captured"
            old == null -> "$name was captured but is not committed in ${committed.path}"
            new.width != old.width || new.height != old.height ->
                "$name is ${new.width}x${new.height}, the committed raw ${old.width}x${old.height}"
            else -> differing(new, old, ignoredTop, File(diffs, name))?.let { "$name: $it pixels differ from the committed raw" }
        }
    }
}

/** How many pixels below [ignoredTop] differ, or null if none; with a diff image at [to] when some do. */
private fun differing(new: BufferedImage, old: BufferedImage, ignoredTop: Int, to: File): Int? {
    val diff = BufferedImage(new.width, new.height, BufferedImage.TYPE_INT_RGB)
    var count = 0
    for (y in 0 until new.height) {
        for (x in 0 until new.width) {
            val a = new.getRGB(x, y)
            val off = y >= ignoredTop && CHANNELS.any { shift -> abs((a shr shift and 0xFF) - (old.getRGB(x, y) shr shift and 0xFF)) > TOLERANCE }
            if (off) count++
            diff.setRGB(x, y, if (off) RED else dimmed(a))
        }
    }
    if (count == 0) return null
    ImageIO.write(diff, "png", to)
    return count
}

private fun dimmed(argb: Int): Int = CHANNELS.fold(0) { rgb, shift -> rgb or ((argb shr shift and 0xFF) / 3 shl shift) }

private fun File.pngs(): List<String> = list { _, name -> name.endsWith(".png") }?.toList().orEmpty()

private const val TOLERANCE = 2
private const val RED = 0xFF0000
private val CHANNELS = listOf(0, 8, 16, 24)
