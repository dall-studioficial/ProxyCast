package dall.app.proxycast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

/**
 * BroadcastReceiver for Wi-Fi Direct events
 */
class WifiDirectReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "WifiDirectReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                // Check if Wi-Fi P2P is enabled
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.d(TAG, "Wi-Fi P2P state changed: enabled=$isEnabled")
                activity.onWifiP2pStateChanged(isEnabled)
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                // Request available peers from the Wi-Fi P2P manager
                Log.d(TAG, "Peers changed, requesting peer list")
                manager.requestPeers(channel) { peers ->
                    activity.onPeersAvailable(peers)
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                // Connection state changed
                Log.d(TAG, "Connection changed")
                manager.requestConnectionInfo(channel) { info ->
                    activity.onConnectionInfoAvailable(info)
                }
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                // This device's details have changed
                Log.d(TAG, "This device changed")
            }
        }
    }
}
