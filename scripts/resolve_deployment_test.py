#!/usr/bin/env python3
"""Tests for the deployment resolver (capability `deployment-configuration`).

Stdlib `unittest` only, for the same reason the resolver is stdlib-only: it must run on every runner and
every dev machine with no install step. Run: `python3 scripts/resolve_deployment_test.py`.

Every case builds its own throwaway tree in a temp directory, so no test reads the real deployments —
a test asserting the production zone name would be testing configuration rather than behaviour.
"""

import importlib.util
import json
import pathlib
import plistlib
import tempfile
import unittest

_spec = importlib.util.spec_from_file_location(
    "resolve_deployment", pathlib.Path(__file__).with_name("resolve-deployment.py")
)
rd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rd)

BUNNY = {
    "kind": "bunny",
    "zone": "test-zone",
    "host": "storage.example",
    "s3Region": "xx",
    "accessKey": {"env": "TEST_ACCESS_KEY"},
}
# Every key the inventory marks required for a `bunny` deployment must appear here, or `standard()`
# builds an INCOMPLETE deployment and 20 unrelated tests fail on the missing key rather than on what
# they assert. That is how adding the two database keys broke this suite — and, because api-deploy is
# the only workflow that runs it and is not a required check, the break reached `main` green.
SECRETS = {
    "apnsPrivateKey": {"env": "TEST_APNS_KEY"},
    "attestTokenKey": {"env": "TEST_TOKEN_KEY"},
    "databaseUrl": {"env": "TEST_DATABASE_URL"},
    "databaseToken": {"env": "TEST_DATABASE_TOKEN"},
}
POLICY = {
    "eventCapacity": 3,
    "eventWindowMaxSeconds": 60,
    "eventLifetimeSeconds": 60,
    "attestTokenTtlSeconds": 60,
}
APPLE = {
    "appName": "Test",
    "bundleId": "test.bundle",
    "teamId": "TEAMID",
    "apnsKeyId": "KEYID",
    "appStoreUrl": "https://example.invalid/app",
    "appAttestRootCa": "-----BEGIN CERTIFICATE-----\nx\n-----END CERTIFICATE-----",
}


_KEEP = []  # hold each TemporaryDirectory until exit, so cleanup is not GC-timed (ResourceWarning)


class Tree:
    """A throwaway repo root holding only what the resolver reads."""

    def __init__(self):
        self.dir = tempfile.TemporaryDirectory()
        _KEEP.append(self.dir)
        self.root = pathlib.Path(self.dir.name)
        (self.root / "deployments" / "components").mkdir(parents=True)

    def component(self, name, obj):
        (self.root / "deployments" / "components" / f"{name}.json").write_text(json.dumps(obj))

    def deployment(self, name, obj):
        (self.root / "deployments" / f"{name}.json").write_text(json.dumps(obj))

    def standard(self, storage=None, **overrides):
        self.component("build", {
            "sha": {"env": "GITHUB_SHA", "scope": "build"},
            "channel": {"env": "SNAPSYNC_CHANNEL", "scope": "build"},
        })
        self.component("policy", POLICY)
        self.component("apple", APPLE)
        self.component("storage", {"storage": storage if storage is not None else dict(BUNNY)})
        body = {
            "extends": ["components/build.json", "components/policy.json", "components/apple.json",
                        "components/storage.json"],
            "domain": "example.invalid",
            **SECRETS,
            **overrides,
        }
        self.deployment("t", body)
        return self

    def resolve(self, name="t", env=None):
        return rd.resolve(self.root, name, env or {})


class MergeTest(unittest.TestCase):
    def test_later_component_wins_and_own_keys_win_last(self):
        t = Tree()
        t.component("a", {"domain": "a.invalid", "appName": "A"})
        t.component("b", {"domain": "b.invalid"})
        t.component("policy", POLICY)
        t.component("apple", APPLE)
        t.component("storage", {"storage": dict(BUNNY)})
        t.deployment("t", {
            "extends": ["components/a.json", "components/b.json", "components/policy.json",
                        "components/apple.json", "components/storage.json"],
            "domain": "own.invalid",
            **SECRETS,
        })
        flat = t.resolve()
        self.assertEqual(flat["domain"], "own.invalid")
        self.assertEqual(flat["appName"], "Test")  # apple.json came after a.json

    def test_a_component_may_not_extend(self):
        t = Tree().standard()
        t.component("nested", {"extends": ["components/apple.json"]})
        t.deployment("t", {"extends": ["components/nested.json"], "domain": "x.invalid"})
        with self.assertRaisesRegex(rd.ResolveError, "may not itself declare"):
            t.resolve()

    def test_missing_component_names_the_file(self):
        t = Tree().standard()
        t.deployment("t", {"extends": ["components/absent.json"], "domain": "x.invalid"})
        with self.assertRaisesRegex(rd.ResolveError, "absent.json"):
            t.resolve()

    def test_unknown_deployment_fails_closed(self):
        with self.assertRaisesRegex(rd.ResolveError, "unknown deployment 'nope'"):
            Tree().standard().resolve("nope")


