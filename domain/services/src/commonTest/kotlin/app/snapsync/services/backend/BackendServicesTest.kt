package app.snapsync.services.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Every need-shaped backend service sits over the ONE gateway, built on first use and kept. */
class BackendServicesTest {

    @Test
    fun `nothing reaches the network until a service is used, and each is built once`() {
        val transport = ScriptedTransport()
        val services = servicesOver(transport)
        val built = listOf(services.directory, services.rename, services.join, services.leave, services.push)
        val again = listOf(services.directory, services.rename, services.join, services.leave, services.push)
        built.zip(again).forEach { (first, second) -> assertSame(first, second, "a second read hands back the same") }
        assertEquals(emptyList(), transport.calls, "building the services sent nothing")
    }
}
