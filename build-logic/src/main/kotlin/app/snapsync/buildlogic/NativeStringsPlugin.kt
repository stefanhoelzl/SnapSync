package app.snapsync.buildlogic

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

/**
 * The text the OS shows outside the app's screens, generated from the ONE place every string lives
 * (`docs/architecture.md`, "Localization"): the screens' `composeResources/values/strings.xml` and its translations.
 *
 * A key `ios_<InfoPlistKey>` becomes that key in the app's `InfoPlist.xcstrings` (and its base value in
 * `Info.plist`); a key `android_<name>` becomes `<name>` in the Android adapter's `res/values/strings.xml` (and each translation's). The
 * locale list declares the languages the app ships — the first is the base, in `values/` — and becomes iOS's
 * `CFBundleLocalizations` and Android's `locales_config.xml`, so each OS offers exactly those languages.
 *
 * The outputs are COMMITTED, like the architecture diagrams: `./gradlew nativeStrings` writes them and
 * `nativeStringsCheck` (part of `check`) fails while one is stale, or while a module's translations and the locale
 * list disagree.
 */
class NativeStringsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("nativeStrings", NativeStringsExtension::class.java)
        val root = project.rootProject.layout.projectDirectory
        extension.strings.convention(project.layout.projectDirectory.dir("src/commonMain/composeResources"))
        extension.infoPlist.convention(root.file("iosApp/iosApp/Info.plist"))
        extension.xcstrings.convention(root.file("iosApp/iosApp/InfoPlist.xcstrings"))
        extension.androidRes.convention(root.dir("adapter/android/src/androidMain/res"))
        extension.localeConfig.convention(root.file("app/android/src/main/res/xml/locales_config.xml"))

        fun NativeStringsTask.wire() {
            locales.set(extension.locales)
            strings.set(extension.strings)
            otherResources.set(extension.otherResources)
            infoPlist.set(extension.infoPlist)
            xcstrings.set(extension.xcstrings)
            androidRes.set(extension.androidRes)
            localeConfig.set(extension.localeConfig)
            rootDir.set(project.rootProject.layout.projectDirectory)
        }
        project.tasks.register("nativeStrings", NativeStringsTask::class.java) {
            group = "build"
            description = "Writes the OS-shown strings and locale lists from the screens' strings.xml."
            write = true
            wire()
        }
        val check = project.tasks.register("nativeStringsCheck", NativeStringsTask::class.java) {
            group = "verification"
            description = "Fails while a generated OS string file is stale (run ./gradlew nativeStrings)."
            write = false
            wire()
        }
        project.tasks.matching { it.name == "check" }.configureEach { dependsOn(check) }
    }
}

/** See [NativeStringsPlugin]. */
abstract class NativeStringsExtension {
    /** The languages the app ships, as BCP 47 tags; the first is the base (`values/`). */
    abstract val locales: ListProperty<String>

    /** The `composeResources` directory the OS strings are read from. */
    abstract val strings: DirectoryProperty

    /** Other modules' `composeResources` directories, held to the same locale list. */
    abstract val otherResources: ListProperty<File>
    abstract val infoPlist: RegularFileProperty
    abstract val xcstrings: RegularFileProperty
    abstract val androidRes: DirectoryProperty
    abstract val localeConfig: RegularFileProperty
}

/** Generates (or, with [write] off, verifies) every output — see [NativeStringsPlugin]. */
abstract class NativeStringsTask : DefaultTask() {
    @get:Input
    var write: Boolean = false

    @get:Input
    abstract val locales: ListProperty<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val strings: DirectoryProperty

    @get:Input
    abstract val otherResources: ListProperty<File>

    // The outputs are also what the check READS, and they are committed files outside this project: tracked as
    // internal so Gradle neither caches the check away nor claims the paths as another task's outputs.
    @get:Internal
    abstract val infoPlist: RegularFileProperty

    @get:Internal
    abstract val xcstrings: RegularFileProperty

    @get:Internal
    abstract val androidRes: DirectoryProperty

    @get:Internal
    abstract val localeConfig: RegularFileProperty

    /** Where paths in a message are relative to. */
    @get:Internal
    abstract val rootDir: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val tags = locales.get()
        if (tags.isEmpty()) throw GradleException("nativeStrings: no locales declared")
        (listOf(strings.get().asFile) + otherResources.get()).forEach { checkLocales(it, tags) }
        val byLocale = tags.associateWith { readStrings(File(strings.get().asFile, "${valuesDir(it, tags)}/strings.xml")) }
        val base = byLocale.getValue(tags.first())

