package app.snapsync.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/**
 * [EntryNames]' judgement of a minified release, on `check`: the classes R8 was given (every module's, the variant's
 * `CLASSES` over all scopes), its mapping, and the dex of the APK it produced, read with the SDK's `dexdump`.
 */
abstract class EntryNamesTask : DefaultTask() {
    /** The entry-point bases and the tap intents' interface: every class below one is named by its class. */
    @get:Input
    abstract val roots: SetProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directories: ListProperty<Directory>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apk: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dexdump: RegularFileProperty

    @get:OutputFile
    abstract val verdict: RegularFileProperty

    @TaskAction
    fun judge() {
        val shapes = classBytes().map(EntryNames::shape).toList()
        val roots = roots.get()
        val subjects = EntryNames.subjects(shapes, roots)
        val v = mapping.get().asFile.useLines { lines ->
            EntryNames.judge(subjects, roots, shapes.map { it.name }.toSet(), lines, innerNames())
        }
        val summary = "${v.kept.size} entry/intent class(es) keep their names; ${v.removed.size} removed as unused " +
            "on Android"
        val detail = v.removed.map { "  removed  $it" } +
            v.renamed.map { (from, to) -> "  RENAMED  $from -> $to" } +
            v.merged.map { "  MERGED   $it (into another class, whose name it would answer)" } +
            v.unnamed.map { "  UNNAMED  $it (no InnerClass annotation naming it: simpleName answers the binary name)" } +
            v.missingRoots.map { "  NO ROOT  $it (no compiled class declares it)" }
        logger.lifecycle(summary)
        verdict.get().asFile.writeText((listOf(summary) + detail).joinToString("\n", postfix = "\n"))
        if (!v.passes) {
            throw GradleException(
                "an entry point or tap intent would not tell its own name in this release " +
                    "(`app/android/proguard-rules.pro`):\n" + detail.filterNot { it.startsWith("  removed") }
                    .joinToString("\n"),
            )
        }
    }

    /** The app's own classes R8 was given; nothing outside `app.snapsync` can be an entry point or an intent. */
    private fun classBytes(): Sequence<ByteArray> {
        val fromJars = jars.get().asSequence().flatMap { jar ->
            ZipFile(jar.asFile).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith(OWN) && it.name.endsWith(".class") }
                    .map { entry -> zip.getInputStream(entry).use { it.readBytes() } }
                    .toList()
            }
        }
        val fromDirectories = directories.get().asSequence().flatMap { root ->
            root.asFile.resolve(OWN).walkTopDown().filter { it.extension == "class" }.map { it.readBytes() }
        }
        return fromJars + fromDirectories
    }

    /** Every dex of the APK, listed by `dexdump -a`: the `InnerClass` names it carries. */
    private fun innerNames(): Map<String, String> {
        val apkFile = apk.get().asFile.walkTopDown().single { it.extension == "apk" }
        val scratch = temporaryDir.apply { listFiles()?.forEach(File::delete) }
        return ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().filter { DEX.matches(it.name) }.toList().flatMap { entry ->
                val dex = scratch.resolve(entry.name)
                zip.getInputStream(entry).use { input -> dex.outputStream().use { input.copyTo(it) } }
                val process = ProcessBuilder(dexdump.get().asFile.path, "-a", "-l", "plain", dex.path)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                val names = process.inputStream.bufferedReader().useLines { EntryNames.innerNames(it).toList() }
                check(process.waitFor() == 0) { "dexdump failed on ${entry.name}" }
                names
            }.toMap()
        }
    }

    private companion object {
        const val OWN = "app/snapsync/"
        val DEX = Regex("""classes\d*\.dex""")
    }
}
