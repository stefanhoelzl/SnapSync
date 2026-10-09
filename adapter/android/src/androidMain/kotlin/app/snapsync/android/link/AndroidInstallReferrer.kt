package app.snapsync.android.link

import android.content.Context
import android.content.SharedPreferences
import android.os.RemoteException
import app.snapsync.model.inviteLinkFromInstallReferrer
import co.touchlab.kermit.Logger
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The invite a Google Play install carried, opened ONCE per installation. The event page's
 * Play button hands Play the invite's fragment payload as the install referrer; on the app's first foreground start
 * this reader asks Play for it and, when it is an invite ([inviteLinkFromInstallReferrer]), hands it to [links] as the
 * event link it came from — the join screen then opens exactly as for a tapped invite. An ORGANIC install, or a
 * referrer that is no invite, delivers nothing.
 *
 * **At most once.** Play answers the same referrer for 90 days after the install, so a small record of its own
 * ([record]) keeps it from reopening the join screen on every launch. That is this platform quirk's bookkeeping, not a
 * service's state, so it does not go through the `Preferences` port. The record is written AFTER the delivery returns:
 * a crash between the two re-offers the invite once, which the user can dismiss, where recording first would lose it
 * silently. Clearing the app's data within the 90 days re-offers it once too — the same invite, joined only on
 * confirmation.
 *
 * **When Play cannot answer.** A device with no Play Store ([ReferrerAnswer.NeverAvailable]) is recorded as handled: it
 * never will. A Play service that is busy, disconnects or refuses ([ReferrerAnswer.TryLater]) is not, so the next
 * foreground start asks again.
 *
 * Asked only from a FOREGROUND start (the root calls [deliverOnce] as the activity is created), so the invite is never
 * consumed by a background start with no screen to open the join screen on. Decision record:
 * `changes/archive/2026-10-01-play-badge-and-install-referrer`, D5.
 */
class AndroidInstallReferrer internal constructor(
    private val record: SharedPreferences,
    private val source: ReferrerSource,
    private val links: AndroidLinks,
    private val log: Logger,
) {
    constructor(context: Context, links: AndroidLinks, log: Logger) : this(
        record = context.getSharedPreferences(RECORD_FILE, Context.MODE_PRIVATE),
        source = PlayReferrerSource(context),
        links = links,
        log = log,
    )

    /** Asks Play once, unless an earlier start already handled this installation's referrer. */
    fun deliverOnce() {
        if (record.getBoolean(HANDLED, false)) return
        source.read { answer ->
            when (answer) {
                is ReferrerAnswer.Referrer -> {
                    val link = inviteLinkFromInstallReferrer(answer.value)
                    if (link == null) {
                        log.i { "install referrer carries no invite" }
                    } else {
                        log.i { "install referrer carries an invite — opening it" }
                        links.deliverInstallReferrer(link)
                    }
                    markHandled()
                }
                ReferrerAnswer.NeverAvailable -> {
                    log.i { "install referrer is not supported on this device" }
                    markHandled()
                }
                ReferrerAnswer.TryLater -> log.i { "install referrer is unavailable — asking again on the next start" }
            }
        }
    }

    private fun markHandled() {
        if (!record.edit().putBoolean(HANDLED, true).commit()) log.w { "install referrer record not written" }
    }

    private companion object {
        const val RECORD_FILE = "install-referrer"
        const val HANDLED = "handled"
    }
}

/** What Play answered about this installation's referrer. */
internal sealed interface ReferrerAnswer {
    /** The referrer the install carried — for an organic install, the one Play fills in itself. */
    data class Referrer(val value: String) : ReferrerAnswer

    /** This device cannot answer, ever (no Play Store). */
    data object NeverAvailable : ReferrerAnswer

    /** Play could not answer this time. */
    data object TryLater : ReferrerAnswer
}

/** The OS seam: asks for the referrer and answers exactly once. The real one is [PlayReferrerSource]. */
internal fun interface ReferrerSource {
    fun read(answer: (ReferrerAnswer) -> Unit)
}

/** The Play Install Referrer client, connected for one answer and released. Its callbacks run on the main thread. */
private class PlayReferrerSource(private val context: Context) : ReferrerSource {
    override fun read(answer: (ReferrerAnswer) -> Unit) {
        val client = InstallReferrerClient.newBuilder(context).build()
        val answered = AtomicBoolean(false)
        fun once(result: ReferrerAnswer) {
            if (answered.compareAndSet(false, true)) {
                client.endConnection()
                answer(result)
            }
        }
        client.startConnection(
            object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    once(
                        when (responseCode) {
                            InstallReferrerClient.InstallReferrerResponse.OK -> referrerOf(client)
                            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> ReferrerAnswer.NeverAvailable
                            else -> ReferrerAnswer.TryLater
                        },
                    )
                }

                override fun onInstallReferrerServiceDisconnected() = once(ReferrerAnswer.TryLater)
            },
        )
    }

    /** The one read the client can refuse: the Play service went away between the connection and the read. */
    private fun referrerOf(client: InstallReferrerClient): ReferrerAnswer = try {
        ReferrerAnswer.Referrer(client.installReferrer.installReferrer.orEmpty())
    } catch (_: RemoteException) {
        ReferrerAnswer.TryLater
    }
}
