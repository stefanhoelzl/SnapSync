package app.snapsync.tools.diagrams

import java.io.File

/**
 * All source-scan diagrams (everything except the Gradle-model-backed `modules.md` and its
 * sidecar), as repo-relative path → content. Pure function of the source tree — this is what the
 * freshness test regenerates in-process and compares against the committed files.
 */
fun generateSourceDiagrams(root: File): Map<String, String> {
    val sources = kotlinSources(root)
    return buildMap {
        put("architecture/zones.md", zonesMarkdown(root))
        put("architecture/ports.md", portsMarkdown(root, sources))
        put("architecture/features.md", featuresMarkdown(root, sources))
        put("architecture/di.md", diMarkdown(sources))
        putAll(flowsMarkdown(sources))
    }
}

/**
 * The `:tools:diagrams:generate` entry point (run by `./gradlew architectureDiagrams`): write the
 * source-scan diagrams under `architecture/` and prune flow files whose trigger no longer exists,
 * so a removed trigger cannot leave a stale committed flow behind.
 */
fun main(args: Array<String>) {
    val root = if (args.isNotEmpty()) File(args[0]) else repoRoot()
    val generated = generateSourceDiagrams(root)
    for ((rel, content) in generated.toSortedMap(compareBy { it })) {
        val file = File(root, rel)
        file.parentFile.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }
    File(root, "architecture/flows").listFiles()
        ?.filter { it.isFile && it.extension == "md" && "architecture/flows/${it.name}" !in generated }
        ?.forEach { it.delete() }
    println("wrote ${generated.size} source-scan diagram file(s) under architecture/")
}