class ValidationTest(unittest.TestCase):
    def test_unknown_key_is_rejected_not_ignored(self):
        # A typo must be an error rather than a silent absence of the key that was meant.
        t = Tree().standard(doamin="typo.invalid")
        with self.assertRaisesRegex(rd.ResolveError, "unknown key 'doamin'"):
            t.resolve()

    def test_missing_required_key_names_it(self):
        t = Tree()
        t.component("apple", APPLE)
        t.component("storage", {"storage": dict(BUNNY)})
        t.deployment("t", {
            "extends": ["components/apple.json", "components/storage.json"],
            "domain": "x.invalid", **SECRETS,
        })
        with self.assertRaisesRegex(rd.ResolveError, "eventCapacity"):
            t.resolve()

    def test_unknown_storage_kind_names_the_sealed_set(self):
        t = Tree().standard(storage={"kind": "s3", "zone": "z"})
        with self.assertRaisesRegex(rd.ResolveError, "outside the sealed set"):
            t.resolve()

    def test_credential_may_not_be_a_literal(self):
        storage = dict(BUNNY, accessKey="hunter2")
        t = Tree().standard(storage=storage)
        with self.assertRaisesRegex(rd.ResolveError, "must be an environment reference"):
            t.resolve()

    def test_scope_must_match_the_inventory(self):
        t = Tree().standard(sha={"env": "GITHUB_SHA"})  # inventory says build
        with self.assertRaisesRegex(rd.ResolveError, "declares scope 'runtime'"):
            t.resolve()

    def test_unknown_scope_is_rejected(self):
        t = Tree().standard(sha={"env": "X", "scope": "deploy"})
        with self.assertRaisesRegex(rd.ResolveError, "unknown scope"):
            t.resolve()


class KindTest(unittest.TestCase):
    def test_filesystem_requires_no_credentials(self):
        t = Tree()
        t.component("policy", POLICY)
        t.component("apple", APPLE)
        t.component("storage", {"storage": {"kind": "filesystem", "root": ".store"}})
        t.deployment("t", {
            "extends": ["components/policy.json", "components/apple.json", "components/storage.json"],
            "domain": "127.0.0.1:8080",
        })
        flat = t.resolve()
        self.assertEqual(flat["storage.kind"], "filesystem")
        self.assertNotIn("apnsPrivateKey", flat)
        self.assertNotIn("storage.accessKey", flat)

    def test_bunny_requires_the_zone(self):
        storage = {k: v for k, v in BUNNY.items() if k != "zone"}
        t = Tree().standard(storage=storage)
        with self.assertRaisesRegex(rd.ResolveError, "storage.zone"):
            t.resolve()


class ScopeTest(unittest.TestCase):
    def test_build_scope_is_read_from_the_environment(self):
        flat = Tree().standard().resolve(env={"GITHUB_SHA": "abc123"})
        self.assertEqual(flat["sha"], "abc123")

    def test_absent_build_variable_takes_the_declared_default(self):
        flat = Tree().standard().resolve(env={})
        self.assertEqual(flat["sha"], "dev")

    def test_runtime_reference_is_copied_through_as_a_name(self):
        # The artifact must carry the NAME, never the value: resolving it here would bake a live
        # credential into the deployed bundle and require CI to hold it.
        flat = Tree().standard().resolve(env={"TEST_ACCESS_KEY": "leaked"})
        self.assertEqual(flat["storage.accessKey"], {"env": "TEST_ACCESS_KEY"})


def plist(flat):
    """Parse the emitted property list with a REAL parser — the point of every case that uses it."""
    return plistlib.loads(rd.render_plist(flat).encode())


