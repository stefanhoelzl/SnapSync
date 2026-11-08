package app.snapsync.tools.detekt

import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class ConstructorsTouchNoPortTest {

    @Test
    fun `a property or init block that reaches a constructor parameter as the object is built is reported`() {
        val findings = ConstructorsTouchNoPort().lint(
            """
            import app.snapsync.ports.Clock
            import app.snapsync.ports.Port

            class Store(private val port: Port, private val clock: Clock) {
                private val kind = port.kind
                private val now = clock.now()
                init { port.register() }
            }
            """.trimIndent(),
        )
        assertEquals(3, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `handing a parameter on, deferring it, or reading it later passes`() {
        val findings = ConstructorsTouchNoPort().lint(
            """
            import app.snapsync.ports.Port

            class Store(private val port: Port, other: Other) {
                private val graph = other.port.kind
                private val wrapped = Wrapper(port)
                private val later by lazy { port.kind }
                private val reader = { port.read() }
                private val handler = object : Handler { override fun on() = port.read() }
                private val state = MutableStateFlow(0)
                fun read() = port.read()
            }
            """.trimIndent(),
        )
        assertEquals(0, findings.size, findings.map { it.message }.toString())
    }

    @Test
    fun `reading a parameter's state or starting a flow at once as the object is built is reported`() {
        val findings = ConstructorsTouchNoPort().lint(
            """
            class Query(private val membership: Repository, keys: Store, scope: CoroutineScope) {
                private val seeded = membership.state.value
                val presence = combine(membership.state, keys.state, ::of).stateIn(scope, SharingStarted.Eagerly, null)
                val later = combine(membership.state, keys.state, ::of)
                    .stateIn(scope, SharingStarted.WhileSubscribed(), null)
                fun now() = membership.state.value
            }
            """.trimIndent(),
        )
        assertEquals(2, findings.size, findings.map { it.message }.toString())
    }
}
