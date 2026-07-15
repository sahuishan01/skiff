package com.transdecoder

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import java.io.File
import java.io.RandomAccessFile
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
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
        var peerIpAddress: String? = null
        var instance: SkiffBackgroundService? = null

        fun reconnect(context: Context) {
            connectionStatus.value = "Connecting..."
            val service = instance
            if (service != null) {
                webSocketClient?.disconnect()
                webSocketClient = null
                service.initializeWebSocket()
            } else {
                val intent = Intent(context, SkiffBackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
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

            // Share local IP address with sender via IceCandidate payload
            val localIp = getLocalIpAddress() ?: "127.0.0.1"
            webSocketClient?.sendMessage(WsMessage.IceCandidate(senderId, "LOCAL_IP:$localIp"))

            activeIncomingRequest.value = null
        }

        fun rejectPairRequest(senderId: String) {
            webSocketClient?.sendMessage(WsMessage.RejectRequest(senderId))
            activeIncomingRequest.value = null
        }

        // Fetch local Wi-Fi / Ethernet interface IPv4 address
        fun getLocalIpAddress(): String? {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress) {
                            val sAddr = addr.hostAddress
                            val isIPv4 = sAddr.indexOf(':') < 0
                            if (isIPv4) return sAddr
                        }
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            return null
        }

        // Send file data using TCP socket client connection
        fun sendFileTcp(context: Context, fileId: String, uri: Uri) {
            val targetIp = peerIpAddress ?: "127.0.0.1" // Fallback to localhost if testing on same device
            val dbInstance = AppDatabase.getDatabase(context)
            val webSocket = webSocketClient

            CoroutineScope(Dispatchers.IO).launch {
                var socket: Socket? = null
                try {
                    // Delay to let the receiver start its server
                    kotlinx.coroutines.delay(1000)
                    socket = Socket(targetIp, 8096)
                    val output = socket.getOutputStream()
                    val writer = java.io.PrintWriter(output, true)

                    val record = dbInstance.transferDao().getTransferById(fileId) ?: return@launch
                    val startOffset = 0L

                    // Send header info: "FILE_ID|FILE_HASH|START_OFFSET"
                    writer.println("${record.fileId}|${record.fileHash}|$startOffset")

                    // Stream file data from Android ContentResolver (SAF URI)
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val buffer = ByteArray(64 * 1024) // 64KB buffer blocks
                        var bytesRead: Int
                        var totalSent = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalSent += bytesRead

                            dbInstance.transferDao().updateProgress(
                                fileId = fileId,
                                bytesTransferred = totalSent,
                                status = if (totalSent >= record.fileSize) TransferStatus.COMPLETED else TransferStatus.TRANSFERRING
                            )

                            // Update signaling server to relay progress to peer UI
                            webSocket?.sendMessage(
                                WsMessage.UpdateProgress(
                                    file_id = fileId,
                                    bytes_transferred = totalSent,
                                    status = if (totalSent == record.fileSize) "Completed" else "Transferring"
                                )
                            )
                        }
                    }
                    output.flush()
                } catch (e: Exception) {
                    e.printStackTrace()
                    dbInstance.transferDao().updateProgress(fileId, 0L, TransferStatus.FAILED)
                } finally {
                    socket?.close()
                }
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var db: AppDatabase
    private var serverSocket: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.getDatabase(this)
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Skiff P2P is active"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Skiff P2P is active"))
        }
        isServiceRunning = true

        initializeWebSocket()
        startTcpServer()
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

    // Start background TCP socket listener
    private fun startTcpServer() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(8096).apply {
                    reuseAddress = true
                }
                while (isServiceRunning) {
                    val socket = serverSocket?.accept() ?: break
                    serviceScope.launch(Dispatchers.IO) {
                        handleIncomingTcpConnection(socket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun handleIncomingTcpConnection(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(input))

            val header = reader.readLine() ?: return
            val parts = header.split("|")
            if (parts.size < 3) return
            val fileId = parts[0]
            val fileHash = parts[1]
            val startOffset = parts[2].toLong()

            val record = db.transferDao().getTransferById(fileId)
            if (record != null) {
                val destinationFile = File(record.filePath)
                destinationFile.parentFile?.mkdirs()

                RandomAccessFile(destinationFile, "rw").use { raf ->
                    raf.seek(startOffset)
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalReceived = startOffset

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        totalReceived += bytesRead

                        db.transferDao().updateProgress(
                            fileId = fileId,
                            bytesTransferred = totalReceived,
                            status = if (totalReceived >= record.fileSize) TransferStatus.COMPLETED else TransferStatus.TRANSFERRING
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket.close()
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
                peerIpAddress = message.receiver_endpoint
            }
            is WsMessage.RequestRejected -> {
                connectionStatus.value = "Pairing Rejected"
                updateNotification("Pairing rejected")
            }
            is WsMessage.RelayedIceCandidate -> {
                // Intercept shared local IP address payload
                if (message.candidate.startsWith("LOCAL_IP:")) {
                    peerIpAddress = message.candidate.substringAfter("LOCAL_IP:")
                }
            }
            is WsMessage.IncomingTransfer -> {
                // Receiver side: insert file record as RECEIVE direction
                serviceScope.launch(Dispatchers.IO) {
                    val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: filesDir
                    message.files.forEach { file ->
                        val localFile = File(downloadsDir, file.file_name)
                        val newRecord = TransferEntity(
                            fileId = file.file_id,
                            sessionId = message.session_id,
                            fileName = file.file_name,
                            filePath = localFile.absolutePath,
                            fileSize = file.file_size,
                            fileHash = file.file_hash,
                            bytesTransferred = 0L,
                            status = TransferStatus.PENDING,
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
        serverSocket?.close()
        serverSocket = null
        isServiceRunning = false
        instance = null
        super.onDestroy()
    }
}
