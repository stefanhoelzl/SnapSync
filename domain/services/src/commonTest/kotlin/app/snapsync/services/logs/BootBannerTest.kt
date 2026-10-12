package app.snapsync.services.logs

import app.snapsync.model.Platform
import app.snapsync.model.StoreLink
import app.snapsync.ports.BuildInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/** The boot banner a process logs first, from its build's raw facts. */
class BootBannerTest {

    private fun build(processId: String?) = object : BuildInfo {
        override val appVersion = "1.4"
        override val buildNumber = "2512"
        override val uploadHost = "https://edge.example/api/v2"
        override val store: StoreLink? = null
        override val pushEnvironment = "production"
        override val osVersion = "Version 26.6.2 (Build 23G90)"
        override val deviceModel = "iPhone12,8"
        override val reporterEnvironment = "production"
        override val dsn: String? = null
        override val platform = Platform.IOS
        override val processId = processId
    }

    @Test
    fun `the banner names the process and its build, then the backend it talks to`() {
        assertEquals(
            listOf(
                "=== app.snapsync process start build=1.4(2512) ===",
                "[boot] upload base = https://edge.example/api/v2",
            ),
            build("app.snapsync").bootBanner(),
        )
    }

    @Test
    fun `a process with no id of its own is named by its platform`() {
        assertEquals("=== ios process start build=1.4(2512) ===", build(null).bootBanner().first())
    }
}
