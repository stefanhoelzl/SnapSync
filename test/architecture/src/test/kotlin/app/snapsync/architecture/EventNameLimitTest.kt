package app.snapsync.architecture

import app.snapsync.model.EVENT_NAME_MAX_LENGTH
import app.snapsync.wire.api.Limits
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * **The client's name cap equals the backend's** (capability `event-creation`).
 *
 * The backend owns the rule: `api/openapi.yaml` caps `EventName` (`maxLength`), and its generated validation
 * rejects a longer name with a `400`. The client mirrors it so the field caps typing and an over-long
 * name is unreachable rather than rejected on a round trip — which means the mirror is only useful while
 * it agrees.
 *
 * Nothing else can notice a disagreement. A raised backend limit makes the client refuse names the server
 * would have taken, and a lowered one makes the client offer names the server will reject — and the only
 * symptom of either is a `400` a user cannot act on, at the one moment they are naming their event.
 *
 * The two values were three literals before this gate: a private constant in the create screen, a bare
 * `100` in the rename sheet, and the TypeScript export. Two of them could have drifted with nothing to
 * say so, which is exactly what this asserts is no longer possible.
 */
class EventNameLimitTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: fail("could not locate the repository root")

    @Test
    fun `the Kotlin name cap equals the backend's`() {
        // The backend's cap is the description's (`api/openapi.yaml`, `EventName.maxLength`), which the generated wire
        // exports and the backend's generated validation enforces.
        assertEquals(
            Limits.EVENT_NAME_MAX_LENGTH,
            EVENT_NAME_MAX_LENGTH,
            "\nThe client's event-name cap disagrees with the backend's.\n" +
                "  The backend owns this rule; the client mirrors it so an over-long name is unreachable\n" +
                "  rather than rejected on a round trip. Change the Kotlin constant to match, or change\n" +
                "  both deliberately — but not one alone.\n",
        )
    }

    @Test
    fun `no screen states the cap as a bare literal`() {
        // The point of the constant is that there is ONE of it. A literal beside a `maxLength` in the
        // screens module is the shape this gate exists to keep out — it is how the rename sheet drifted
        // out of sight of the create form's constant in the first place.
        val offenders = File(repoRoot, "ui/screens/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.path }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) ->
                        Regex(
                            """maxLength\s*=\s*\d+""",
                        ).containsMatchIn(line.substringBefore("//"))
                    }
                    .map { (n, line) -> "${file.name}:${n + 1}  ${line.trim()}" }
            }
            .filterNot { "maxLength = 200" in it } // the bug-report note's own bound, unrelated to a name
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "\nA name cap is stated as a literal instead of EVENT_NAME_MAX_LENGTH:\n" +
                offenders.joinToString("\n") { "  $it" } + "\n",
        )
    }
}
