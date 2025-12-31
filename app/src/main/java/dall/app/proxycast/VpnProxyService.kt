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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.*

/**
 * VPN service that routes all device traffic through a SOCKS5 proxy server.
 * 
 * **IMPORTANT - Current Implementation Status:**
 * This is a POC (Proof of Concept) implementation that demonstrates VPN setup with proper configuration.
 * The packet processing logic is simplified and NOT suitable for production use.
 * 
 * **For Production Use:**
 * A full implementation would require:
 * 1. Native tun2socks binaries (compiled for arm64-v8a, armeabi-v7a, x86, x86_64)
 * 2. JNI bridge to launch tun2socks with TUN file descriptor
 * 3. tun2socks would handle the full TCP/IP stack and SOCKS5 forwarding
 * 
 * **What This POC Demonstrates:**
 * - Proper VPN interface configuration (10.0.0.2/32, 0.0.0.0/0 route, DNS, MTU)
 * - VPN permission handling
 * - Foreground service with notification
 * - Basic packet capture and logging
 * - Integration with SOCKS5 proxy server
 * 
 * **Recommended Production Implementation:**
 * Use libraries like:
 * - xjasonlyu/tun2socks (Go-based, supports SOCKS5)
 * - badvpn-tun2socks (C-based, well-tested)
 * - hev-socks5-tunnel (C-based, lightweight)
 * 
 * See: https://github.com/xjasonlyu/tun2socks
 * See: https://github.com/ambrop72/badvpn
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
        
        // VPN configuration (as specified in requirements)
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE_PREFIX = "0.0.0.0"
        private const val VPN_ROUTE_PREFIX_LENGTH = 0
        private const val VPN_MTU = 1500
        private const val VPN_DNS_PRIMARY = "1.1.1.1"
        private const val VPN_DNS_SECONDARY = "8.8.8.8"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isRunning = false
    private var proxyHost: String = ""
    private var proxyPort: Int = ProxyServerService.PROXY_PORT

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VPN -> {
                proxyHost = intent.getStringExtra(EXTRA_PROXY_HOST) ?: ""
                proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, ProxyServerService.PROXY_PORT)
                
                if (proxyHost.isEmpty()) {
                    Log.e(TAG, "No proxy host provided")
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                Log.d(TAG, "Starting VPN with proxy: $proxyHost:$proxyPort")
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
                .addAddress(VPN_ADDRESS, 32) // Single IP address for VPN interface
                .addRoute(VPN_ROUTE_PREFIX, VPN_ROUTE_PREFIX_LENGTH) // Route all traffic (0.0.0.0/0)
                .addDnsServer(VPN_DNS_PRIMARY) // Cloudflare DNS
                .addDnsServer(VPN_DNS_SECONDARY) // Google DNS
                .setMtu(VPN_MTU)
                .setBlocking(true) // Use blocking mode for simpler implementation

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
                // Clean up and stop service
                stopVpn()
                stopSelf()
                return
            }
            
            Log.d(TAG, "VPN interface established successfully (10.0.0.2/32, route 0.0.0.0/0)")
            Log.d(TAG, "DNS: $VPN_DNS_PRIMARY, $VPN_DNS_SECONDARY, MTU: $VPN_MTU")
            Log.d(TAG, "Proxy: $proxyHost:$proxyPort (SOCKS5)")
            
            // Start packet processing
            serviceScope.launch {
                processPackets()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN service", e)
            stopVpn()
            stopSelf()
        }
    }

    private fun stopVpn() {
        isRunning = false
        try {
            vpnInterface?.close()
            vpnInterface = null
            Log.d(TAG, "VPN stopped")
        } catch (e: IOException) {
            Log.e(TAG, "Error stopping VPN", e)
        }
    }

    private suspend fun processPackets() = withContext(Dispatchers.IO) {
        val vpnFd = vpnInterface ?: return@withContext
        
        try {
            val inputStream = FileInputStream(vpnFd.fileDescriptor)
            val outputStream = FileOutputStream(vpnFd.fileDescriptor)
            
            val buffer = ByteBuffer.allocate(VPN_MTU)
            
            Log.d(TAG, "Starting packet processing loop")
            Log.w(TAG, "Note: This is a POC implementation. Packet forwarding is simplified.")
            Log.w(TAG, "For production, integrate native tun2socks binaries.")
            
            var packetCount = 0
            
            while (isRunning && vpnFd.fileDescriptor.valid()) {
                try {
                    // Read packet from VPN interface
                    val length = inputStream.read(buffer.array())
                    
                    if (length > 0) {
                        buffer.limit(length)
                        packetCount++
                        
                        // Log packet info periodically (every 100 packets)
                        if (packetCount % 100 == 0) {
                            Log.d(TAG, "Processed $packetCount packets through VPN")
                        }
                        
                        // Parse and log the packet (simplified for POC)
                        val packet = buffer.array().copyOf(length)
                        logPacketInfo(packet)
                        
                        // In a production implementation with tun2socks:
                        // - tun2socks would read packets from the TUN fd
                        // - Parse the TCP/IP headers
                        // - Establish SOCKS5 connections for each TCP flow
                        // - Forward data bidirectionally
                        // - Write response packets back to TUN fd
                        
                        // For this POC, we just log and drop packets
                        // This means the VPN will be active but traffic won't actually flow
                        
                        buffer.clear()
                    }
                } catch (e: IOException) {
                    if (isRunning) {
                        Log.e(TAG, "Error reading packet", e)
                    }
                }
            }
            
            Log.d(TAG, "Packet processing loop ended. Total packets: $packetCount")
        } catch (e: IOException) {
            if (isRunning) {
                Log.e(TAG, "Error processing packets", e)
            }
        }
    }

    /**
     * Log basic packet information for debugging
     * In production, this would be handled by tun2socks
     */
    private fun logPacketInfo(packet: ByteArray) {
        try {
            if (packet.size < 20) return
            
            val version = (packet[0].toInt() shr 4) and 0x0F
            if (version != 4) return // Only log IPv4 for simplicity
            
            val protocol = packet[9].toInt() and 0xFF
            val protocolName = when (protocol) {
                6 -> "TCP"
                17 -> "UDP"
                1 -> "ICMP"
                else -> "Other($protocol)"
            }
            
            // Extract destination IP
            val destIp = String.format(
                "%d.%d.%d.%d",
                packet[16].toInt() and 0xFF,
                packet[17].toInt() and 0xFF,
                packet[18].toInt() and 0xFF,
                packet[19].toInt() and 0xFF
            )
            
            // Log first packet to each destination (to avoid spam)
            // In production, tun2socks would handle all forwarding
            val key = "$destIp-$protocolName"
            if (!loggedDestinations.contains(key)) {
                loggedDestinations.add(key)
                Log.i(TAG, "VPN captured traffic: $protocolName -> $destIp (would forward via SOCKS5 in production)")
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
    }

    // Track logged destinations to avoid spam (limit size to prevent memory leak)
    private val loggedDestinations = Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 100 // Keep max 100 unique destinations
            }
        }
    )
}
