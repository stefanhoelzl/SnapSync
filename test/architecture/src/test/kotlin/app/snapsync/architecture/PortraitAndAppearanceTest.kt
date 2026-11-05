package app.snapsync.architecture

import app.snapsync.control.Verifies
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **The app is a portrait phone app that follows the system appearance**, as the platforms are told it is: both are
 * declarations the operating system acts on before any of the app's code runs, so the declarations are what a test can
 * hold. What the OS then does with them (that it really does not rotate, that it really draws this colour) is the
 * platform's, and only a device shows it.
 *
 * - The orientation: iOS's one honoured key lists upright portrait alone (the app targets iPhone only); Android's
 *   launcher activity is locked to portrait. A tablet or an unfolded foldable may rotate it, which Android does on its
 *   own over the lock (the spec allows it).
 * - No white flash in dark mode: the window the OS shows before the first frame is the screen's own background, in
 *   both appearances — on Android a night theme with its own window colour, on iOS a launch screen with no image or
 *   colour of its own, which the system draws in its own background for the current appearance.
 */
class PortraitAndAppearanceTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    private fun read(path: String): String = File(repoRoot, path).also {
        assertTrue(it.isFile, "$path is missing — the shell moved and this guard is stale")
    }.readText()

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "The app is a portrait phone app that follows the system appearance",
        scenario = "Rotating the phone",
    )
    fun `both platforms declare upright portrait only`() {
        val plist = read("iosApp/iosApp/Info.plist")
        val orientations = Regex("""<key>UISupportedInterfaceOrientations</key>\s*<array>(.*?)</array>""", DOT_ALL)
            .find(plist)?.groupValues?.get(1) ?: fail("Info.plist declares no supported orientations")
        assertEquals(
            listOf("UIInterfaceOrientationPortrait"),
            Regex("""<string>([^<]+)</string>""").findAll(orientations).map { it.groupValues[1] }.toList(),
            "iOS: upright portrait only",
        )
        assertTrue(
            "UISupportedInterfaceOrientations~ipad" !in plist,
            "iOS: an iPad variant would let the app rotate on a device the app does not target",
        )

        val manifest = read("app/android/src/main/AndroidManifest.xml")
        val launcher = Regex("""<activity\b(?:(?!</activity>).)*?android\.intent\.category\.LAUNCHER""", DOT_ALL)
            .find(manifest)?.value ?: fail("the Android manifest has no launcher activity")
        assertTrue(
            Regex("""android:screenOrientation="portrait"""").containsMatchIn(launcher),
            "Android: the launcher activity is locked to portrait",
        )
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "The app is a portrait phone app that follows the system appearance",
        scenario = "Launching in dark mode",
    )
    fun `the window before the first frame is the screen's background in either appearance`() {
        listOf("values", "values-night").forEach { dir ->
            val theme = read("app/android/src/main/res/$dir/themes.xml")
            assertTrue(
                "<item name=\"android:windowBackground\">@color/screen_background</item>" in theme,
                "Android ($dir): the launch window is the screen's background",
            )
            assertTrue(
                Regex("""<color name="screen_background">#[0-9A-Fa-f]{8}</color>""")
                    .containsMatchIn(read("app/android/src/main/res/$dir/colors.xml")),
                "Android ($dir): the screen's background has its own colour",
            )
        }
        assertTrue(
            Regex("""<key>UILaunchScreen</key>\s*<dict\s*/>""").containsMatchIn(read("iosApp/iosApp/Info.plist")),
            "iOS: the launch screen draws nothing of its own, so the system background of the current appearance shows",
        )
    }

    private companion object {
        val DOT_ALL = RegexOption.DOT_MATCHES_ALL
    }
}
