package dall.app.proxycast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Foreground service that runs an HTTP CONNECT proxy server on port 8080
 */
class ProxyServerService : Service() {

    companion object {
        private const val TAG = "ProxyServerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ProxyServerChannel"
        const val PROXY_PORT = 8080
        const val SOCKS5_PORT = 1080
    }

    private var httpServerSocket: ServerSocket? = null
    private var socks5ServerSocket: ServerSocket? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service starting")
        
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        if (!isRunning) {
            startProxyServer()
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying")
        stopProxyServer()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proxy Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running Wi-Fi Direct Proxy Server"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Proxy Server Running")
            .setContentText("HTTP:$PROXY_PORT, SOCKS5:$SOCKS5_PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startProxyServer() {
        isRunning = true
        
        // Start HTTP CONNECT proxy
        serviceScope.launch {
            try {
                httpServerSocket = ServerSocket(PROXY_PORT)
                Log.d(TAG, "HTTP CONNECT proxy started on port $PROXY_PORT")
                
                val socket = httpServerSocket
                if (socket == null || socket.isClosed) {
                    Log.e(TAG, "HTTP server socket is null or closed")
                    return@launch
                }
                
                while (isRunning && !socket.isClosed) {
                    try {
                        val clientSocket = socket.accept()
                        Log.d(TAG, "HTTP client connected: ${clientSocket.inetAddress}")
                        
                        // Handle each client in a separate coroutine
                        launch {
                            handleHttpConnectClient(clientSocket)
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting HTTP client connection", e)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting HTTP proxy server", e)
            }
        }
        
        // Start SOCKS5 proxy
        serviceScope.launch {
            try {
                socks5ServerSocket = ServerSocket(SOCKS5_PORT)
                Log.d(TAG, "SOCKS5 proxy started on port $SOCKS5_PORT")
                
                val socket = socks5ServerSocket
                if (socket == null || socket.isClosed) {
                    Log.e(TAG, "SOCKS5 server socket is null or closed")
                    return@launch
                }
                
                while (isRunning && !socket.isClosed) {
                    try {
                        val clientSocket = socket.accept()
                        Log.d(TAG, "SOCKS5 client connected: ${clientSocket.inetAddress}")
                        
                        // Handle each client in a separate coroutine
                        launch {
                            handleSocks5Client(clientSocket)
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting SOCKS5 client connection", e)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting SOCKS5 proxy server", e)
            }
        }
    }

    private fun stopProxyServer() {
        isRunning = false
        try {
            httpServerSocket?.close()
            socks5ServerSocket?.close()
            Log.d(TAG, "Proxy servers stopped")
        } catch (e: IOException) {
            Log.e(TAG, "Error stopping proxy servers", e)
        }
    }

    private suspend fun handleHttpConnectClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = clientSocket.getInputStream().bufferedReader()
            val output = clientSocket.getOutputStream()
            
            // Read the HTTP request line
            val requestLine = input.readLine()
            if (requestLine == null) {
                clientSocket.close()
                return@withContext
            }
            
            Log.d(TAG, "Request: $requestLine")
            
            // Parse CONNECT request
            val parts = requestLine.split(" ")
            if (parts.size >= 2 && parts[0] == "CONNECT") {
                val hostPort = parts[1]
                val (host, port) = parseHostPort(hostPort)
                
                // Read and discard headers
                while (true) {
                    val line = input.readLine()
                    if (line.isNullOrEmpty()) break
                }
                
                // Connect to target server
                try {
                    val targetSocket = Socket()
                    targetSocket.connect(InetSocketAddress(host, port), 10000)
                    
                    // Send success response
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    output.flush()
                    
                    Log.d(TAG, "Tunnel established to $host:$port")
                    
                    // Relay data bidirectionally with proper cancellation
                    coroutineScope {
                        val job1 = launch {
                            relay(clientSocket.getInputStream(), targetSocket.getOutputStream())
                        }
                        val job2 = launch {
                            relay(targetSocket.getInputStream(), clientSocket.getOutputStream())
                        }
                        
                        // Wait for both directions to complete
                        try {
                            job1.join()
                            job2.join()
                        } catch (e: Exception) {
                            // If one fails, cancel the other
                            job1.cancel()
                            job2.cancel()
                            throw e
                        }
                    }
                    
                    targetSocket.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error connecting to target: $host:$port", e)
                    output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                    output.flush()
                }
            } else {
                // Not a CONNECT request
                output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                output.flush()
            }
            
            clientSocket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
            try {
                clientSocket.close()
            } catch (ignored: IOException) {}
        }
    }

    private fun parseHostPort(hostPort: String): Pair<String, Int> {
        val parts = hostPort.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) {
            parts[1].toIntOrNull().also { 
                if (it == null) {
                    Log.w(TAG, "Invalid port in host:port '$hostPort', defaulting to 443")
                }
            } ?: 443
        } else {
            443
        }
        return Pair(host, port)
    }

    private suspend fun relay(input: java.io.InputStream, output: java.io.OutputStream) {
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (e: IOException) {
            // Connection closed or error
        }
    }

    /**
     * Handle SOCKS5 proxy client connection
     * Implements basic SOCKS5 protocol (RFC 1928)
     */
    private suspend fun handleSocks5Client(clientSocket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            
            // Step 1: Read SOCKS5 greeting (version + nmethods + methods)
            val version = input.read()
            if (version != 5) {
                // Not SOCKS5
                Log.w(TAG, "SOCKS5: Invalid version: $version")
                clientSocket.close()
                return@withContext
            }
            
            val nmethods = input.read()
            if (nmethods <= 0) {
                Log.w(TAG, "SOCKS5: No authentication methods provided")
                clientSocket.close()
                return@withContext
            }
            
            // Read authentication methods
            val methods = ByteArray(nmethods)
            var bytesRead = 0
            while (bytesRead < nmethods) {
                val read = input.read(methods, bytesRead, nmethods - bytesRead)
                if (read == -1) {
                    Log.w(TAG, "SOCKS5: Unexpected end of stream reading methods")
                    clientSocket.close()
                    return@withContext
                }
                bytesRead += read
            }
            
            // Step 2: Send authentication method selection (no authentication = 0x00)
            output.write(byteArrayOf(5, 0)) // SOCKS5, No authentication required
            output.flush()
            
            // Step 3: Read SOCKS5 request (VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT)
            val requestVersion = input.read()
            if (requestVersion != 5) {
                Log.w(TAG, "SOCKS5: Invalid request version: $requestVersion")
                clientSocket.close()
                return@withContext
            }
            
            val cmd = input.read()
            val rsv = input.read()
            val atyp = input.read()
            
            // Parse destination address
            val destAddress: String
            val destPort: Int
            
            when (atyp) {
                1 -> {
                    // IPv4 address (4 bytes)
                    val addr = ByteArray(4)
                    var bytesRead = 0
                    while (bytesRead < 4) {
                        val read = input.read(addr, bytesRead, 4 - bytesRead)
                        if (read == -1) {
                            Log.w(TAG, "SOCKS5: Unexpected end of stream reading IPv4")
                            clientSocket.close()
                            return@withContext
                        }
                        bytesRead += read
                    }
                    destAddress = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                3 -> {
                    // Domain name
                    val domainLength = input.read()
                    if (domainLength <= 0) {
                        Log.w(TAG, "SOCKS5: Invalid domain length")
                        clientSocket.close()
                        return@withContext
                    }
                    val domainBytes = ByteArray(domainLength)
                    var bytesRead = 0
                    while (bytesRead < domainLength) {
                        val read = input.read(domainBytes, bytesRead, domainLength - bytesRead)
                        if (read == -1) {
                            Log.w(TAG, "SOCKS5: Unexpected end of stream reading domain")
                            clientSocket.close()
                            return@withContext
                        }
                        bytesRead += read
                    }
                    destAddress = String(domainBytes)
                }
                4 -> {
                    // IPv6 address (16 bytes)
                    val addr = ByteArray(16)
                    var bytesRead = 0
                    while (bytesRead < 16) {
                        val read = input.read(addr, bytesRead, 16 - bytesRead)
                        if (read == -1) {
                            Log.w(TAG, "SOCKS5: Unexpected end of stream reading IPv6")
                            clientSocket.close()
                            return@withContext
                        }
                        bytesRead += read
                    }
                    // Format IPv6 address properly
                    destAddress = formatIpv6Address(addr)
                    Log.d(TAG, "SOCKS5: IPv6 destination: $destAddress")
                }
                else -> {
                    Log.w(TAG, "SOCKS5: Unsupported address type: $atyp")
                    // Send failure response
                    output.write(byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    clientSocket.close()
                    return@withContext
                }
            }
            
            // Read destination port (2 bytes, big-endian)
            val portHigh = input.read()
            val portLow = input.read()
            destPort = (portHigh shl 8) or portLow
            
            Log.d(TAG, "SOCKS5: CMD=$cmd, Destination=$destAddress:$destPort")
            
            // Step 4: Handle command
            when (cmd) {
                1 -> {
                    // CONNECT command
                    try {
                        val targetSocket = Socket()
                        targetSocket.connect(InetSocketAddress(destAddress, destPort), 10000)
                        
                        // Send success response
                        // VER, REP, RSV, ATYP, BND.ADDR, BND.PORT
                        val successResponse = byteArrayOf(
                            5, // VER
                            0, // REP (succeeded)
                            0, // RSV
                            1, // ATYP (IPv4)
                            0, 0, 0, 0, // BND.ADDR (0.0.0.0)
                            0, 0 // BND.PORT (0)
                        )
                        output.write(successResponse)
                        output.flush()
                        
                        Log.d(TAG, "SOCKS5: Tunnel established to $destAddress:$destPort")
                        
                        // Relay data bidirectionally
                        coroutineScope {
                            val job1 = launch {
                                relay(clientSocket.getInputStream(), targetSocket.getOutputStream())
                            }
                            val job2 = launch {
                                relay(targetSocket.getInputStream(), clientSocket.getOutputStream())
                            }
                            
                            try {
                                job1.join()
                                job2.join()
                            } catch (e: Exception) {
                                job1.cancel()
                                job2.cancel()
                                throw e
                            }
                        }
                        
                        targetSocket.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "SOCKS5: Error connecting to target: $destAddress:$destPort", e)
                        // Send failure response
                        output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                        output.flush()
                    }
                }
                2 -> {
                    // BIND command - not supported in this POC
                    Log.w(TAG, "SOCKS5: BIND command not supported")
                    output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                    output.flush()
                }
                3 -> {
                    // UDP ASSOCIATE - not supported in this POC
                    Log.w(TAG, "SOCKS5: UDP ASSOCIATE not supported")
                    output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                    output.flush()
                }
                else -> {
                    Log.w(TAG, "SOCKS5: Unknown command: $cmd")
                    output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                    output.flush()
                }
            }
            
            clientSocket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SOCKS5 client", e)
            try {
                clientSocket.close()
            } catch (ignored: IOException) {}
        }
    }

    /**
     * Format IPv6 address from 16-byte array to standard string notation
     */
    private fun formatIpv6Address(addr: ByteArray): String {
        require(addr.size == 16) { "IPv6 address must be 16 bytes" }
        
        // Convert bytes to hex groups
        val groups = mutableListOf<String>()
        for (i in 0 until 16 step 2) {
            val group = ((addr[i].toInt() and 0xFF) shl 8) or (addr[i + 1].toInt() and 0xFF)
            groups.add(group.toString(16))
        }
        
        // Join with colons
        return groups.joinToString(":")
    }
}
