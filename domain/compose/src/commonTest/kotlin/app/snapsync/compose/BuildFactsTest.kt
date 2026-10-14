package app.snapsync.compose

import app.snapsync.model.Platform
import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState
import app.snapsync.model.Store
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.ExtensionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the composition makes of the build's raw facts: the build a dump names. */
class BuildFactsTest {

    private fun build(processId: String?) = object : BuildInfo {
        override val appVersion = "1.4"
        override val buildNumber = "2512"
        override val uploadHost = "https://edge.example/api/v2"
        override val store: Store? = null
        override val pushEnvironment = "production"
        override val osVersion = "Version 26.6.2 (Build 23G90)"
        override val deviceModel = "iPhone12,8"
        override val reporterEnvironment = "production"
        override val dsn: String? = null
        override val platform = Platform.IOS
        override val processId = processId
    }

    private fun registry(supported: Boolean) = object : ExtensionRegistry {
        override val supported = supported
        override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer = RegistrationAnswer.Unsupported
        override fun isEnabled(): RegistrationState = RegistrationState.UNSUPPORTED
    }

    @Test
    fun `a dump names the build and the uploaders the OS carries`() {
        val carried = diagnosticEnvironment(build("app.snapsync"), registry(supported = true))
        assertEquals(
            listOf(
                "1.4",
                "2512",
                "Version 26.6.2 (Build 23G90)",
                "iPhone12,8",
                "app+extension",
                "https://edge.example/api/v2",
                "production",
            ),
            with(carried) {
                listOf(appVersion, buildNumber, osVersion, deviceModel, uploadTier, uploadBase, reporterEnvironment)
            },
        )
        assertEquals("app", diagnosticEnvironment(build("app.snapsync"), registry(supported = false)).uploadTier)
    }
}
