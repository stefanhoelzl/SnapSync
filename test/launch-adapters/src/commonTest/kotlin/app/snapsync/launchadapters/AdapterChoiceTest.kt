package app.snapsync.launchadapters

import app.snapsync.mock.MockedSystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The adapters file's grammar and the coherence rules (`docs/testing.md`, "Launch-time adapters"). */
class AdapterChoiceTest {

    private fun parsed(text: String): AdapterChoice = assertIs<AdapterParse.Parsed>(AdapterChoice.parse(text)).choice

    private fun problems(text: String): List<String> = assertIs<AdapterParse.Invalid>(AdapterChoice.parse(text)).problems

    @Test
    fun a_missing_system_is_real_and_comments_and_blank_lines_are_ignored() {
        val choice = parsed("# a clock at a fixed instant\n\nclock=mock   # the screenshots' instant\nscreen = real\n")
        assertEquals(setOf(MockedSystem.CLOCK), choice.mocked)
    }

    @Test
    fun an_empty_file_is_all_real() {
        assertEquals(AdapterChoice.ALL_REAL, parsed(""))
    }

    @Test
    fun render_names_every_system_and_parses_back_to_the_same_choice() {
        val choice = AdapterChoice(setOf(MockedSystem.BACKEND, MockedSystem.LIBRARY, MockedSystem.PUSH))
        val text = choice.render()
        assertEquals(MockedSystem.entries.size, text.lines().count { it.isNotBlank() })
        assertEquals(choice, parsed(text))
        assertEquals(AdapterChoice.ALL_MOCK, parsed(AdapterChoice.ALL_MOCK.render()))
    }

    @Test
    fun an_unknown_system_an_unknown_value_a_bare_word_and_a_repeat_are_each_named_never_guessed() {
        val found = problems("backnd=mock\nclock=mocked\nlibrary\nclock=mock\nclock=real\n")
        assertEquals(4, found.size, found.joinToString("\n"))
        assertTrue(found[0].contains("line 1") && found[0].contains("names no system"))
        assertTrue(found[1].contains("line 2") && found[1].contains("`mock` or `real`"))
        assertTrue(found[2].contains("line 3") && found[2].contains("not a `system=mock|real` assignment"))
        assertTrue(found[3].contains("line 5") && found[3].contains("second time"))
    }

    @Test
    fun all_real_and_all_mock_are_coherent() {
        assertEquals(emptyList(), AdapterChoice.ALL_REAL.incoherence())
        assertEquals(emptyList(), AdapterChoice.ALL_MOCK.incoherence())
    }

    @Test
    fun a_clock_alone_and_a_screen_alone_are_coherent() {
        assertEquals(emptyList(), AdapterChoice(setOf(MockedSystem.CLOCK)).incoherence())
        assertEquals(emptyList(), AdapterChoice(setOf(MockedSystem.SCREEN, MockedSystem.LIFECYCLE)).incoherence())
    }

    @Test
    fun a_mocked_backend_needs_every_transfer_the_push_service_the_enclave_and_a_mocked_registration() {
        val reasons = AdapterChoice(setOf(MockedSystem.BACKEND)).incoherence()
        val text = reasons.joinToString("\n")
        listOf("upload-queue=mock", "upload-session=mock", "downloads=mock", "push=mock", "integrity=mock").forEach {
            assertTrue(it in text, "expected '$it' among:\n$text")
        }
        assertTrue("extension-registry=real needs backend=real" in text, text)
    }

    @Test
    fun real_photos_with_a_mocked_backend_is_a_coherent_choice() {
        val choice = AdapterChoice(
            setOf(
                MockedSystem.BACKEND, MockedSystem.UPLOAD_QUEUE, MockedSystem.UPLOAD_SESSION, MockedSystem.DOWNLOADS,
                MockedSystem.PUSH, MockedSystem.INTEGRITY, MockedSystem.EXTENSION_REGISTRY,
            ),
        )
        assertEquals(emptyList(), choice.incoherence(), "the rejected alternative to per-port choice forbade exactly this")
    }

    @Test
    fun mocked_photos_never_reach_a_real_transfer_or_a_real_backend() {
        val text = AdapterChoice(setOf(MockedSystem.LIBRARY, MockedSystem.EXTENSION_REGISTRY)).incoherence().joinToString("\n")
        assertTrue("library=mock needs upload-queue=mock, upload-session=mock" in text, text)
        val queue = AdapterChoice(setOf(MockedSystem.UPLOAD_QUEUE, MockedSystem.EXTENSION_REGISTRY)).incoherence().joinToString("\n")
        assertTrue("upload-queue=mock needs backend=mock" in queue, queue)
    }

    @Test
    fun a_mocked_download_may_land_in_a_real_library() {
        assertEquals(emptyList(), AdapterChoice(setOf(MockedSystem.DOWNLOADS)).incoherence(), "it stages real image bytes")
    }

    @Test
    fun a_real_registration_keeps_every_system_the_extension_writes_real() {
        val text = AdapterChoice(setOf(MockedSystem.FILES, MockedSystem.DATABASES)).incoherence().joinToString("\n")
        assertTrue("extension-registry=real needs files=real, databases=real" in text, text)
        assertEquals(emptyList(), AdapterChoice(setOf(MockedSystem.FILES, MockedSystem.DATABASES, MockedSystem.EXTENSION_REGISTRY)).incoherence())
    }
}
