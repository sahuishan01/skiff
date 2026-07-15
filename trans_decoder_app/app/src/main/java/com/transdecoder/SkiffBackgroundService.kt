package com.transdecoder

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.transdecoder.data.local.AppDatabase
import com.transdecoder.data.local.TransferEntity
import com.transdecoder.data.local.TransferStatus
import com.transdecoder.data.local.TransferDirection
import com.transdecoder.data.network.WebSocketClient
import com.transdecoder.data.network.WsMessage
import com.transdecoder.data.network.FileMetadataInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class SkiffBackgroundService : Service() {

    companion object {
        const val CHANNEL_ID = "SkiffBackgroundChannel"
        const val NOTIFICATION_ID = 101

        // Observable states
        val deviceCode = MutableStateFlow("Registering...")
        val connectionStatus = MutableStateFlow("Disconnected")
        val activeIncomingRequest = MutableStateFlow<Pair<String, String>?>(null)
        val activePeerDeviceId = MutableStateFlow<String?>(null)

        var webSocketClient: WebSocketClient? = null
        var isServiceRunning = false

        fun reconnect(context: Context) {
            connectionStatus.value = "Connecting..."
            webSocketClient?.disconnect()
            webSocketClient = null
            val intent = Intent(context, SkiffBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendPairRequest(targetCode: String) {
            connectionStatus.value = "Connecting to $targetCode..."
            webSocketClient?.sendMessage(WsMessage.RequestConnection(targetCode))
        }

        fun acceptPairRequest(senderId: String) {
            activePeerDeviceId.value = senderId
            connectionStatus.value = "Paired & Connected"
            webSocketClient?.sendMessage(WsMessage.AcceptRequest(senderId))
            activeIncomingRequest.value = null
        }

        fun rejectPairRequest(senderId: String) {
            webSocketClient?.sendMessage(WsMessage.RejectRequest(senderId))
            activeIncomingRequest.value = null
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Skiff P2P is active"))
        isServiceRunning = true

        initializeWebSocket()
    }

    private fun initializeWebSocket() {
        if (webSocketClient != null) return

        val prefs = getSharedPreferences("skiff_prefs", MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        val deviceId = id!!

        webSocketClient = WebSocketClient(
            serverUrl = Config.SIGNALING_SERVER_URL,
            onMessageReceived = { message ->
                serviceScope.launch(Dispatchers.Main) {
                    handleMessage(message)
                }
            },
            onError = { error ->
                serviceScope.launch(Dispatchers.Main) {
                    connectionStatus.value = "Connection Error"
                }
            }
        )
        webSocketClient?.connect()

        serviceScope.launch {
            kotlinx.coroutines.delay(1000)
            webSocketClient?.sendMessage(WsMessage.Register(deviceId))
        }
    }

    private fun handleMessage(message: WsMessage) {
        when (message) {
            is WsMessage.Registered -> {
                deviceCode.value = message.device_code
                connectionStatus.value = "Registered & Waiting"
                updateNotification("Sharing Code: ${message.device_code}")
            }
            is WsMessage.IncomingRequest -> {
                activeIncomingRequest.value = Pair(message.sender_device_id, message.sender_code)
            }
            is WsMessage.RequestAccepted -> {
                activePeerDeviceId.value = message.receiver_device_id
                connectionStatus.value = "Paired & Connected"
                updateNotification("Connected to Peer")
            }
            is WsMessage.RequestRejected -> {
                connectionStatus.value = "Pairing Rejected"
                updateNotification("Pairing rejected")
            }
            is WsMessage.IncomingTransfer -> {
                // Receiver side: insert file record as RECEIVE direction
                serviceScope.launch(Dispatchers.IO) {
                    message.files.forEach { file ->
                        val newRecord = TransferEntity(
                            fileId = file.file_id,
                            sessionId = message.session_id,
                            fileName = file.file_name,
                            filePath = file.file_path,
                            fileSize = file.file_size,
                            fileHash = file.file_hash,
                            bytesTransferred = 0L,
                            status = TransferStatus.TRANSFERRING,
                            direction = TransferDirection.RECEIVE,
                            peerDeviceId = message.sender_device_id
                        )
                        db.transferDao().insertTransfer(newRecord)
                    }
                }
            }
            is WsMessage.ProgressUpdated -> {
                // Receiver side: update incoming file bytes dynamically as received
                serviceScope.launch(Dispatchers.IO) {
                    val status = db.transferDao().getTransferById(message.file_id)?.let {
                        if (message.bytes_transferred >= it.fileSize) TransferStatus.COMPLETED else TransferStatus.TRANSFERRING
                    } ?: TransferStatus.TRANSFERRING
                    db.transferDao().updateProgress(message.file_id, message.bytes_transferred, status)
                }
            }
            else -> {}
        }
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⛵ Skiff P2P Share")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Skiff P2P Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        webSocketClient?.disconnect()
        webSocketClient = null
        isServiceRunning = false
        super.onDestroy()
    }
}
