package app.snapsync.buildlogic

import org.gradle.api.logging.Logger
import java.io.File

/**
 * A generated file that is COMMITTED (`docs/architecture.md`, "Generated files are written by the build"): written by
 * `build`, only when its text changed — and said so, so a local build that brought one up to date tells, and the
 * change is committed rather than discovered by CI's clean-tree check.
 */
internal object Committed {
    fun write(file: File, text: String, logger: Logger, root: File) {
        if (file.isFile && file.readText(Charsets.UTF_8) == text) return
        file.parentFile.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        logger.lifecycle("rewrote ${file.relativeTo(root).invariantSeparatorsPath}: commit it")
    }
}
