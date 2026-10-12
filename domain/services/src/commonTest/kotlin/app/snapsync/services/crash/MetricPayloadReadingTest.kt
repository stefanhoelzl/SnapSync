package app.snapsync.services.crash

import app.snapsync.ports.MetricNode
import app.snapsync.ports.ProcessMetricPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A delivered payload read as dotted keys — and a call-stack branch never read at all. */
class MetricPayloadReadingTest {

    /** [value] — maps, lists and leaves — as the lazy view a platform hands over. */
    private fun node(value: Any?): MetricNode? = when (value) {
        null -> null
        is Map<*, *> -> object : MetricNode.Branch {
            override val keys = value.keys.map { it.toString() }
            override fun child(key: String) = node(value[key])
        }
        is List<*> -> object : MetricNode.Items {
            override val size = value.size
            override fun item(index: Int) = node(value[index])
        }
        else -> MetricNode.Leaf(value.toString())
    }

    private fun read(value: Map<String, Any?>) = ProcessMetricPayload(node(value)!!).report().fields

    @Test
    fun `nested branches become dotted keys and an empty one contributes none`() {
        val flat = read(
            mapOf(
                "applicationExitMetrics" to mapOf(
                    "foregroundExitData" to mapOf("cumulativeAbnormalExitCount" to 6),
                    "backgroundExitData" to emptyMap<String, Any?>(),
                ),
                "appVersion" to "0.1",
            ),
        )
        assertEquals("6", flat["applicationExitMetrics.foregroundExitData.cumulativeAbnormalExitCount"])
        assertEquals("0.1", flat["appVersion"])
        assertTrue(flat.keys.none { it.startsWith("applicationExitMetrics.backgroundExitData") })
    }

    @Test
    fun `items are keyed by position and nesting is unbounded`() {
        val flat = read(mapOf("a" to listOf(mapOf("b" to mapOf("c" to 1)), "x")))
        assertEquals("1", flat["a.0.b.c"])
        assertEquals("x", flat["a.1"])
    }

    @Test
    fun `an empty key adds no separator and a slot holding nothing contributes no key`() {
        assertEquals(mapOf("a" to "1"), read(mapOf("" to mapOf("a" to 1))))
        val flat = read(mapOf("terminationReason" to null, "signal" to 6, "items" to listOf(null, 2)))
        assertEquals(mapOf("signal" to "6", "items.1" to "2"), flat)
    }

    @Test
    fun `a call stack tree is skipped at any depth - the root included - without being read`() {
        val unread = object : MetricNode.Branch {
            override val keys = listOf("callStacks")
            override fun child(key: String): MetricNode? = fail("a call-stack branch was read")
        }
        val diagnostic = object : MetricNode.Branch {
            override val keys = listOf("callStackTree", "diagnosticMetaData")
            override fun child(key: String) = if (key == "callStackTree") unread else node(mapOf("signal" to 6))
        }
        val root = object : MetricNode.Branch {
            override val keys = listOf("callStackTree", "crashDiagnostics")
            override fun child(key: String): MetricNode? = when (key) {
                "callStackTree" -> unread
                else -> object : MetricNode.Items {
                    override val size = 1
                    override fun item(index: Int) = diagnostic
                }
            }
        }
        assertEquals(
            mapOf("crashDiagnostics.0.diagnosticMetaData.signal" to "6"),
            ProcessMetricPayload(root).report().fields,
        )
    }
}
