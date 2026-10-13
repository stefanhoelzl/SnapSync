package app.snapsync.rig

import app.snapsync.compose.AppDevicePorts
import app.snapsync.contracts.attest.SoftwareKeyIntegrity

/** A simulator has no App Attest: every real adapter stands but the integrity service, a software key instead. */
internal actual fun attestable(real: AppDevicePorts): AppDevicePorts = AppDevicePorts(
    clock = real.clock,
    crypto = real.crypto,
    crashReporter = real.crashReporter,
    files = real.files,
    databases = real.databases,
    preferences = real.preferences,
    secureStore = real.secureStore,
    platformDeviceId = real.platformDeviceId,
    integrity = lazy { SoftwareKeyIntegrity() },
    processInfo = real.processInfo,
    network = real.network,
    deviceConditions = real.deviceConditions,
    http = real.http,
    backgroundTime = real.backgroundTime,
    wake = real.wake,
    extensionRegistry = real.extensionRegistry,
    gallery = real.gallery,
    uploadSession = real.uploadSession,
    download = real.download,
    systemUi = real.systemUi,
    lifecycle = real.lifecycle,
    links = real.links,
    pushNotifications = real.pushNotifications,
    ui = real.ui,
    dateFormatting = real.dateFormatting,
)
