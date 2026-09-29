package app.snapsync.launchadapters

import app.snapsync.compose.DevicePorts

import app.snapsync.mock.ExtensionHostMock
import app.snapsync.mock.FileSystemMock
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.model.CycleResult
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What a process launches with (`docs/testing.md`, "Launch-time adapters"): the adapters file read through the real files
 * adapter — here the files mock standing in for it — and each mocked system's state restored from its own file.
 */
class LaunchAdaptersTest {

    private val disk = FileSystemMock()
    private val files: Files = disk.port()
    private val facts = AdapterFacts(osDrivenUpload = true, appVersion = "9.9", freshDeviceId = { FRESH })

    private fun write(path: String, text: String) = disk.operator.write(FileArea.SHARED, path, text.encodeToByteArray())

    private fun launch(process: AdapterProcess = AdapterProcess.APP) = LaunchAdapters.read(files, process, facts)

    @Test
    fun no_adapters_file_is_an_ordinary_rig_build_every_system_real() {
        assertSame(LaunchAdapters.AllReal, launch())
    }

    // A platform that does not have every real adapter (Android) names the ones it has, and what no file means.
    private val partial = AdapterFacts(
        osDrivenUpload = false,
        appVersion = "9.9",
        freshDeviceId = { FRESH },
        realAdapters = setOf(MockedSystem.SCREEN, MockedSystem.LIFECYCLE, MockedSystem.FILES),
        whenAbsent = AdapterChoice(MockedSystem.entries.toSet() - setOf(MockedSystem.SCREEN, MockedSystem.LIFECYCLE)),
    )

    @Test
    fun without_every_real_adapter_no_file_is_the_platforms_default_choice_and_restores_nothing() {
        // A state file an earlier file-chosen launch left is not the default launch's to restore.
        write(AdapterFiles.state(MockedSystem.CLOCK), "{not json")
        val chosen = assertIs<LaunchAdapters.Chosen>(LaunchAdapters.read(files, AdapterProcess.APP, partial))
        assertEquals(partial.whenAbsent, chosen.choice)
    }

    @Test
    fun without_every_real_adapter_a_choice_leaving_another_real_is_refused_naming_each() {
        // `files=real` is allowed; every system omitted is read as real, and the two named ones have no adapter.
        write(AdapterFiles.CHOICE, "files=real\nbackend=real\nclock=real\n")
        val refused = assertIs<LaunchAdapters.Refused>(LaunchAdapters.read(files, AdapterProcess.APP, partial))
        assertTrue(refused.reasons.any { it.startsWith("backend=real") }, refused.reasons.toString())
        assertTrue(refused.reasons.any { it.startsWith("clock=real") }, refused.reasons.toString())
        assertTrue(refused.reasons.none { it.startsWith("files=real") }, refused.reasons.toString())
    }

    @Test
    fun without_every_real_adapter_a_choice_within_them_is_chosen() {
        val choice = AdapterChoice(MockedSystem.entries.toSet() - setOf(MockedSystem.SCREEN, MockedSystem.LIFECYCLE, MockedSystem.FILES))
        write(AdapterFiles.CHOICE, choice.render())
        assertEquals(choice, assertIs<LaunchAdapters.Chosen>(LaunchAdapters.read(files, AdapterProcess.APP, partial)).choice)
    }

    @Test
    fun a_choice_that_does_not_parse_is_refused_naming_the_line() {
        write(AdapterFiles.CHOICE, "clock=mock\nbakend=mock\n")
        val refused = assertIs<LaunchAdapters.Refused>(launch())
        assertTrue(refused.reasons.single().contains("line 2"), refused.reasons.toString())
    }

    @Test
    fun an_incoherent_choice_is_refused_with_every_broken_rule() {
        write(AdapterFiles.CHOICE, "backend=mock\n")
        val refused = assertIs<LaunchAdapters.Refused>(launch())
        assertTrue(refused.reasons.size >= 2, refused.reasons.toString())
    }

    @Test
    fun an_unrestorable_state_file_refuses_rather_than_starting_the_system_empty() {
        write(AdapterFiles.CHOICE, "clock=mock\n")
        write(AdapterFiles.state(MockedSystem.CLOCK), "{not json")
        val refused = assertIs<LaunchAdapters.Refused>(launch())
        assertTrue(refused.reasons.single().contains(AdapterFiles.state(MockedSystem.CLOCK)), refused.reasons.toString())
    }

