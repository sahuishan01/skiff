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
    @SerialName("REQUEST_CONNECTION_BY_ID")
    data class RequestConnectionById(val target_device_id: String) : WsMessage()

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

    @Serializable
    @SerialName("INITIATE_TRANSFER")
    data class InitiateTransfer(
        val session_id: String,
        val receiver_device_id: String,
        val files: List<FileMetadataInput>
    ) : WsMessage()

    @Serializable
    @SerialName("UPDATE_PROGRESS")
    data class UpdateProgress(
        val file_id: String,
        val bytes_transferred: Long,
        val status: String // "pending", "transferring", "completed", "failed", "paused"
    ) : WsMessage()

    @Serializable
    @SerialName("TRANSFER_INITIATED")
    data class TransferInitiated(val session_id: String) : WsMessage()

    @Serializable
    @SerialName("INCOMING_TRANSFER")
    data class IncomingTransfer(
        val session_id: String,
        val sender_device_id: String,
        val files: List<FileMetadataInput>
    ) : WsMessage()

    @Serializable
    @SerialName("PROGRESS_UPDATED")
    data class ProgressUpdated(
        val file_id: String,
        val bytes_transferred: Long
    ) : WsMessage()

    @Serializable
    @SerialName("CANCEL_TRANSFER")
    data class CancelTransfer(
        val file_id: String,
        val target_device_id: String
    ) : WsMessage()

    @Serializable
    @SerialName("TRANSFER_CANCELLED")
    data class TransferCancelled(val file_id: String) : WsMessage()
}

@Serializable
data class FileMetadataInput(
    val file_id: String,
    val file_name: String,
    val file_path: String,
    val file_size: Long,
    val file_hash: String
)

class WebSocketClient(
    private val serverUrl: String,
    private val onMessageReceived: (WsMessage) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
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
                onError(java.lang.Exception("Connection closed by server: $reason"))
            }
        })
    }

    fun sendMessage(message: WsMessage): Boolean {
        val jsonText = json.encodeToString(message)
        val sent = webSocket?.send(jsonText) ?: false
        android.util.Log.d("SkiffWS", "Sent message (${message::class.simpleName}): $sent")
        return sent
    }

    fun disconnect() {
        webSocket?.close(1000, "App closed")
    }
}
