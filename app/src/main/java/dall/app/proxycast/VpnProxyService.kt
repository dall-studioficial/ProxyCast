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
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_PREFIX_LENGTH = 0
        private const val VPN_MTU = 1500
        private const val VPN_DNS = "8.8.8.8"
        
        // IP and protocol constants
        private const val IPV4_VERSION = 4
        private const val IP_PROTOCOL_OFFSET = 9
        private const val TCP_PROTOCOL = 6
        private const val IP_DEST_OFFSET = 16
        private const val MAX_HEADER_LINES = 50 // Prevent infinite loop in header reading
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
            // Build VPN interface
            val builder = Builder()
                .setSession("ProxyCast VPN")
                .addAddress(VPN_ADDRESS, 24)
                .addRoute(VPN_ROUTE, VPN_PREFIX_LENGTH)
                .addDnsServer(VPN_DNS)
                .setMtu(VPN_MTU)
                .setBlocking(false)

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
                // Clean up and rethrow
                vpnInterface?.close()
                vpnInterface = null
                isRunning = false
                throw e
            }
            
            Log.d(TAG, "VPN interface established successfully, starting packet processing")
            
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
            
            while (isRunning && vpnFd.fileDescriptor.valid()) {
                // Read packet from VPN interface
                val length = inputStream.read(buffer.array())
                
                if (length > 0) {
                    buffer.limit(length)
                    
                    // Parse and process the packet
                    val packet = buffer.array().copyOf(length)
                    processPacket(packet, outputStream)
                    
                    buffer.clear()
                }
            }
            
            Log.d(TAG, "Packet processing loop ended")
        } catch (e: IOException) {
            if (isRunning) {
                Log.e(TAG, "Error processing packets", e)
            }
        }
    }

    private suspend fun processPacket(packet: ByteArray, outputStream: FileOutputStream) {
        try {
            // Parse IP header to get destination
            if (packet.size < 20) return
            
            val version = (packet[0].toInt() shr 4) and 0x0F
            if (version != IPV4_VERSION) {
                // Only handle IPv4 for this POC
                Log.d(TAG, "Ignoring non-IPv4 packet")
                return
            }
            
            val protocol = packet[IP_PROTOCOL_OFFSET].toInt() and 0xFF
            
            // Extract destination IP
            val destIp = String.format(
                "%d.%d.%d.%d",
                packet[IP_DEST_OFFSET].toInt() and 0xFF,
                packet[IP_DEST_OFFSET + 1].toInt() and 0xFF,
                packet[IP_DEST_OFFSET + 2].toInt() and 0xFF,
                packet[IP_DEST_OFFSET + 3].toInt() and 0xFF
            )
            
            // For this POC, we'll route TCP packets through the proxy
            if (protocol == TCP_PROTOCOL) {
                // Extract destination port
                val ihl = (packet[0].toInt() and 0x0F) * 4
                if (packet.size < ihl + 4) return
                
                val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                
                Log.d(TAG, "TCP packet to $destIp:$destPort - routing through proxy")
                
                // Route through proxy (simplified - in production would need full TCP stack)
                routeThroughProxy(destIp, destPort, packet, outputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet", e)
        }
    }

    private suspend fun routeThroughProxy(
        destIp: String,
        destPort: Int,
        packet: ByteArray,
        outputStream: FileOutputStream
    ) = withContext(Dispatchers.IO) {
        try {
            // Connect to proxy server
            val proxySocket = Socket()
            proxySocket.connect(InetSocketAddress(proxyHost, proxyPort), 5000)
            
            // Send CONNECT request
            val connectRequest = "CONNECT $destIp:$destPort HTTP/1.1\r\n" +
                    "Host: $destIp:$destPort\r\n" +
                    "Proxy-Connection: keep-alive\r\n" +
                    "\r\n"
            
            proxySocket.getOutputStream().write(connectRequest.toByteArray())
            proxySocket.getOutputStream().flush()
            
            // Read proxy response
            val response = proxySocket.getInputStream().bufferedReader().readLine()
            
            // Check for proper HTTP 200 status line format
            if (response?.startsWith("HTTP/1.") == true && response.contains(" 200 ")) {
                Log.d(TAG, "Proxy connection established to $destIp:$destPort")
                
                // Forward data through proxy
                // Note: This is a simplified implementation
                // A production VPN would need a full TCP/IP stack implementation
                
                // Read remaining headers with limit to prevent hanging
                var headerCount = 0
                while (headerCount < MAX_HEADER_LINES) {
                    val line = proxySocket.getInputStream().bufferedReader().readLine()
                    if (line.isNullOrEmpty()) break
                    headerCount++
                }
                
                // Send original packet data through proxy
                // (In a real implementation, we'd extract the TCP payload and forward it)
                
            } else {
                Log.e(TAG, "Proxy connection failed: $response")
            }
            
            proxySocket.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error routing through proxy", e)
        }
    }
}