def xcconfig_values(text):
    """Read an xcconfig the way xcodebuild does: `//` opens a comment anywhere on the line."""
    out = {}
    for line in text.splitlines():
        line = line.split("//")[0].strip()
        if " = " in line:
            key, value = line.split(" = ", 1)
            out[key.strip()] = value.strip()
    return out


class RenderingTest(unittest.TestCase):
    def test_a_key_reaches_only_its_declared_renderings(self):
        flat = Tree().standard().resolve()
        site = json.loads(rd.render_site(flat))
        self.assertEqual(list(site), ["domain"])  # the site sees the domain and nothing else
        body = rd.nest(rd.project(flat, rd.JSON)); body.pop("sha", None)
        self.assertNotIn("appName", body)  # xcconfig-only
        self.assertNotIn("channel", body)  # xcconfig-only

    def test_sha_sits_beside_config_never_inside_it(self):
        flat = Tree().standard().resolve()
        ts = rd.render_deployment_ts(flat, "abc")
        self.assertIn('sha: "abc"', ts)
        self.assertNotIn('"sha":', ts)  # never a member of the config object

    def test_secrets_reach_the_bundle_as_names(self):
        flat = Tree().standard().resolve(env={"TEST_APNS_KEY": "leaked"})
        text = rd.render_deployment_ts(flat, "abc")
        self.assertIn("TEST_APNS_KEY", text)
        self.assertNotIn("leaked", text)

    def test_the_dsn_is_absent_off_release(self):
        # Absence is the off-switch, enforced by the renderer rather than by CI not exporting the secret.
        flat = Tree().standard(sentryDsn={"env": "SENTRY_DSN", "scope": "build"}).resolve(
            env={"SENTRY_DSN": "https://k@example.invalid/1", "SNAPSYNC_CHANNEL": "dev"}
        )
        self.assertNotIn("sentryDsn", plist(flat))

    def test_the_dsn_survives_the_grammar_it_is_rendered_into(self):
        """The bug this rendering exists to make unrepresentable.

        Asserted through a REAL plist parser, not against the emitted string: rendering the intended
        bytes was never in doubt — the previous xcconfig test asserted exactly that and passed while
        four TestFlight builds shipped with `SENTRY_DSN = https:`, truncated at the `//` that opens an
        xcconfig comment. What has to hold is that the value READ BACK equals the value resolved.
        """
        dsn = "https://k@example.invalid/1"
        flat = Tree().standard(sentryDsn={"env": "SENTRY_DSN", "scope": "build"}).resolve(
            env={"SENTRY_DSN": dsn, "SNAPSYNC_CHANNEL": "release"}
        )
        self.assertEqual(dsn, plist(flat)["sentryDsn"])

    def test_the_upload_base_carries_the_version_prefix(self):
        # Also a `//`-carrying value, and read back through the parser for the same reason.
        flat = Tree().standard().resolve()
        self.assertEqual("https://example.invalid/api/v1", plist(flat)["uploadBase"])

    def test_a_loopback_host_is_plaintext_and_everything_else_is_not(self):
        """The ATS constraint, derived from the host rather than declared beside it.

        This is what replaces the retired `BACKGROUND_UPLOAD_URL_BASE=` xcodebuild override: an
        operator points a build at the local rig by SELECTING that deployment, and the scheme follows
        from the host. A tunnel is not loopback and must stay https, or the build fails silently on
        device under default ATS.
        """
        for domain, expected in (
            ("127.0.0.1:8080", "http://127.0.0.1:8080/api/v1"),
            ("[::1]:8080", "http://[::1]:8080/api/v1"),      # the only URL-valid IPv6 form
            ("localhost:8080", "https://localhost:8080/api/v1"),   # a NAME, not the exempt literal
            ("random-words.trycloudflare.com", "https://random-words.trycloudflare.com/api/v1"),
            ("example.invalid", "https://example.invalid/api/v1"),
        ):
            flat = Tree().standard(domain=domain).resolve()
            self.assertEqual(expected, plist(flat)["uploadBase"], f"for domain {domain}")

    def test_the_plist_carries_exactly_the_device_facing_values(self):
        release = Tree().standard(sentryDsn={"env": "SENTRY_DSN", "scope": "build"}).resolve(
            env={"SENTRY_DSN": "https://k@example.invalid/1", "SNAPSYNC_CHANNEL": "release"}
        )
        self.assertEqual(
            {"uploadBase", "apnsEnv", "sentryEnvironment", "sentryDsn"}, set(plist(release))
        )
        dev = Tree().standard(sentryDsn={"env": "SENTRY_DSN", "scope": "build"}).resolve(
            env={"SENTRY_DSN": "https://k@example.invalid/1", "SNAPSYNC_CHANNEL": "dev"}
        )
        self.assertEqual({"uploadBase", "apnsEnv", "sentryEnvironment"}, set(plist(dev)))

    def test_the_three_channel_derived_settings_cannot_disagree(self):
        for channel, aps, apns, sentry in (("release", "production", "production", "production"),
                                           ("dev", "development", "sandbox", "development")):
            flat = Tree().standard().resolve(env={"SNAPSYNC_CHANNEL": channel})
            values = plist(flat)
            self.assertIn(f"APS_ENVIRONMENT = {aps}", rd.render_xcconfig(flat))
            self.assertEqual(apns, values["apnsEnv"])
            self.assertEqual(sentry, values["sentryEnvironment"])

    def test_the_upload_base_parts_survive_being_read_as_an_xcconfig(self):
        """The two settings the `Info.plist` composes `BackgroundUploadURLBase` from.

        Read back THROUGH the parser, never asserted as the emitted string. That distinction is the
        whole lesson of the DSN truncation: the renderer's test asserted the bytes it wrote, which were
        correct, and the break was entirely downstream in a grammar that reinterprets them. What has to
        hold is that xcodebuild, applying the comment rule, still sees a bare host and a scheme.

        Recomposed here exactly as the plists do it, because `assetsd` validates the registration
        against that composed value and a mismatch fails with a bare `PHPhotosErrorDomain -1`
        (capability `ios-photokit-upload`).
        """
        for domain, scheme in (
            ("example.invalid", "https"),
            ("127.0.0.1:8080", "http"),
            ("[::1]:8080", "http"),
            ("random-words.trycloudflare.com", "https"),
        ):
            flat = Tree().standard(domain=domain).resolve()
            values = xcconfig_values(rd.render_xcconfig(flat))
            self.assertEqual(domain, values["UPLOAD_HOST"], f"for domain {domain}")
            self.assertEqual(scheme, values["UPLOAD_SCHEME"], f"for domain {domain}")
            composed = f"{values['UPLOAD_SCHEME']}://{values['UPLOAD_HOST']}/api/v1"
            self.assertEqual(plist(flat)["uploadBase"], composed, f"for domain {domain}")

    def test_no_xcconfig_value_contains_a_comment_delimiter(self):
        """`//` opens a comment anywhere on an xcconfig line, and the grammar offers no escape."""
        flat = Tree().standard(sentryDsn={"env": "SENTRY_DSN", "scope": "build"}).resolve(
            env={"SENTRY_DSN": "https://k@example.invalid/1", "SNAPSYNC_CHANNEL": "release"}
        )
        for line in rd.render_xcconfig(flat).splitlines():
            # The RAW right-hand side, BEFORE comment stripping. Reading it through the parser would
            # inspect the already-truncated value and pass on exactly the input this must reject.
            if line.startswith("//") or " = " not in line:
                continue
            name, value = line.split(" = ", 1)
            self.assertNotIn("//", value, f"{name} would be truncated by the xcconfig comment rule")

    def test_no_environment_value_reaches_the_xcconfig(self):
        """The RAW rule, asserted on the OUTPUT rather than the declaration.

        A declaration-level check would fire on `channel`, which is environment-sourced and names the
        xcconfig but appears there only through enums this renderer chooses. Poisoning every environment
        name the inventory reads and looking for the poison in the rendered file asks the question that
        actually matters: did an unreviewable value land in a grammar with no escaping?
        """
        poison = "//POISON//"
        names = {v["env"] for v in (Tree().standard(
            sentryDsn={"env": "SENTRY_DSN", "scope": "build"}).resolve(env={})).values()
            if isinstance(v, dict) and "env" in v}
        names |= {"SNAPSYNC_CHANNEL", "SENTRY_DSN", "GITHUB_SHA"}
        flat = Tree().standard(sentryDsn={"env": "SENTRY_DSN", "scope": "build"}).resolve(
            env={n: poison for n in names}
        )
        self.assertNotIn(poison, rd.render_xcconfig(flat))

    def test_types_declare_a_real_union(self):
        types = rd.render_types(Tree().standard().resolve())
        self.assertIn('readonly kind: "bunny";', types)
        self.assertIn('readonly kind: "filesystem";', types)
        # The union spans the WHOLE deployment, not just `storage`: apnsPrivateKey/attestTokenKey are
        # kind==bunny keys, so a storage-only union would type a filesystem deployment as carrying them.
        self.assertIn("export type ResolvedDeployment = BunnyDeployment | FilesystemDeployment;", types)
        self.assertIn("readonly accessKey: { readonly env: string };", types)

    def test_the_bunny_branch_alone_carries_the_deployed_secrets(self):
        types = rd.render_types(Tree().standard().resolve())
        bunny = types.split("export type BunnyDeployment")[1].split("export type FilesystemDeployment")[0]
        fs = types.split("export type FilesystemDeployment")[1].split("export type ResolvedDeployment")[0]
        for key in ("apnsPrivateKey", "attestTokenKey"):
            self.assertIn(key, bunny)
            self.assertNotIn(key, fs)

    def test_narrowing_is_offered_as_type_guards(self):
        # A nested discriminant does not narrow the outer type, so the guards are the only safe way in.
        types = rd.render_types(Tree().standard().resolve())
        self.assertIn("export function isBunnyDeployment(", types)
        self.assertIn("export function isFilesystemDeployment(", types)


