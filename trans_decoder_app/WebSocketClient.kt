package com.transdecoder.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.*
import okio.ByteString

@Serializable
sealed class WsMessage {
    @Serializable
    @SerialName("REGISTER")
    data class Register(val device_id: String) : WsMessage()

    @Serializable
    @SerialName("REQUEST_CONNECTION")
    data class RequestConnection(val target_code: String) : WsMessage()

    @Serializable
    @SerialName("ACCEPT_REQUEST")
    data class AcceptRequest(val sender_device_id: String) : WsMessage()

    @Serializable
    @SerialName("REJECT_REQUEST")
    data class RejectRequest(val sender_device_id: String) : WsMessage()

    @Serializable
    @SerialName("ICE_CANDIDATE")
    data class IceCandidate(
        val target_device_id: String,
        val candidate: String // JSON serialized string of candidate details
    ) : WsMessage()

    @Serializable
    @SerialName("REGISTERED")
    data class Registered(val device_code: String) : WsMessage()

    @Serializable
    @SerialName("INCOMING_REQUEST")
    data class IncomingRequest(
        val sender_device_id: String,
        val sender_code: String
    ) : WsMessage()

    @Serializable
    @SerialName("REQUEST_ACCEPTED")
    data class RequestAccepted(
        val receiver_device_id: String,
        val receiver_endpoint: String?
    ) : WsMessage()

    @Serializable
    @SerialName("REQUEST_REJECTED")
    data class RequestRejected(val reason: String) : WsMessage()

    @Serializable
    @SerialName("RELAYED_ICE_CANDIDATE")
    data class RelayedIceCandidate(
        val sender_device_id: String,
        val candidate: String
    ) : WsMessage()
}

class WebSocketClient(
    private val serverUrl: String,
    private val onMessageReceived: (WsMessage) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("Connected to Skiff Signaling Server")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = json.decodeFromString<WsMessage>(text)
                    onMessageReceived(message)
                } catch (e: Exception) {
                    println("Failed to parse message: $text. Error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })
    }

    fun sendMessage(message: WsMessage) {
        val jsonText = json.encodeToString(message)
        webSocket?.send(jsonText)
    }

    fun disconnect() {
        webSocket?.close(1000, "App closed")
    }
}
