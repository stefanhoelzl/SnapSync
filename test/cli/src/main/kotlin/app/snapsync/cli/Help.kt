package app.snapsync.cli

// The help, from the same derivation as the commands: what `rig --help`, `rig <service> --help` and
// `rig <service> <method> --help` print. The descriptor carries names and types, not the interfaces' KDoc, so what a
// method does is read in `:test:levers`, where the help points.

internal fun topHelp(): String = buildString {
    appendLine("rig — the control channel's typed services, over kRPC (docs/testing.md, \"Typed services\")")
    appendLine()
    appendLine("usage: rig --url <base> [--json] <service> <method> [--<parameter> <value>]…")
    appendLine("       rig --url <base> [--json] run <file>     one command per line ('-': stdin), one connection")
    appendLine("       rig [<service> [<method>]] --help")
    appendLine()
    appendLine("  --url <base>   the host: http://127.0.0.1:<port> — the JVM host's printed port, or a phone's forward")
    appendLine("  --json         print each answer as one line of JSON")
    appendLine(
        "  --timeout <s>  how long one call may take, in seconds (default 60; 0: as long as it takes — a contract",
    )
    appendLine("                 run, a big seed, a wipe the device's owner must confirm); a stream's, default 300")
    appendLine()
    appendLine("a stream (world events) prints one line of JSON per element until its reader goes or its bound passes;")
    appendLine("a device host serves the world's events from its last act on — wait on one with jq:")
    appendLine("  rig --url <base> world events | jq -cn 'first(inputs | select(.type == \"ui\"))'")
    appendLine()
    appendLine("services:")
    SERVICE_COMMANDS.forEach { appendLine("  ${it.name.padEnd(SERVICE_COLUMN)}${it.needsLine()}") }
    appendLine()
    appendLine(
        "exit: 0 answered (a stream: its reader went) · 1 failed (the host's message; a stream: its bound passed) · " +
            "2 usage · 3 refused by this host (its reason)",
    )
}

internal fun ServiceCommands.help(): String = buildString {
    appendLine("rig $name — ${service.type.qualifiedName} (${needsLine()})")
    appendLine()
    methods.forEach { appendLine("  ${it.usage().removePrefix("rig $name ")}") }
    appendLine()
    appendLine(
        "an omitted [--event] is the joined event, an omitted [--device] this device; any other omitted value none",
    )
}

internal fun MethodCommand.help(): String = buildString {
    appendLine(usage())
    appendLine()
    appendLine(
        "${service.type.qualifiedName}.$declared — ${if (streams) "streams" else "answers"} ${
            callable.returnType.kType.toString().removePrefix("kotlin.")
        }" + if (streams) ": one line of JSON per element" else "",
    )
    parameters.forEach { flag ->
        val omitted = flag.omittedMeans?.let { "  (omitted: $it)" } ?: "  (required)"
        appendLine("  --${flag.name} <${flag.typeLabel}>$omitted")
    }
}

/** `rig backend fail --operation <String> --fault <server-error|…> [--times <Int>]`. */
internal fun MethodCommand.usage(): String = buildString {
    append("rig ${service.name} $name")
    parameters.forEach { flag ->
        val value = if (flag.switch) " [true|false]" else " <${flag.typeLabel}>"
        append(if (flag.omittable) " [--${flag.name}$value]" else " --${flag.name}$value")
    }
}

private fun ServiceCommands.needsLine(): String =
    service.needs.joinToString(", ") { it.what }.ifEmpty { "every host" }.let { "needs: $it" }

private const val SERVICE_COLUMN = 16