class MaintenanceKeyTest(unittest.TestCase):
    """The maintenance flag (capability `backend-deployment`): default off, JSON only, one key apart."""

    def test_a_deployment_that_does_not_set_it_serves_normally(self):
        # The default is what makes every local resolve and every non-migrating deploy safe: a rendering
        # nobody thought about must not produce a bundle that refuses the device API.
        flat = Tree().standard().resolve()
        self.assertIs(flat["maintenance"], False)

    def test_a_literal_true_reaches_the_bundle(self):
        flat = Tree().standard(maintenance=True).resolve()
        self.assertIs(flat["maintenance"], True)
        body = rd.nest(rd.project(flat, rd.JSON))
        self.assertIs(body["maintenance"], True)

    def test_the_flag_reaches_only_the_backend_bundle(self):
        flat = Tree().standard(maintenance=True).resolve()
        self.assertNotIn("maintenance", json.loads(rd.render_site(flat)))
        self.assertNotIn("maintenance", plist(flat))
        self.assertNotIn("maintenance", xcconfig_values(rd.render_xcconfig(flat)))

    def test_it_is_typed_as_a_boolean(self):
        # `bool` is a subclass of `int`, so a bare numeric test would type this `number` and the emitted
        # value would not satisfy the emitted type. The compiler is the check; this pins that it can be.
        types = rd.render_types(Tree().standard(maintenance=True).resolve())
        self.assertIn("readonly maintenance: boolean;", types)

    def test_two_deployments_over_one_component_differ_by_exactly_this_key(self):
        # The whole point of the shared component: the domain and every credential reference resolve
        # identically, so the maintenance bundle cannot drift from the one it stands in for.
        t = Tree().standard()
        t.component("core", {"domain": "example.invalid", **SECRETS})
        shared = ["components/build.json", "components/policy.json", "components/apple.json",
                  "components/storage.json", "components/core.json"]
        t.deployment("live", {"extends": shared})
        t.deployment("window", {"extends": shared, "maintenance": True})

        live = t.resolve("live")
        window = t.resolve("window")
        differing = {k for k in set(live) | set(window) if live.get(k) != window.get(k)}
        self.assertEqual(differing, {"maintenance"})

    def test_a_deployment_may_not_extend_another_deployment(self):
        # `prod.json` is now a bare `extends` list, which makes `extends: ["prod.json"]` an inviting
        # mistake. It must fail rather than silently resolve to a partial merge.
        t = Tree().standard()
        t.deployment("window", {"extends": ["t.json"], "maintenance": True})
        with self.assertRaisesRegex(rd.ResolveError, "may not itself declare"):
            t.resolve("window")


class AtomicityTest(unittest.TestCase):
    def test_a_failed_resolution_writes_nothing(self):
        t = Tree().standard(doamin="typo")
        with self.assertRaises(rd.ResolveError):
            flat = t.resolve()
            rd.emit(t.root, flat)
        self.assertFalse((t.root / "api/src/deployment.ts").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
