package app.snapsync.architecture

import app.snapsync.levers.JoinedEvent
import app.snapsync.levers.LEVER_SERVICES
import app.snapsync.levers.StandsInFor
import app.snapsync.levers.ThisDevice
import app.snapsync.mock.MockDevice
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Every operator face a test can move is a typed service** (`docs/testing.md`, "Typed services").
 *
 * A face is the `operator` of one of [MockDevice]'s mocks — derived here, never listed. A face that has MIGRATED
 * implements a declared service's interface (`LEVER_SERVICES`), and then every public member it has is either a method
 * of that interface — so the channel serves it, and a test reaches it — or is named by a public-route method's
 * [StandsInFor] as `<Face>.<member>`, which reaches the same state through a surface every host serves — the backend's
 * public routes, or the operating system's deliveries. Nothing else excuses a member: a
 * lever the face grows without a method is a state no test can enter.
 *
 * The faces not yet migrated sit in [NOT_MIGRATED], which may only shrink: an entry for a face that has migrated, or
 * for no face at all, fails.
 */
@OptIn(ExperimentalRpcApi::class)
class LeverFacesTest {

    /** Every face, by its class's simple name. */
    private val faces: Map<String, KClass<*>> = MockDevice::class.memberProperties
        .filter { it.visibility == KVisibility.PUBLIC }
        .mapNotNull { mock ->
            (mock.returnType.classifier as? KClass<*>)?.memberProperties?.singleOrNull {
                it.name == "operator"
            }
        }
        .mapNotNull { it.returnType.classifier as? KClass<*> }
        .associateBy { it.simpleName!! }

    /** The interface of every declared service, by its fully-qualified name. */
    private val serviceInterfaces: Map<String, KClass<*>> =
        LEVER_SERVICES.associate { it.descriptor.fqName to Class.forName(it.descriptor.fqName).kotlin }

    /** The face members a public-route method stands in for, each as `<Face>.<member>`. */
    private val standIns: Map<String, String> = serviceInterfaces.values.flatMap { service ->
        service.memberFunctions.flatMap { method ->
            method.annotations.filterIsInstance<StandsInFor>().flatMap {
                it.members.toList()
            }.map { it to "${service.simpleName}.${method.name}" }
        }
    }.toMap()

    private fun migratedTo(face: KClass<*>): List<KClass<*>> =
        face.allSupertypes.mapNotNull { it.classifier as? KClass<*> }.filter { it.qualifiedName in serviceInterfaces }

    @Test
    fun `the faces are found`() {
        // Non-vacuity: the derivation found the mocks' faces, and the backend's among them.
        assertTrue(faces.size >= 20, "found only ${faces.keys}")
        assertTrue("BackendOperator" in faces, "found ${faces.keys}")
    }

    @Test
    fun `every public member of a migrated face is a method of its interface or stood in for`() {
        val problems = faces.values.filter { migratedTo(it).isNotEmpty() }.flatMap { face ->
            val methods = migratedTo(face).flatMap { service -> service.members.map { it.name } }.toSet()
            face.members
                .filter { it.visibility == KVisibility.PUBLIC && it.name !in ANY_MEMBERS }
                .map { it.name }
                .distinct()
                .filter { it !in methods && "${face.simpleName}.$it" !in standIns }
                .map {
                    "${face.simpleName}.$it is neither a method of ${migratedTo(
                        face,
                    ).map { s -> s.simpleName }} nor stood in for"
                }
        }
        assertEquals(
            emptyList(),
            problems,
            "A face member no test can reach through the channel: declare it on the face's interface (a lever answers " +
                "Unit, a read its value), or reach the same state through a public-route method that names it in " +
                "@StandsInFor, or make it non-public plumbing.",
        )
    }

    @Test
    fun `every stand-in names a member of a migrated face`() {
        val members = faces.values.filter {
            migratedTo(it).isNotEmpty()
        }.flatMap { face -> face.members.map { "${face.simpleName}.${it.name}" } }.toSet()
        assertEquals(
            emptyList(),
            standIns.keys.filter {
                it !in members
            }.map { "${standIns[it]} stands in for '$it', which no migrated face has" },
        )
    }

    @Test
    fun `the faces not yet migrated may only shrink`() {
        val migrated = faces.filterValues { migratedTo(it).isNotEmpty() }.keys
        val unmigrated = faces.keys - migrated
        assertEquals(
            emptyList(),
            (unmigrated - NOT_MIGRATED).sorted(),
            "a face no service declares is missing from NOT_MIGRATED",
        )
        assertEquals(
            emptyList(),
            (NOT_MIGRATED - unmigrated).sorted(),
            "NOT_MIGRATED lists a face that has migrated, or that no mock has: remove it — the list only shrinks",
        )
    }

    /**
     * The curl verb has only the interface's types, never its default values: a parameter may be omitted only when it is
     * nullable (and then arrives `null`, which the method gives a meaning), and the joined event and this device are
     * marked on a `String?`.
     */
    @Test
    fun `every omittable parameter is nullable`() {
        val problems = LEVER_SERVICES.flatMap { service ->
            service.descriptor.callables.values.flatMap { callable ->
                callable.parameters.mapNotNull { p ->
                    val where = "${service.name}.${callable.name}(${p.name})"
                    val marked = p.annotations.any { it is JoinedEvent || it is ThisDevice }
                    when {
                        p.isOptional && !p.type.kType.isMarkedNullable -> "$where has a default but is not nullable"
                        marked && p.type.kType != typeOf<String?>() -> "$where is marked as the joined event or this device but is no String?"
                        else -> null
                    }
                }
            }
        }
        assertEquals(emptyList(), problems)
    }

    /** A service the channel does not serve is one no test can reach: every `@Rpc` interface is declared. */
    @Test
    fun `every service interface is declared`() {
        val root = File(LEVER_SERVICES.first()::class.java.protectionDomain.codeSource.location.toURI())
        val stubs = if (root.isDirectory) {
            root.walkTopDown().filter {
                it.name.endsWith(
                    "\$\$rpcServiceStub.class",
                )
            }.map { it.relativeTo(root).path }.toList()
        } else {
            java.util.zip.ZipFile(root).use { zip ->
                zip.entries().asSequence().map {
                    it.name
                }.filter { it.endsWith("\$\$rpcServiceStub.class") }.toList()
            }
        }
        val declared = stubs.map { it.removeSuffix("\$\$rpcServiceStub.class").replace('/', '.') }.toSet()
        assertTrue(declared.isNotEmpty(), "found no @Rpc interface at $root")
        assertEquals(declared, serviceInterfaces.keys, "every @Rpc interface joins LEVER_SERVICES, and only those")
    }

    private companion object {
        val ANY_MEMBERS = setOf("equals", "hashCode", "toString")

        /** The faces that are not typed services yet (phases 3a–3f): the list only shrinks. */
        val NOT_MIGRATED = setOf(
            "ScreenOperator",
        )
    }
}
