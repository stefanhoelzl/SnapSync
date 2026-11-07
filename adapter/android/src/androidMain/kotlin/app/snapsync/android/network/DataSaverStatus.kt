package app.snapsync.android.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether Android's Data Saver holds this app's data: on, and SnapSync not allowed
 * unrestricted data in it (`restrictBackgroundStatus` `ENABLED`; `WHITELISTED` holds nothing). [AndroidNetworkMonitor]'s
 * KDoc has the platform facts and their measurement.
 */
class DataSaverStatus(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity: ConnectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

    /** Data Saver holds this app now. Cheap and synchronous. */
    fun inForce(): Boolean =
        connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    /**
     * [inForce] now, then again at every `ACTION_RESTRICT_BACKGROUND_CHANGED` — a broadcast only a registered receiver
     * hears, so this holds while it is collected.
     */
    fun watch(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(inForce())
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(inForce())
        awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
}
