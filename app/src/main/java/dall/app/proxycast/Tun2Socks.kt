package dall.app.proxycast

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin-based tun2socks implementation for routing TUN interface traffic through SOCKS5 proxy.
 * This provides functionality similar to go-tun2socks but implemented in pure Kotlin.
 */
class Tun2Socks(
    private val tunFd: ParcelFileDescriptor,
    private val socksHost: String,
    private val socksPort: Int,
    private val mtu: Int = 1500
) {
    companion object {
        private const val TAG = "Tun2Socks"
        private const val IPV4_VERSION = 4
        private const val IP_PROTOCOL_OFFSET = 9
        private const val TCP_PROTOCOL = 6
        private const val UDP_PROTOCOL = 17
        private const val IP_DEST_OFFSET = 16
        private const val IP_SRC_OFFSET = 12
        
        // SOCKS5 constants
        private const val SOCKS_VERSION = 5
        private const val NO_AUTH = 0
        private const val CONNECT = 1
        private const val IPV4 = 1
        private const val UDP_ASSOCIATE = 3
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isRunning = false
    
    // Connection tracking: (srcIP:srcPort:dstIP:dstPort) -> Socket
    private val tcpConnections = ConcurrentHashMap<String, Socket>()
    
    /**
     * Start the tun2socks forwarding engine
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "Tun2Socks already running")
            return
        }
        
        isRunning = true
        Log.d(TAG, "Starting tun2socks: SOCKS5 proxy at $socksHost:$socksPort")
        
        scope.launch {
            processPackets()
        }
    }
    
    /**
     * Stop the tun2socks forwarding engine and close all connections
     */
    fun stop() {
        isRunning = false
        
        // Close all TCP connections
        tcpConnections.values.forEach { socket ->
            try {
                socket.close()
            } catch (ignored: IOException) {}
        }
        tcpConnections.clear()
        
        scope.cancel()
        Log.d(TAG, "Tun2Socks stopped")
    }
    
    /**
     * Main packet processing loop - reads from TUN and dispatches to handlers
     */
    private suspend fun processPackets() = withContext(Dispatchers.IO) {
        try {
            val inputStream = FileInputStream(tunFd.fileDescriptor)
            val outputStream = FileOutputStream(tunFd.fileDescriptor)
            val buffer = ByteBuffer.allocate(mtu)
            
            Log.d(TAG, "Packet processing loop started")
            
            while (isRunning && tunFd.fileDescriptor.valid()) {
                try {
                    val length = inputStream.read(buffer.array())
                    
                    if (length > 0) {
                        buffer.limit(length)
                        val packet = buffer.array().copyOf(length)
                        
                        // Process packet asynchronously
                        launch {
                            handlePacket(packet, outputStream)
                        }
                        
                        buffer.clear()
                    }
                } catch (e: IOException) {
                    if (isRunning) {
                        Log.e(TAG, "Error reading from TUN", e)
                    }
                    break
                }
            }
            
            Log.d(TAG, "Packet processing loop ended")
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in packet processing", e)
        }
    }
    
    /**
     * Handle individual IP packet
     */
    private suspend fun handlePacket(packet: ByteArray, outputStream: FileOutputStream) {
        if (packet.size < 20) return // Minimum IP header size
        
        try {
            // Parse IP header
            val version = (packet[0].toInt() shr 4) and 0x0F
            if (version != IPV4_VERSION) {
                // Only IPv4 supported in this POC
                return
            }
            
            val ihl = (packet[0].toInt() and 0x0F) * 4
            val protocol = packet[IP_PROTOCOL_OFFSET].toInt() and 0xFF
            
            // Extract source and destination IPs
            val srcIp = formatIp(packet, IP_SRC_OFFSET)
            val dstIp = formatIp(packet, IP_DEST_OFFSET)
            
            when (protocol) {
                TCP_PROTOCOL -> handleTcpPacket(packet, ihl, srcIp, dstIp, outputStream)
                UDP_PROTOCOL -> handleUdpPacket(packet, ihl, srcIp, dstIp, outputStream)
                else -> {
                    // Unsupported protocol - drop silently
                    Log.v(TAG, "Dropping unsupported protocol: $protocol")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling packet", e)
        }
    }
    
    /**
     * Handle TCP packet - forward through SOCKS5 CONNECT
     */
    private suspend fun handleTcpPacket(
        packet: ByteArray,
        ihl: Int,
        srcIp: String,
        dstIp: String,
        outputStream: FileOutputStream
    ) = withContext(Dispatchers.IO) {
        try {
            if (packet.size < ihl + 20) return@withContext // Need at least TCP header
            
            // Parse TCP header
            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            
            val connectionKey = "$srcIp:$srcPort:$dstIp:$dstPort"
            
            // Check if this is a SYN packet (new connection)
            val flags = packet[ihl + 13].toInt() and 0xFF
            val syn = (flags and 0x02) != 0
            val fin = (flags and 0x01) != 0
            val rst = (flags and 0x04) != 0
            
            if (syn && !tcpConnections.containsKey(connectionKey)) {
                // New connection - establish SOCKS5 tunnel
                Log.d(TAG, "New TCP connection: $srcIp:$srcPort -> $dstIp:$dstPort")
                
                try {
                    val socket = connectViaSocks5(dstIp, dstPort)
                    tcpConnections[connectionKey] = socket
                    
                    // Start relaying data for this connection
                    launch {
                        relayTcpConnection(socket, connectionKey, outputStream)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to establish SOCKS5 connection: $e")
                    // Send RST back to client
                }
            } else if (fin || rst) {
                // Connection terminating
                tcpConnections.remove(connectionKey)?.close()
            } else {
                // Data packet - forward through existing connection
                val socket = tcpConnections[connectionKey]
                if (socket != null && !socket.isClosed) {
                    // Extract TCP payload and send
                    val tcpHeaderLen = ((packet[ihl + 12].toInt() shr 4) and 0x0F) * 4
                    val payloadOffset = ihl + tcpHeaderLen
                    if (payloadOffset < packet.size) {
                        val payload = packet.copyOfRange(payloadOffset, packet.size)
                        socket.getOutputStream().write(payload)
                        socket.getOutputStream().flush()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling TCP packet", e)
        }
    }
    
    /**
     * Handle UDP packet - forward through SOCKS5 UDP ASSOCIATE (simplified)
     */
    private suspend fun handleUdpPacket(
        packet: ByteArray,
        ihl: Int,
        srcIp: String,
        dstIp: String,
        outputStream: FileOutputStream
    ) {
        // UDP support is complex with SOCKS5 - simplified or skip for POC
        Log.v(TAG, "UDP packet from $srcIp to $dstIp (not fully implemented in POC)")
    }
    
    /**
     * Establish SOCKS5 connection to target
     */
    private suspend fun connectViaSocks5(targetHost: String, targetPort: Int): Socket = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.connect(InetSocketAddress(socksHost, socksPort), 5000)
        
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        
        // SOCKS5 handshake
        // 1. Send greeting
        output.write(byteArrayOf(
            SOCKS_VERSION.toByte(),
            1, // Number of methods
            NO_AUTH.toByte()
        ))
        output.flush()
        
        // 2. Read method selection
        val greeting = ByteArray(2)
        input.read(greeting)
        
        if (greeting[0] != SOCKS_VERSION.toByte() || greeting[1] != NO_AUTH.toByte()) {
            throw IOException("SOCKS5 auth failed")
        }
        
        // 3. Send CONNECT request
        val request = ByteArray(10 + targetHost.length)
        request[0] = SOCKS_VERSION.toByte()
        request[1] = CONNECT.toByte()
        request[2] = 0 // Reserved
        request[3] = 3 // Domain name
        request[4] = targetHost.length.toByte()
        
        targetHost.toByteArray().copyInto(request, 5)
        
        val portOffset = 5 + targetHost.length
        request[portOffset] = (targetPort shr 8).toByte()
        request[portOffset + 1] = targetPort.toByte()
        
        output.write(request)
        output.flush()
        
        // 4. Read response
        val response = ByteArray(10)
        input.read(response, 0, 4)
        
        if (response[1] != 0.toByte()) {
            throw IOException("SOCKS5 CONNECT failed: ${response[1]}")
        }
        
        // Read rest of response based on address type
        val addrType = response[3].toInt()
        when (addrType) {
            1 -> input.read(response, 4, 6) // IPv4: 4 bytes addr + 2 bytes port
            3 -> {
                val len = input.read()
                input.read(ByteArray(len + 2)) // Domain: len + name + port
            }
            4 -> input.read(response, 4, 18) // IPv6: 16 bytes addr + 2 bytes port
        }
        
        Log.d(TAG, "SOCKS5 connection established to $targetHost:$targetPort")
        socket
    }
    
    /**
     * Relay data from SOCKS5 socket back to TUN
     */
    private suspend fun relayTcpConnection(socket: Socket, connectionKey: String, outputStream: FileOutputStream) {
        try {
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            
            while (isRunning && !socket.isClosed) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                
                // In a full implementation, we'd reconstruct IP+TCP packets
                // For this POC, we're simplifying - the VPN might drop some packets
                // A production implementation would need a full TCP/IP stack like lwIP
                
                Log.v(TAG, "Received $bytesRead bytes from remote for $connectionKey")
            }
        } catch (e: IOException) {
            // Connection closed
        } finally {
            tcpConnections.remove(connectionKey)
            try {
                socket.close()
            } catch (ignored: IOException) {}
        }
    }
    
    /**
     * Format IP address from packet bytes
     */
    private fun formatIp(packet: ByteArray, offset: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            packet[offset].toInt() and 0xFF,
            packet[offset + 1].toInt() and 0xFF,
            packet[offset + 2].toInt() and 0xFF,
            packet[offset + 3].toInt() and 0xFF
        )
    }
}
