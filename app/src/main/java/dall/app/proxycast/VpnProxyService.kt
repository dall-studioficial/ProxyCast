package dall.app.proxycast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.IOException

/**
 * VPN service that routes all device traffic through the HTTP CONNECT proxy server.
 * This allows the client device to automatically use the proxy without manual configuration.
 */
class VpnProxyService : VpnService() {

    companion object {
        private const val TAG = "VpnProxyService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "VpnProxyChannel"
        const val ACTION_START_VPN = "dall.app.proxycast.START_VPN"
        const val ACTION_STOP_VPN = "dall.app.proxycast.STOP_VPN"
        const val EXTRA_PROXY_HOST = "proxy_host"
        const val EXTRA_PROXY_PORT = "proxy_port"
        
        // VPN configuration
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_PREFIX_LENGTH = 32
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_ROUTE_PREFIX = 0
        private const val VPN_MTU = 1500
        private const val VPN_DNS_PRIMARY = "1.1.1.1"
        private const val VPN_DNS_SECONDARY = "8.8.8.8"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socks: Tun2Socks? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isRunning = false
    private var proxyHost: String = ""
    private var proxyPort: Int = ProxyServerService.SOCKS5_PORT

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VPN -> {
                proxyHost = intent.getStringExtra(EXTRA_PROXY_HOST) ?: ""
                // Use SOCKS5 port for tun2socks
                proxyPort = ProxyServerService.SOCKS5_PORT
                
                if (proxyHost.isEmpty()) {
                    Log.e(TAG, "No proxy host provided")
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                Log.d(TAG, "Starting VPN with SOCKS5 proxy: $proxyHost:$proxyPort")
                startVpn()
            }
            ACTION_STOP_VPN -> {
                Log.d(TAG, "Stopping VPN")
                stopVpn()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "VPN Service destroying")
        stopVpn()
        serviceScope.cancel()
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.d(TAG, "VPN revoked by user")
        stopVpn()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Proxy Client",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN-based proxy client connection"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, VpnProxyService::class.java).apply {
            action = ACTION_STOP_VPN
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Proxy Active")
            .setContentText("Routing traffic through $proxyHost:$proxyPort")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_delete,
                "Disconnect",
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun startVpn() {
        if (isRunning) {
            Log.w(TAG, "VPN already running")
            return
        }

        try {
            // Build VPN interface with proper configuration
            val builder = Builder()
                .setSession("ProxyCast VPN")
                .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                .addRoute(VPN_ROUTE, VPN_ROUTE_PREFIX)
                .addDnsServer(VPN_DNS_PRIMARY)
                .addDnsServer(VPN_DNS_SECONDARY)
                .setMtu(VPN_MTU)
                .setBlocking(true) // Blocking mode for tun2socks

            // Establish VPN connection
            vpnInterface = builder.establish()
            
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface - builder.establish() returned null")
                stopSelf()
                return
            }

            isRunning = true
            
            // Start foreground service with notification
            try {
                val notification = createNotification()
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "VPN foreground service started with notification")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
                stopVpn()
                stopSelf()
                return
            }
            
            Log.d(TAG, "VPN interface established successfully, starting tun2socks")
            
            // Start tun2socks forwarding engine
            startTun2Socks()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN service", e)
            stopVpn()
            stopSelf()
        }
    }

    private fun stopVpn() {
        isRunning = false
        
        // Stop tun2socks
        tun2socks?.stop()
        tun2socks = null
        Log.d(TAG, "Tun2socks stopped")
        
        // Close VPN interface
        try {
            vpnInterface?.close()
            vpnInterface = null
            Log.d(TAG, "VPN stopped")
        } catch (e: IOException) {
            Log.e(TAG, "Error stopping VPN", e)
        }
    }
    
    /**
     * Start tun2socks forwarding engine
     */
    private fun startTun2Socks() {
        val vpnFd = vpnInterface
        if (vpnFd == null) {
            Log.e(TAG, "Cannot start tun2socks: VPN interface is null")
            return
        }
        
        try {
            tun2socks = Tun2Socks(
                tunFd = vpnFd,
                socksHost = proxyHost,
                socksPort = proxyPort,
                mtu = VPN_MTU
            )
            tun2socks?.start()
            Log.d(TAG, "Tun2socks started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tun2socks", e)
            stopVpn()
            stopSelf()
        }
    }
}
