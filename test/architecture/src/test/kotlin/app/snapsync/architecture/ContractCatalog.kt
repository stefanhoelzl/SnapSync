package app.snapsync.architecture

import app.snapsync.contracts.Contract
import java.io.File
import java.util.jar.JarFile
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes
import kotlin.test.assertTrue

/**
 * **Every port contract, loaded as the value it is** (`docs/testing.md`, "Every clause declares the cells it covers").
 *
 * The contracts are read from `:test:contracts`' compiled JVM classes, not from their source: a clause's id may be
 * computed (the schema clauses), and its `covers` is rendered by the same common code every host runs, so reading
 * the VALUE is the only reading that sees what a run sees. Scope is derived — every `object` in the contracts package
 * extending [Contract] — never listed, so a new contract is in the catalog the day it compiles.
 *
 * The one `Contract` not here is the extension's single-clause re-wrap of `UploadContract` in an iOS rig source set,
 * which carries `UploadContract`'s own clauses and adds none.
 */
internal object ContractCatalog {

    class ClauseDecl(val id: String, val state: String, val covers: List<String>)

    class ContractDecl(val name: String, val objectName: String, val stateEnum: String, val clauses: List<ClauseDecl>)

    val contracts: List<ContractDecl> by lazy {
        contractObjects().map { contract ->
            val state = contract::class.allSupertypes.single { it.classifier == Contract::class }
                .arguments.first().type!!.classifier as KClass<*>
            ContractDecl(
                name = contract.name,
                objectName = contract::class.simpleName!!,
                stateEnum = state.simpleName!!,
                clauses = contract.clauses.map { ClauseDecl(it.id, it.state.name, it.covers) },
            )
        }.sortedBy { it.name }
    }

    private fun contractObjects(): List<Contract<*, *>> {
        val root = File(Contract::class.java.protectionDomain.codeSource.location.toURI())
        val prefix = Contract::class.java.packageName.replace('.', '/') + "/"
        val names = if (root.isDirectory) {
            root.walk().filter { it.isFile }.map { it.toRelativeString(root).replace('\\', '/') }.toList()
        } else {
            JarFile(root).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
        }
        val loader = Contract::class.java.classLoader
        val found = names.filter { it.startsWith(prefix) && it.endsWith(".class") && '$' !in it }
            .map { Class.forName(it.removeSuffix(".class").replace('/', '.'), false, loader) }
            .filter { Contract::class.java.isAssignableFrom(it) }
            .mapNotNull { it.kotlin.objectInstance as Contract<*, *>? }
        assertTrue(found.isNotEmpty(), "no Contract object found under $root — the contracts' classes moved")
        return found
    }
}
