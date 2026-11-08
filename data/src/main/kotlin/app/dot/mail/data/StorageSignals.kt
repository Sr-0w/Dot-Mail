package app.dot.mail.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** Observed only while a settings/progress screen is collecting; no polling or permanent receiver. */
internal fun storageSignals(context:Context):Flow<Unit> = merge(idleNetworks(context).map { Unit },callbackFlow {
    val receiver=object:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) { trySend(Unit) } }
    ContextCompat.registerReceiver(context,receiver,IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED); addAction(Intent.ACTION_BATTERY_CHANGED) },ContextCompat.RECEIVER_NOT_EXPORTED)
    trySend(Unit)
    awaitClose { runCatching { context.unregisterReceiver(receiver) } }
}).onStart { emit(Unit) }
