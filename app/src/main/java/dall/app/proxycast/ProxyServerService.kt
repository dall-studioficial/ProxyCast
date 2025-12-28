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
    }

    private var serverSocket: ServerSocket? = null
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
            .setContentText("HTTP CONNECT proxy on port $PROXY_PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startProxyServer() {
        isRunning = true
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(PROXY_PORT)
                Log.d(TAG, "Proxy server started on port $PROXY_PORT")
                
                while (isRunning && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        Log.d(TAG, "Client connected: ${clientSocket.inetAddress}")
                        
                        // Handle each client in a separate coroutine
                        launch {
                            handleClient(clientSocket)
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting proxy server", e)
            }
        }
    }

    private fun stopProxyServer() {
        isRunning = false
        try {
            serverSocket?.close()
            Log.d(TAG, "Proxy server stopped")
        } catch (e: IOException) {
            Log.e(TAG, "Error stopping proxy server", e)
        }
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
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
                    
                    // Relay data bidirectionally
                    val job1 = launch {
                        relay(clientSocket.getInputStream(), targetSocket.getOutputStream())
                    }
                    val job2 = launch {
                        relay(targetSocket.getInputStream(), clientSocket.getOutputStream())
                    }
                    
                    // Wait for both directions to complete
                    job1.join()
                    job2.join()
                    
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
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 443 else 443
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
}
