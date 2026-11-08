plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("snapsync.targets")
}

// `:test:verifies` — the `@Verifies` annotation alone (`docs/architecture.md`, "Tests verify requirements"): how a test
// says which requirement it verifies. Its own module, depending on nothing, so any test source set can claim a
// requirement without linking the control channel's client: the screens' Compose tests (`:ui:screens`) verify what only
// a drawn screen shows. `:test:control` exposes it to the tests that drive the app over the protocol, as before.
//
// Support group (`docs/architecture.md`, "The module set: three groups"): it links into no
// shipped-format binary. The package stays `app.snapsync.control`, the annotation's name since it was introduced.
