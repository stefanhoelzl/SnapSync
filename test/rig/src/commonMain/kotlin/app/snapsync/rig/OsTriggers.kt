package app.snapsync.rig

import app.snapsync.contracts.EntryDriver

/**
 * The app's `/os` verbs, for every host: each is one [EntryDriver] delivery under the name the iOS shell's entry point
 * has always carried (`docs/testing.md`, "The control channel") — the `rig-channel` runbook, [RigVocabulary] and the
 * integration tests speak these names, so a host supplies a driver and never a table of its own.
 */
fun appTriggers(driver: EntryDriver): Map<String, RigTrigger> = mapOf(
    "onForeground" to RigTrigger.Fire { driver.foreground() },
    "onBackground" to RigTrigger.Fire { driver.background() },
    "onPushToken" to RigTrigger.Fire { arg -> driver.pushToken(arg.orEmpty()) },
    "onPushTokenFailure" to RigTrigger.Fire { arg -> driver.pushTokenFailure(arg.orEmpty()) },
    // The warm Universal Link: the link, through a running app's continuation.
    "onSceneContinueActivity" to RigTrigger.Fire { arg -> driver.continueLink(arg.orEmpty()) },
    "onSilentPush" to RigTrigger.Receipted { arg, done -> driver.silentPush(arg, done) },
    // The iOS task identifier is the argument, as the operating system delivers it.
    "onBackgroundTask" to RigTrigger.Receipted { arg, done -> driver.backgroundTask(arg.orEmpty(), done) },
    // The session identifier is the argument, as the operating system delivers it.
    "onBackgroundTransfers" to RigTrigger.Receipted { arg, done -> driver.backgroundTransfers(arg.orEmpty(), done) },
)
