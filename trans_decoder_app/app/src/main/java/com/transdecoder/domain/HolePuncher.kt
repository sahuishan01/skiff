package com.transdecoder.domain

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HolePuncher(
    private val localPort: Int = 0 // Bind to any random port
) {
    private var socket: DatagramSocket? = null

    init {
        socket = DatagramSocket(localPort).apply {
            reuseAddress = true
        }
    }

    /**
     * Discovers the external IP and Port of this device by querying a STUN server.
     */
    suspend fun discoverPublicEndpoint(stunHost: String, stunPort: Int): Pair<String, Int> = withContext(Dispatchers.IO) {
        val currentSocket = socket ?: throw IllegalStateException("Socket not initialized")
        
        // Simple mock of STUN request packet format (RFC 5389 Binding request)
        val request = ByteArray(20)
        request[0] = 0x00.toByte() // Message Type: Binding Request
        request[1] = 0x01.toByte()
        // transaction ID (12 bytes) + magic cookie (4 bytes)
        
        val address = InetAddress.getByName(stunHost)
        val packet = DatagramPacket(request, request.size, address, stunPort)
        currentSocket.send(packet)

        val buffer = ByteArray(512)
        val responsePacket = DatagramPacket(buffer, buffer.size)
        currentSocket.soTimeout = 3000 // 3 seconds timeout
        
        try {
            currentSocket.receive(responsePacket)
            // Parse STUN XOR-MAPPED-ADDRESS attributes here to get public IP & Port
            // Returning stub for implementation reference
            return@withContext Pair(responsePacket.address.hostAddress, responsePacket.port)
        } catch (e: SocketTimeoutException) {
            throw Exception("STUN request timed out")
        }
    }

    /**
     * Actively punches a hole through firewalls by sending UDP pings to the peer.
     */
    suspend fun punchHole(peerIp: String, peerPort: Int): Boolean = withContext(Dispatchers.IO) {
        val currentSocket = socket ?: return@withContext false
        val peerAddress = InetAddress.getByName(peerIp)
        val pingData = "PING_HOLE".toByteArray()
        val packet = DatagramPacket(pingData, pingData.size, peerAddress, peerPort)
        
        println("Punching UDP hole to $peerIp:$peerPort...")

        // Send multiple pings to establish NAT mapping
        for (i in 1..10) {
            try {
                currentSocket.send(packet)
                Thread.sleep(100) // Delay between pings
            } catch (e: Exception) {
                println("Failed sending hole punch ping: ${e.message}")
            }
        }

        // Wait to listen for incoming ping from the peer
        val buffer = ByteArray(512)
        val response = DatagramPacket(buffer, buffer.size)
        currentSocket.soTimeout = 5000 // 5 seconds listen window
        
        try {
            currentSocket.receive(response)
            val msg = String(response.data, 0, response.length)
            if (msg == "PING_HOLE") {
                println("Successfully punched hole and connected to Peer!")
                return@withContext true
            }
        } catch (e: SocketTimeoutException) {
            println("Hole punch listening timed out. NAT traversal failed.")
        }
        
        return@withContext false
    }

    fun getSocket(): DatagramSocket? = socket

    fun close() {
        socket?.close()
        socket = null
    }
}
