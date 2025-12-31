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
 *
 * Note: Response packet reconstruction is not implemented in this POC.
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

    fun stop() {
        isRunning = false

        tcpConnections.values.forEach { socket ->
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
        tcpConnections.clear()

        scope.cancel()
        Log.d(TAG, "Tun2Socks stopped")
    }

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

    private suspend fun handlePacket(packet: ByteArray, outputStream: FileOutputStream) {
        if (packet.size < 20) return

        try {
            val version = (packet[0].toInt() shr 4) and 0x0F
            if (version != IPV4_VERSION) {
                return
            }

            val ihl = (packet[0].toInt() and 0x0F) * 4
            val protocol = packet[IP_PROTOCOL_OFFSET].toInt() and 0xFF

            val srcIp = formatIp(packet, IP_SRC_OFFSET)
            val dstIp = formatIp(packet, IP_DEST_OFFSET)

            when (protocol) {
                TCP_PROTOCOL -> handleTcpPacket(packet, ihl, srcIp, dstIp, outputStream)
                UDP_PROTOCOL -> handleUdpPacket(packet, ihl, srcIp, dstIp)
                else -> Log.v(TAG, "Dropping unsupported protocol: $protocol")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling packet", e)
        }
    }

    private suspend fun handleTcpPacket(
        packet: ByteArray,
        ihl: Int,
        srcIp: String,
        dstIp: String,
        outputStream: FileOutputStream
    ) = withContext(Dispatchers.IO) {
        try {
            if (packet.size < ihl + 20) return@withContext

            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

            val connectionKey = "$srcIp:$srcPort:$dstIp:$dstPort"

            val flags = packet[ihl + 13].toInt() and 0xFF
            val syn = (flags and 0x02) != 0
            val fin = (flags and 0x01) != 0
            val rst = (flags and 0x04) != 0

            if (syn && !tcpConnections.containsKey(connectionKey)) {
                Log.d(TAG, "New TCP connection: $srcIp:$srcPort -> $dstIp:$dstPort")

                try {
                    val socket = connectViaSocks5(dstIp, dstPort)
                    tcpConnections[connectionKey] = socket

                    launch {
                        relayTcpConnection(socket, connectionKey, outputStream)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to establish SOCKS5 connection: $e")
                    // TODO: Send RST packet back to client to properly terminate the connection
                }
            } else if (fin || rst) {
                tcpConnections.remove(connectionKey)?.close()
            } else {
                val socket = tcpConnections[connectionKey]
                if (socket != null && !socket.isClosed) {
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

    private suspend fun handleUdpPacket(
        packet: ByteArray,
        ihl: Int,
        srcIp: String,
        dstIp: String
    ) {
        Log.v(TAG, "UDP packet from $srcIp to $dstIp (not fully implemented in POC)")
    }

    private suspend fun connectViaSocks5(targetHost: String, targetPort: Int): Socket = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.connect(InetSocketAddress(socksHost, socksPort), 5000)

        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(),
                1,
                NO_AUTH.toByte()
            )
        )
        output.flush()

        val greeting = ByteArray(2)
        input.read(greeting)

        if (greeting[0] != SOCKS_VERSION.toByte() || greeting[1] != NO_AUTH.toByte()) {
            throw IOException("SOCKS5 auth failed")
        }

        val domainLen = targetHost.length
        val request = ByteArray(7 + domainLen)
        request[0] = SOCKS_VERSION.toByte()
        request[1] = CONNECT.toByte()
        request[2] = 0
        request[3] = 3
        request[4] = domainLen.toByte()

        targetHost.toByteArray().copyInto(request, 5)

        val portOffset = 5 + domainLen
        request[portOffset] = (targetPort shr 8).toByte()
        request[portOffset + 1] = targetPort.toByte()

        output.write(request)
        output.flush()

        val response = ByteArray(10)
        input.read(response, 0, 4)

        if (response[1] != 0.toByte()) {
            throw IOException("SOCKS5 CONNECT failed: ${response[1]}")
        }

        when (response[3].toInt()) {
            1 -> input.read(response, 4, 6)
            3 -> {
                val len = input.read()
                input.read(ByteArray(len + 2))
            }
            4 -> input.read(response, 4, 18)
        }

        Log.d(TAG, "SOCKS5 connection established to $targetHost:$targetPort")
        socket
    }

    /**
     * Relay data from SOCKS5 socket back to TUN.
     * NOTE: Response packet reconstruction is not implemented in this POC.
     */
    private suspend fun relayTcpConnection(socket: Socket, connectionKey: String, outputStream: FileOutputStream) {
        try {
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)

            while (isRunning && !socket.isClosed) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                // TODO: Reconstruct IP+TCP packets with proper headers
                Log.v(TAG, "Received $bytesRead bytes from remote for $connectionKey (packet reconstruction not implemented)")
            }
        } catch (_: IOException) {
        } finally {
            tcpConnections.remove(connectionKey)
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

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
