package dall.app.proxycast

import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Lightweight SOCKS5 proxy server for tun2socks integration.
 * Implements SOCKS5 protocol (RFC 1928) with CONNECT command support.
 */
class Socks5Server(private val port: Int) {
    
    companion object {
        private const val TAG = "Socks5Server"
        private const val SOCKS_VERSION = 5
        private const val NO_AUTH = 0
        private const val CONNECT = 1
        private const val IPV4 = 1
        private const val DOMAIN = 3
        private const val IPV6 = 4
    }
    
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isRunning = false
    
    fun start() {
        if (isRunning) {
            Log.w(TAG, "SOCKS5 server already running")
            return
        }
        
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "SOCKS5 server started on port $port")
                
                while (isRunning && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        if (clientSocket != null) {
                            Log.d(TAG, "SOCKS5 client connected: ${clientSocket.inetAddress}")
                            launch {
                                handleClient(clientSocket)
                            }
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting SOCKS5 client", e)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting SOCKS5 server", e)
            }
        }
    }
    
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            scope.cancel()
            Log.d(TAG, "SOCKS5 server stopped")
        } catch (e: IOException) {
            Log.e(TAG, "Error stopping SOCKS5 server", e)
        }
    }
    
    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()
            
            // 1. Authentication negotiation
            if (!handleAuth(input, output)) {
                clientSocket.close()
                return@withContext
            }
            
            // 2. Handle CONNECT request
            val target = handleRequest(input, output)
            if (target == null) {
                clientSocket.close()
                return@withContext
            }
            
            // 3. Connect to target and relay traffic
            connectAndRelay(clientSocket, target.first, target.second)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SOCKS5 client", e)
        } finally {
            try {
                clientSocket.close()
            } catch (ignored: IOException) {}
        }
    }
    
    private fun handleAuth(input: InputStream, output: OutputStream): Boolean {
        try {
            // Read client greeting
            val version = input.read()
            if (version != SOCKS_VERSION) {
                Log.e(TAG, "Unsupported SOCKS version: $version")
                return false
            }
            
            val nMethods = input.read()
            val methods = ByteArray(nMethods)
            input.read(methods)
            
            // We only support NO_AUTH
            if (!methods.contains(NO_AUTH.toByte())) {
                // No acceptable methods
                output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0xFF.toByte()))
                return false
            }
            
            // Accept NO_AUTH
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_AUTH.toByte()))
            output.flush()
            
            return true
        } catch (e: IOException) {
            Log.e(TAG, "Error in auth negotiation", e)
            return false
        }
    }
    
    private fun handleRequest(input: InputStream, output: OutputStream): Pair<String, Int>? {
        try {
            // Read request
            val version = input.read()
            val command = input.read()
            val reserved = input.read()
            val addressType = input.read()
            
            if (version != SOCKS_VERSION || command != CONNECT) {
                // Send failure response
                sendResponse(output, 0x07) // Command not supported
                return null
            }
            
            // Parse address
            val (host, port) = when (addressType) {
                IPV4 -> {
                    val addr = ByteArray(4)
                    input.read(addr)
                    val host = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    val port = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
                    Pair(host, port)
                }
                DOMAIN -> {
                    val len = input.read()
                    val domain = ByteArray(len)
                    input.read(domain)
                    val host = String(domain)
                    val port = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
                    Pair(host, port)
                }
                IPV6 -> {
                    // Read 16 bytes for IPv6 but we'll simplify for this POC
                    val addr = ByteArray(16)
                    input.read(addr)
                    val port = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
                    // Simplified IPv6 handling
                    return null
                }
                else -> {
                    sendResponse(output, 0x08) // Address type not supported
                    return null
                }
            }
            
            Log.d(TAG, "SOCKS5 CONNECT to $host:$port")
            
            // Send success response
            sendResponse(output, 0x00)
            
            return Pair(host, port)
            
        } catch (e: IOException) {
            Log.e(TAG, "Error parsing SOCKS5 request", e)
            return null
        }
    }
    
    private fun sendResponse(output: OutputStream, status: Int) {
        try {
            // SOCKS5 response: VER(1) REP(1) RSV(1) ATYP(1) BND.ADDR(var) BND.PORT(2)
            val response = byteArrayOf(
                SOCKS_VERSION.toByte(),
                status.toByte(),
                0x00, // Reserved
                IPV4.toByte(),
                0, 0, 0, 0, // Bind address (0.0.0.0)
                0, 0 // Bind port (0)
            )
            output.write(response)
            output.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error sending SOCKS5 response", e)
        }
    }
    
    private suspend fun connectAndRelay(clientSocket: Socket, host: String, port: Int) {
        var targetSocket: Socket? = null
        try {
            targetSocket = Socket()
            targetSocket.connect(InetSocketAddress(host, port), 10000)
            
            Log.d(TAG, "Connected to target $host:$port")
            
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
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Error connecting to target $host:$port", e)
        } finally {
            try {
                targetSocket?.close()
            } catch (ignored: IOException) {}
        }
    }
    
    private suspend fun relay(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (e: IOException) {
            // Connection closed
        }
    }
}
