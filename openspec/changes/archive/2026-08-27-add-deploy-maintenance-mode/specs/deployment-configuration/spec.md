## ADDED Requirements

### Requirement: The maintenance flag is a build-scope key, absent by default

The inventory SHALL declare a **maintenance** key: build scope, rendered into the backend bundle's
rendering alone, defaulting to **off**. It is the same shape the commit stamp and the build channel already
have — a value that varies per build rather than per deployment.

It SHALL NOT be a runtime environment reference. CI holds only the script-scoped deploy key and cannot
write the script's environment (see `backend-deployment`), so a value CI must control has to ship inside
the artifact. This is the same argument that makes every other non-secret deployment-resolved rather than
environment-owned; the maintenance flag is simply the first one CI sets **per publish** rather than per
deployment.

Its default SHALL be off, so every rendering that does not deliberately set it produces a bundle that
serves normally.

#### Scenario: A deployment that does not set it serves normally

- **WHEN** a deployment is resolved without declaring the maintenance key
- **THEN** the rendered backend configuration reports the window closed

#### Scenario: The flag reaches only the backend bundle

- **WHEN** the resolver emits every rendering
- **THEN** the maintenance key appears in the backend bundle's rendering and in no other

### Requirement: A second deployment sharing a first one's values does so through a component

Two deployments that must agree on a set of values SHALL share them by **both extending the same
component**, never by one deployment extending another and never by restating them.

This follows from the composition rule already stated: a component may not itself declare `extends`, so a
deployment cannot be extended. Restating the shared values in the second file would put them in two places
with nothing binding them — the drift class this capability exists to make impossible, applied to the
device-facing domain and the credential references, which are exactly the values whose disagreement is
silent and expensive.

Applying this: the production deployment's own keys move into a component, and both the production
deployment and the maintenance deployment extend it — the latter adding the maintenance flag and nothing
else.

#### Scenario: The maintenance deployment differs by exactly one key

- **WHEN** the production and maintenance deployments are resolved
- **THEN** every key resolves identically except the maintenance flag

#### Scenario: A deployment is not extended

- **WHEN** a deployment file names another deployment file in its `extends` list
- **THEN** resolution fails, because that file declares `extends` and a component may not
