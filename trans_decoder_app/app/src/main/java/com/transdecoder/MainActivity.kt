package com.transdecoder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.transdecoder.data.local.AppDatabase
import com.transdecoder.data.local.TransferDirection
import com.transdecoder.data.local.TransferEntity
import com.transdecoder.data.local.TransferStatus
import com.transdecoder.data.network.FileMetadataInput
import com.transdecoder.data.network.WsMessage
import com.transdecoder.ui.theme.SkiffTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        db = AppDatabase.getDatabase(this)

        // Request POST_NOTIFICATIONS runtime permission on Android 13+ (needed for Foreground Service status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (!isGranted) {
                    Toast.makeText(this, "Notification permission is required for background sync", Toast.LENGTH_LONG).show()
                }
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start Foreground Background Service
        val serviceIntent = Intent(this, SkiffBackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            SkiffTheme {
                // Collect states from the persistent background service
                val deviceCode by SkiffBackgroundService.deviceCode.collectAsState()
                val connectionStatus by SkiffBackgroundService.connectionStatus.collectAsState()
                val activeIncomingRequest by SkiffBackgroundService.activeIncomingRequest.collectAsState()
                val activePeerDeviceId by SkiffBackgroundService.activePeerDeviceId.collectAsState()

                val peerCodeInput = remember { mutableStateOf("") }
                val transfers by db.transferDao().getAllTransfersFlow().collectAsState(initial = emptyList())

                // File picker launcher
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetMultipleContents()
                ) { uris: List<Uri> ->
                    val peerId = activePeerDeviceId
                    if (uris.isNotEmpty() && peerId != null) {
                        sendFiles(uris, peerId, transfers.firstOrNull()?.sessionId ?: UUID.randomUUID().toString())
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Unified root LazyColumn to support dynamic scrolling in landscape orientation
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Title Bar
                        item {
                            Text(
                                text = "⛵ Skiff P2P Share",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Pairing Code Card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Your Device Sharing Code", fontSize = 14.sp, color = Color.Gray)
                                    Text(
                                        text = deviceCode,
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 2.sp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Text("Share this code with other devices to pair", fontSize = 12.sp, color = Color.LightGray)
                                }
                            }
                        }

                        // Connection Status Card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Connection Status", fontSize = 12.sp, color = Color.Gray)
                                        Text(
                                            text = connectionStatus,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when (connectionStatus) {
                                                "Paired & Connected" -> Color.Green
                                                "Connecting..." -> Color.Yellow
                                                else -> Color.Red
                                            }
                                        )
                                    }
                                    Button(onClick = {
                                        SkiffBackgroundService.reconnect(this@MainActivity)
                                    }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Reconnect")
                                    }
                                }
                            }
                        }

                        // Peer Pairing Input Card
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("Connect to Peer Device", fontWeight = FontWeight.Bold)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = peerCodeInput.value,
                                            onValueChange = { peerCodeInput.value = it.uppercase() },
                                            label = { Text("6-Digit Code") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                        Button(
                                            onClick = {
                                                if (peerCodeInput.value.length == 6) {
                                                    SkiffBackgroundService.sendPairRequest(peerCodeInput.value)
                                                } else {
                                                    Toast.makeText(this@MainActivity, "Enter valid 6-char code", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.height(56.dp)
                                        ) {
                                            Text("Pair")
                                        }
                                    }
                                }
                            }
                        }

                        // Send Files Button (Enabled only when paired)
                        item {
                            Button(
                                onClick = { filePickerLauncher.launch("*/*") },
                                enabled = activePeerDeviceId != null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Text("Select & Send Files", fontSize = 16.sp)
                            }
                        }

                        // Progress bars list header
                        item {
                            Text("Transfers List", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        // Progress bars list items (rendered sequentially in same scrollable viewport)
                        items(transfers) { transfer ->
                            TransferProgressItem(transfer)
                        }
                    }

                    // Dialog for incoming pairing request
                    activeIncomingRequest?.let { request ->
                        AlertDialog(
                            onDismissRequest = { /* Force action */ },
                            title = { Text("Connection Request") },
                            text = { Text("Incoming connection request from code ${request.second}. Do you want to pair?") },
                            confirmButton = {
                                Button(onClick = {
                                    SkiffBackgroundService.acceptPairRequest(request.first)
                                }) {
                                    Text("Accept")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    SkiffBackgroundService.rejectPairRequest(request.first)
                                }) {
                                    Text("Reject")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun getUriMetadata(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (name == "file") {
            name = uri.lastPathSegment ?: "file"
        }
        return Pair(name, size)
    }

    private fun sendFiles(uris: List<Uri>, peerId: String, sessionId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val fileList = uris.map { uri ->
                val (name, size) = getUriMetadata(uri)
                val fileId = UUID.randomUUID().toString()
                val fileHash = UUID.randomUUID().toString()
                
                val newFile = TransferEntity(
                    fileId = fileId,
                    sessionId = sessionId,
                    fileName = name,
                    filePath = uri.toString(),
                    fileSize = size,
                    fileHash = fileHash,
                    bytesTransferred = 0L,
                    status = TransferStatus.PENDING,
                    direction = TransferDirection.SEND,
                    peerDeviceId = peerId
                )
                db.transferDao().insertTransfer(newFile)
                
                FileMetadataInput(
                    file_id = fileId,
                    file_name = name,
                    file_path = uri.toString(),
                    file_size = size,
                    file_hash = fileHash
                )
            }
            
            // 1. Notify the receiver peer over WS signaling channel that files are incoming
            SkiffBackgroundService.webSocketClient?.sendMessage(
                WsMessage.InitiateTransfer(
                    session_id = sessionId,
                    receiver_device_id = peerId,
                    files = fileList
                )
            )

            // 2. Start streaming each file over TCP socket
            fileList.zip(uris).forEach { (file, uri) ->
                SkiffBackgroundService.sendFileTcp(this@MainActivity, file.file_id, uri)
            }
        }
    }
}

@Composable
fun TransferProgressItem(transfer: TransferEntity) {
    val progress = if (transfer.fileSize > 0) transfer.bytesTransferred.toFloat() / transfer.fileSize else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (transfer.direction == TransferDirection.SEND) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Direction",
                tint = if (transfer.direction == TransferDirection.SEND) Color.Cyan else Color.Magenta
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.fileName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${formatBytes(transfer.bytesTransferred)} / ${formatBytes(transfer.fileSize)}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            if (transfer.status == TransferStatus.COMPLETED) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Completed",
                    tint = Color.Green
                )
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "${bytes / 1024 / 1024}MB"
        bytes >= 1024 -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }
}
