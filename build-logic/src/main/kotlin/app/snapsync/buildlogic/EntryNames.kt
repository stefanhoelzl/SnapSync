package app.snapsync.buildlogic

import org.objectweb.asm.ClassReader

/**
 * The entry-names gate's judgement (`app/android/proguard-rules.pro`), pure so it is tested without Gradle: an entry
 * point is named by its class (`EntryPoints.kt`: `this::class.simpleName`) and a tap by its intent's class, so on a
 * minified Android build every such class must reach the dex under its OWN name — neither renamed (`p13`) nor merged
 * into another class, whose name it would then answer.
 *
 * Which classes those are is read from the COMPILED classes R8 was given — every class below one of the roots — never
 * from the mapping: a class R8 merged away has no line of its own there, and a list read from the mapping would drop
 * exactly the class it should catch. Each is then judged by R8's mapping:
 *
 * - its own line maps it to itself: **kept**;
 * - its own line maps it to another name: **renamed**;
 * - no line of its own, but the mapping names it as the original owner of a method (an inlined or merged frame): its
 *   code survived inside another class — **merged**;
 * - no trace at all: R8 removed it as unused on this platform (an iOS-only entry point) — **removed**, and fine, since
 *   no code that could tell its name ships.
 *
 * A kept NESTED class (every tap intent: `UiIntent$ConfirmCreate`) needs one thing more, which the mapping cannot show:
 * its `simpleName` on Android is read from the dex's `InnerClass` annotation, and without it answers
 * `UiIntent$ConfirmCreate`. R8's full mode drops that annotation from classes it does not keep, whatever
 * `-keepattributes` says, so the dex itself is read ([innerNames], from `dexdump -a`): a kept nested class whose
 * annotation does not name it is **unnamed**.
 */
object EntryNames {

    /** A compiled class's place in the hierarchy, in the mapping's dotted form. */
    data class Shape(val name: String, val superName: String?, val interfaces: List<String>)

    data class Verdict(
        val kept: List<String>,
        val removed: List<String>,
        val renamed: List<Pair<String, String>>,
        val merged: List<String>,
        val unnamed: List<String>,
        /** Roots no compiled class declares: a renamed or moved base, which would leave the gate judging nothing. */
        val missingRoots: List<String>,
    ) {
        val passes: Boolean
            get() = renamed.isEmpty() && merged.isEmpty() && unnamed.isEmpty() && missingRoots.isEmpty()
    }

    fun shape(bytes: ByteArray): Shape {
        val reader = ClassReader(bytes)
        return Shape(dotted(reader.className), reader.superName?.let(::dotted), reader.interfaces.map(::dotted))
    }

    /** Every class of [shapes] below one of [roots] — a subclass or an implementation, at any depth — roots excluded. */
    fun subjects(shapes: Collection<Shape>, roots: Set<String>): Set<String> {
        val byName = shapes.associateBy { it.name }
        val below = mutableMapOf<String, Boolean>()
        fun reaches(name: String): Boolean = below.getOrPut(name) {
            val shape = byName[name] ?: return@getOrPut false
            (listOfNotNull(shape.superName) + shape.interfaces).any { it in roots || reaches(it) }
        }
        return shapes.map { it.name }.filter { it !in roots && reaches(it) }.toSortedSet()
    }

    /**
     * [subjects] judged by R8's [mapping] and the dex's [innerNames]; [declared] is every compiled class's name, to
     * catch a root that no longer exists.
     */
    fun judge(
        subjects: Set<String>,
        roots: Set<String>,
        declared: Set<String>,
        mapping: Sequence<String>,
        innerNames: Map<String, String>,
    ): Verdict {
        val mappedTo = mutableMapOf<String, String>()
        val owners = mutableSetOf<String>()
        mapping.filter { "app.snapsync." in it }.forEach { line ->
            if (!line.startsWith(" ")) {
                CLASS_LINE.matchEntire(line)?.let { mappedTo[it.groupValues[1]] = it.groupValues[2] }
            } else {
                QUALIFIED_MEMBER.findAll(line).forEach { owners += it.groupValues[1] }
            }
        }
        val kept = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val merged = mutableListOf<String>()
        subjects.sorted().forEach { name ->
            val to = mappedTo[name]
            when {
                to == name -> kept += name
                to != null -> renamed += name to to
                name in owners -> merged += name
                else -> removed += name
            }
        }
        val unnamed = kept.filter { '$' in it.substringAfterLast('.') && innerNames[it] != it.substringAfterLast('$') }
        return Verdict(kept, removed, renamed, merged, unnamed, (roots - declared).sorted())
    }

    /**
     * Each class's `InnerClass` annotation name, by dotted class name, from ONE dex's `dexdump -a` listing: a class's
     * annotations come as `Class #N annotations:` before its own `Class #N` block and its descriptor.
     */
    fun innerNames(dump: Sequence<String>): Map<String, String> {
        val annotated = mutableMapOf<String, String>()
        val described = mutableMapOf<String, String>()
        var annotating: String? = null
        var describing: String? = null
        dump.forEach { line ->
            ANNOTATIONS_OF.matchEntire(line)?.let { annotating = it.groupValues[1] }
            INNER_CLASS.find(line)?.let { match -> annotating?.let { annotated[it] = match.groupValues[1] } }
            CLASS_BLOCK.matchEntire(line)?.let { describing = it.groupValues[1] }
            DESCRIPTOR.find(line)?.let { match -> describing?.let { described[it] = dotted(match.groupValues[1]) } }
        }
        return described.entries.mapNotNull { (index, name) -> annotated[index]?.let { name to it } }.toMap()
    }

    private fun dotted(internal: String) = internal.replace('/', '.')

    /** `app.snapsync.entrypoints.OnForeground -> p13:` */
    private val CLASS_LINE = Regex("""(\S+) -> (\S+):""")

    /** A member line's qualified original owner: `14:19:void app.snapsync.entrypoints.OnTap.run(…)` names `OnTap`. */
    private val QUALIFIED_MEMBER = Regex("""([\w$]+(?:\.[\w$]+)+)\.[\w$<>]+\(""")

    private val ANNOTATIONS_OF = Regex("""Class #(\d+) annotations:""")
    private val INNER_CLASS = Regex("""Ldalvik/annotation/InnerClass;.* name="([^"]+)"""")
    private val CLASS_BLOCK = Regex("""Class #(\d+)\s+-""")
    private val DESCRIPTOR = Regex("""Class descriptor\s+: 'L([^;]+);'""")
}