    @Test
    fun a_coherent_choice_swaps_exactly_the_mocked_systems_and_never_builds_their_real_adapters() {
        write(AdapterFiles.CHOICE, "clock=mock\nscreen=mock\n")
        val chosen = assertIs<LaunchAdapters.Chosen>(launch())
        val built = mutableListOf<String>()
        val real = MockDevice()
        val ports = chosen.ports(
            root = AdapterProcess.APP,
            real = DevicePorts(
                clock = lazy { built += "clock"; real.clock.port() },
                ui = lazy { built += "ui"; real.screen.port() },
                systemUi = lazy { built += "systemUi"; real.systemUi.port() },
            ),
        )
        chosen.device.clock.operator.now = Instant.parse("2026-01-01T00:00:00Z")
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), ports.clock.now())
        assertTrue(ports.ui.toString().isNotEmpty())
        ports.systemUi.openSettings()
        assertEquals(listOf("systemUi"), built, "a mocked system's real adapter is never built")
        assertEquals(1, real.systemUi.operator.settingsOpened.value, "a real system reaches the real adapter")
        assertEquals("9.9", chosen.device.declaredVersion.value, "the backend mock hears the build's own version")
        assertEquals(FRESH, chosen.device.ownDeviceId)
    }

    @Test
    fun what_the_app_saves_the_next_launch_restores_and_an_unchanged_system_is_not_rewritten() = runTest {
        write(AdapterFiles.CHOICE, "clock=mock\nsystem-ui=mock\n")
        val first = assertIs<LaunchAdapters.Chosen>(launch())
        first.device.clock.operator.now = Instant.parse("2026-03-04T05:06:07Z")
        first.device.systemUi.port().share("hi")
        assertEquals(setOf(MockedSystem.CLOCK, MockedSystem.SYSTEM_UI), first.save().toSet())
        assertEquals(emptyList(), first.save(), "nothing changed, nothing written")
        first.device.systemUi.port().openSettings()
        assertEquals(listOf(MockedSystem.SYSTEM_UI), first.save())

        val second = assertIs<LaunchAdapters.Chosen>(launch())
        assertEquals(Instant.parse("2026-03-04T05:06:07Z"), second.device.clock.operator.now)
        assertEquals(listOf("hi"), second.device.systemUi.operator.shared.value)
        assertEquals(1, second.device.systemUi.operator.settingsOpened.value)
    }

    @Test
    fun the_extension_gets_its_own_faces() {
        write(AdapterFiles.CHOICE, "files=mock\nintegrity=mock\nbackend=mock\nupload-queue=mock\nupload-session=mock\n" +
            "downloads=mock\nlibrary=mock\npush=mock\nextension-registry=mock\n")
        val chosen = assertIs<LaunchAdapters.Chosen>(launch(AdapterProcess.EXTENSION))
        val ports = chosen.ports(DevicePorts(), AdapterProcess.EXTENSION)
        assertEquals(FileResult.AreaUnavailable, ports.files.read(FileArea.PRIVATE, "x"), "the extension reaches only the shared area")
        assertEquals(false, ports.integrity.isAvailable(), "App Attest does not exist in the extension")
    }

    @Test
    fun a_refused_launch_and_the_extensions_own_process_under_a_mocked_registration_compose_nothing() = runTest {
        write(AdapterFiles.CHOICE, "backend=mock\n")
        assertAnsweredWithoutComposing(launch(AdapterProcess.APP))

        write(AdapterFiles.CHOICE, "extension-registry=mock\n")
        assertAnsweredWithoutComposing(launch(AdapterProcess.EXTENSION))

        // Inside the app — the control channel invoking the extension's root — the composition runs.
        val host = ExtensionHostMock()
        var composed = false
        chosenExtensionHost(host.port(), launch(AdapterProcess.APP)).listen(
            ExtensionHandlers(onProcess = { composed = true; CycleResult.COMPLETED }, onTerminate = {}),
        )
        host.operator.process()
        assertTrue(composed)
    }

    private suspend fun assertAnsweredWithoutComposing(launch: LaunchAdapters) {
        val host = ExtensionHostMock()
        chosenExtensionHost(host.port(), launch).listen(
            ExtensionHandlers(onProcess = { error("composed under $launch") }, onTerminate = { error("composed") }),
        )
        assertEquals(CycleResult.COMPLETED, host.operator.process())
        host.operator.terminate()
    }

    private companion object {
        const val FRESH = "00000000-0000-4000-9000-0000000000f1"
    }
}
