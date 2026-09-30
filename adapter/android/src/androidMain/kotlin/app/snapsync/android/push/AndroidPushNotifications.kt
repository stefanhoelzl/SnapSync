package app.snapsync.android.push

import android.content.Context
import app.snapsync.android.download.OnceCompletion
import app.snapsync.model.PUSH_KIND_FCM
import app.snapsync.model.PlatformError
import app.snapsync.model.PushMessage
import app.snapsync.model.PushToken
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Completion
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.PushNotifications
import co.touchlab.kermit.Logger
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Firebase project a build's pushes come from — the resolved deployment's public Firebase values
 * (`docs/deployment.md`). Rendered into the build rather than read from a `google-services.json`, as every other
 * deployment value is. [isConfigured] is `false` on a build whose deployment names no project yet: that build gets no
 * push at all, exactly as a phone without Google Play services does.
 */
class FirebaseConfig(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val senderId: String,
) {
    val isConfigured: Boolean
        get() = listOf(projectId, applicationId, apiKey, senderId).all { it.isNotBlank() }
}

/**
 * The Android [PushNotifications] (capability `receiving-photos`): Firebase Cloud Messaging, the counterpart of APNs.
 *
 * - **Its kind is [PUSH_KIND_FCM]**, which the device's registration carries, so the backend sends through FCM.
 * - **Firebase is started here, by hand**, from [config] — the manifest removes Firebase's auto-start provider, so a
 *   build or a test that never composes this adapter never starts Firebase. An unconfigured build, and a phone without
 *   Google Play services, answer [register] with [PushHandlers.onTokenFailure] and never hear a push; their photos
 *   arrive on the next opening, as with a dropped wake.
 * - **A push** is a high-priority FCM data message whose data is the whole payload (`eventId`), handed over whole —
 *   `model/`'s codec reads it. [SnapSyncMessagingService] holds FCM's worker thread until the core releases the
 *   completion, at most [MESSAGE_BUDGET_MILLIS]: FCM allows a message about ten seconds of the process. The push's own
 *   work and the tail after it run under the process's background time, which the core holds across the wake — the
 *   completion has no expiry signal of its own.
 */
class AndroidPushNotifications(
    context: Context,
    private val config: FirebaseConfig,
    private val log: Logger = Logger.withTag("push"),
) : PushNotifications {

    private val appContext = context.applicationContext

    @Volatile
    private var handlers: PushHandlers? = null

    override val kind: String = PUSH_KIND_FCM

    private val messaging: FirebaseMessaging? by lazy {
        if (!config.isConfigured) {
            log.i { "this build names no Firebase project — it gets no push" }
            return@lazy null
        }
        runCatchingCancellable {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(
                    appContext,
                    FirebaseOptions.Builder()
                        .setProjectId(config.projectId)
                        .setApplicationId(config.applicationId)
                        .setApiKey(config.apiKey)
                        .setGcmSenderId(config.senderId)
                        .build(),
                )
            }
            FirebaseMessaging.getInstance()
        }.onFailure { log.w(it) { "Firebase could not be started" } }.getOrNull()
    }

    override fun listen(handlers: PushHandlers) {
        this.handlers = handlers
        registration.value = this
    }

    /**
     * Ask FCM for this device's token. `getToken()` is deprecated in Firebase Messaging 25 for `register()` +
     * `onRegistered`, which deliver a token when it is issued; the core instead asks at every app entry and compares the
     * answer against what the backend last accepted — `getToken()`'s semantics, which answers the current token every
     * time. Whether `onRegistered` re-delivers an unchanged token to a new process is not measured (no Firebase project
     * exists to measure it with); move only once it is.
     */
    @Suppress("DEPRECATION")
    override fun register() {
        val messaging = messaging ?: return failure("no push service on this build or phone")
        messaging.token.addOnCompleteListener { task ->
            // `task.result` THROWS on a failed task, and this runs on the main thread: read it only once the task
            // succeeded, so a refused registration reaches `failure` instead of killing the process (it did, on the CI
            // emulator: "FCM Registration failed!").
            val token = if (task.isSuccessful) task.result?.takeIf { it.isNotBlank() } else null
            if (token != null) {
                handlers?.onToken(PushToken(token))
            } else {
                failure(task.exception?.message ?: "FCM issued no token")
            }
        }
    }

    /** FCM issued (or rotated) this device's token. */
    internal fun deliverToken(token: String) {
        handlers?.onToken(PushToken(token))
    }

    /** A data message arrived; [completion] is released once the push's own work is done. */
    internal fun deliverMessage(data: Map<String, String>, completion: Completion) {
        val current = handlers ?: return completion.complete()
        current.onMessage(PushMessage(data.toMap<Any?, Any?>()), completion)
    }

    private fun failure(reason: String) {
        log.w { "no push token: $reason" }
        handlers?.onTokenFailure(PlatformError(reason))
    }

    internal companion object {
        /** The adapter the process's composition listened on last; the messaging service waits for one. */
        val registration = MutableStateFlow<AndroidPushNotifications?>(null)

        /** How long a message holds FCM's worker at most: under the ~10 s FCM gives it. */
        const val MESSAGE_BUDGET_MILLIS = 9_000L
    }
}

/**
 * FCM's entry into the app — an entry port. A message starts a dead process: `Application.onCreate` composes (building
 * no UI) before any service runs, so the [AndroidPushNotifications] the composition listened on is already registered
 * here. None — a build that refused at start, or a rig launch whose push service is mocked — runs nothing.
 */
class SnapSyncMessagingService : FirebaseMessagingService() {

    // Deprecated with `getToken()` (see `AndroidPushNotifications.register`), and kept with it.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        AndroidPushNotifications.registration.value?.deliverToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // FCM calls this on its own worker thread and considers the message handled when it returns, so the thread is
        // held — on a latch, never a coroutine runner — until the core releases the push, at most the budget.
        val push = AndroidPushNotifications.registration.value ?: return
        val released = CountDownLatch(1)
        runCatchingCancellable { push.deliverMessage(message.data, OnceCompletion { released.countDown() }) }
            .onFailure { Logger.withTag("push").w(it) { "the push could not be handed over" } }
        released.await(AndroidPushNotifications.MESSAGE_BUDGET_MILLIS, TimeUnit.MILLISECONDS)
    }
}