        val outputs = buildMap {
            put(xcstrings.get().asFile, xcstringsJson(tags, byLocale))
            put(infoPlist.get().asFile, infoPlistWith(infoPlist.get().asFile.readText(), tags, base))
            tags.forEach { tag ->
                val android = byLocale.getValue(tag).filterKeys { it.startsWith(ANDROID) }
                if (android.isNotEmpty() || tag == tags.first()) {
                    put(File(androidRes.get().asFile, "${valuesDir(tag, tags)}/strings.xml"), androidStrings(android))
                }
            }
            put(localeConfig.get().asFile, localeConfigXml(tags))
        }
        val stale = outputs.filter { (file, text) -> !file.exists() || file.readText() != text }
        if (write) {
            stale.forEach { (file, text) -> file.parentFile.mkdirs(); file.writeText(text) }
        } else if (stale.isNotEmpty()) {
            val root = rootDir.get().asFile
            throw GradleException(
                "Stale generated strings — run ./gradlew nativeStrings and commit:\n" +
                    stale.keys.joinToString("\n") { "  " + it.relativeTo(root) },
            )
        }
    }

    /** Every `values*` directory of [dir] is a declared locale, and every declared locale has one. */
    private fun checkLocales(dir: File, tags: List<String>) {
        val present = dir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values") }.map { it.name }.toSet()
        val declared = tags.map { valuesDir(it, tags) }.toSet()
        if (present != declared) {
            throw GradleException(
                "nativeStrings: ${dir.relativeTo(rootDir.get().asFile)} has ${present.sorted()}, but the locale list " +
                    "$tags declares ${declared.sorted()}. A translation and its declaration land together.",
            )
        }
    }

    private companion object {
        const val IOS = "ios_"
        const val ANDROID = "android_"

        /** `values` for the base, else Android's qualifier: `de` → `values-de`, `pt-BR` → `values-pt-rBR`. */
        fun valuesDir(tag: String, tags: List<String>): String {
            if (tag == tags.first()) return "values"
            val parts = tag.split('-')
            return "values-" + parts[0] + parts.drop(1).joinToString("") { "-r$it" }
        }

        fun readStrings(file: File): Map<String, String> {
            if (!file.exists()) return emptyMap()
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.documentElement.getElementsByTagName("string")
            return (0 until nodes.length).associate {
                val el = nodes.item(it) as Element
                el.getAttribute("name") to el.textContent
            }
        }

        fun json(s: String) = buildString {
            append('"')
            s.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    else -> append(c)
                }
            }
            append('"')
        }

        fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** The String Catalog Xcode compiles into each language's `InfoPlist.strings`. */
        fun xcstringsJson(tags: List<String>, byLocale: Map<String, Map<String, String>>): String {
            val keys = byLocale.values.flatMap { it.keys }.filter { it.startsWith(IOS) }.toSortedSet()
            val entries = keys.joinToString(",\n") { key ->
                val localizations = tags.mapNotNull { tag ->
                    byLocale.getValue(tag)[key]?.let { value ->
                        "        ${json(tag)} : {\n          \"stringUnit\" : {\n            \"state\" : \"translated\",\n" +
                            "            \"value\" : ${json(value)}\n          }\n        }"
                    }
                }.joinToString(",\n")
                "    ${json(key.removePrefix(IOS))} : {\n      \"extractionState\" : \"manual\",\n" +
                    "      \"localizations\" : {\n$localizations\n      }\n    }"
            }
            return "{\n  \"sourceLanguage\" : ${json(tags.first())},\n  \"strings\" : {\n$entries\n  },\n" +
                "  \"version\" : \"1.0\"\n}\n"
        }

        /** [plist] with each `ios_` key's base value and the `CFBundleLocalizations` array set. */
        fun infoPlistWith(plist: String, tags: List<String>, base: Map<String, String>): String {
            var out = plist
            base.filterKeys { it.startsWith(IOS) }.forEach { (key, value) ->
                val plistKey = key.removePrefix(IOS)
                val pattern = Regex("(<key>${Regex.escape(plistKey)}</key>\\s*<string>)(.*?)(</string>)", RegexOption.DOT_MATCHES_ALL)
                if (!pattern.containsMatchIn(out)) throw GradleException("Info.plist has no $plistKey for $key")
                out = pattern.replace(out) { it.groupValues[1] + xml(value) + it.groupValues[3] }
            }
            val array = "<key>CFBundleLocalizations</key>\n\t<array>\n" +
                tags.joinToString("") { "\t\t<string>$it</string>\n" } + "\t</array>"
            val existing = Regex("<key>CFBundleLocalizations</key>\\s*<array>.*?</array>", RegexOption.DOT_MATCHES_ALL)
            out = if (existing.containsMatchIn(out)) {
                existing.replace(out) { array }
            } else {
                val anchor = Regex("(<key>CFBundleDevelopmentRegion</key>\\s*<string>[^<]*</string>\n)")
                anchor.replace(out) { it.value + "\t" + array + "\n" }
            }
            return out
        }

        /** An Android string resource: its text escaped the way aapt reads it. */
        fun androidStrings(strings: Map<String, String>): String {
            fun escape(s: String) = xml(s).replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .let { if (it.startsWith("@") || it.startsWith("?")) "\\$it" else it }
            val body = strings.toSortedMap().entries.joinToString("") { (key, value) ->
                "    <string name=\"${key.removePrefix(ANDROID)}\">${escape(value)}</string>\n"
            }
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$GENERATED_XML\n<resources>\n$body</resources>\n"
        }

        fun localeConfigXml(tags: List<String>): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$GENERATED_XML\n" +
                "<locale-config xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                tags.joinToString("") { "    <locale android:name=\"$it\" />\n" } + "</locale-config>\n"

        const val GENERATED_XML =
            "<!-- GENERATED by ./gradlew nativeStrings from ui/screens' strings.xml (`docs/architecture.md`, " +
                "\"Localization\"). Do not edit. -->"
    }
}
