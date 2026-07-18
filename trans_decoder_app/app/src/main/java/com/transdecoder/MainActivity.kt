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
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.transdecoder.data.local.AppDatabase
import com.transdecoder.data.local.KnownPeer
import com.transdecoder.data.local.TransferDirection
import com.transdecoder.data.local.TransferEntity
import com.transdecoder.data.local.TransferStatus
import com.transdecoder.ui.theme.PairCodeFont
import com.transdecoder.data.network.FileMetadataInput
import com.transdecoder.data.network.WsMessage
import com.transdecoder.ui.theme.PairCodeFont
import com.transdecoder.ui.theme.SkiffColors
import com.transdecoder.ui.theme.SkiffTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = AppDatabase.getDatabase(this)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted: Boolean ->
                if (!isGranted) {
                    Toast.makeText(
                        this,
                        "Background sync needs notification permission",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start foreground background service
        val serviceIntent = Intent(this, SkiffBackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            SkiffTheme {
                val deviceCode by SkiffBackgroundService.deviceCode.collectAsState()
                val connectionStatus by SkiffBackgroundService.connectionStatus.collectAsState()
                val activeIncomingRequest by SkiffBackgroundService.activeIncomingRequest.collectAsState()
                val activePeerDeviceId by SkiffBackgroundService.activePeerDeviceId.collectAsState()

                val peerCodeInput = remember { mutableStateOf("") }
                val isPairing = remember { mutableStateOf(false) }
                val transfers by db.transferDao().getAllTransfersFlow()
                    .collectAsState(initial = emptyList())
                val knownPeers by db.knownPeerDao().getAllPeersFlow()
                    .collectAsState(initial = emptyList())

                var showSettings by remember { mutableStateOf(false) }

                // Reset pairing loading state when connection status resolves
                LaunchedEffect(connectionStatus) {
                    if (connectionStatus in listOf("Paired & Connected", "Pairing Rejected", "Registered & Waiting")) {
                        isPairing.value = false
                    }
                }

                // File picker
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetMultipleContents()
                ) { uris: List<Uri> ->
                    val peerId = activePeerDeviceId
                    if (uris.isNotEmpty() && peerId != null) {
                        sendFiles(uris, peerId, transfers.firstOrNull()?.sessionId
                            ?: UUID.randomUUID().toString())
                    }
                }

                // Custom save folder preference
                val prefs = remember {
                    getSharedPreferences("skiff_prefs", MODE_PRIVATE)
                }
                val customSavePathState = remember {
                    mutableStateOf(prefs.getString("custom_save_path_uri", null))
                }

                val folderPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    if (uri != null) {
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            prefs.edit().putString("custom_save_path_uri", uri.toString()).apply()
                            customSavePathState.value = uri.toString()
                            AppLogger.log("Custom save location selected: $uri")
                        } catch (e: Exception) {
                            AppLogger.log(
                                "Failed to obtain persistent folder permission: ${e.message}"
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        topBar = {
                            SkiffTopBar(
                                connectionStatus = connectionStatus,
                                onReconnect = {
                                    SkiffBackgroundService.reconnect(this@MainActivity)
                                },
                                onSettings = { showSettings = true }
                            )
                        }
                    ) { padding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                        ) {
                            // ── Hero: Pairing Code ─────────────────────────────
                            PairingCodeHero(
                                deviceCode = deviceCode,
                                connectionStatus = connectionStatus
                            )

                            // ── Action Section ─────────────────────────────────
                            ActionSection(
                                peerCodeInput = peerCodeInput.value,
                                onPeerCodeChange = { peerCodeInput.value = it.uppercase() },
                                onPair = {
                                    if (peerCodeInput.value.length == 6) {
                                        isPairing.value = true
                                        SkiffBackgroundService.sendPairRequest(peerCodeInput.value)
                                    } else {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Code must be 6 characters",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                isPaired = activePeerDeviceId != null,
                                isPairing = isPairing.value,
                                onSendFiles = { filePickerLauncher.launch("*/*") }
                            )

                            PeersSection(
                                peers = knownPeers,
                                activePeerId = activePeerDeviceId,
                                peerCodeInput = peerCodeInput,
                                onPair = { code ->
                                    isPairing.value = true
                                    peerCodeInput.value = code
                                    SkiffBackgroundService.sendPairRequest(code)
                                }
                            )

                            TransferListSection(
                                transfers = transfers,
                                isPaired = activePeerDeviceId != null,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // ── Incoming Pair Request Dialog ──────────────────────────
                    activeIncomingRequest?.let { request ->
                        PairRequestDialog(
                            code = request.second,
                            onAccept = {
                                SkiffBackgroundService.acceptPairRequest(request.first)
                            },
                            onReject = {
                                SkiffBackgroundService.rejectPairRequest(request.first)
                            }
                        )
                    }

                    // ── Settings Dialog ────────────────────────────────────────
                    if (showSettings) {
                        SettingsDialog(
                            customSavePathUri = customSavePathState.value,
                            onChangeSaveLocation = { folderPickerLauncher.launch(null) },
                            onResetSaveLocation = {
                                prefs.edit().remove("custom_save_path_uri").apply()
                                customSavePathState.value = null
                                AppLogger.log("Reset save location to Downloads default")
                            },
                            onDismiss = { showSettings = false }
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
            try {
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

                SkiffBackgroundService.webSocketClient?.sendMessage(
                    WsMessage.InitiateTransfer(
                        session_id = sessionId,
                        receiver_device_id = peerId,
                        files = fileList
                    )
                )

                fileList.zip(uris).forEach { (file, uri) ->
                    SkiffBackgroundService.sendFileTcp(this@MainActivity, file.file_id, uri)
                }
            } catch (e: Exception) {
                AppLogger.log("Sender: Failed to initiate transfer: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Composable Components
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkiffTopBar(
    connectionStatus: String,
    onReconnect: () -> Unit,
    onSettings: () -> Unit
) {
    val statusColor = when (connectionStatus) {
        "Paired & Connected" -> SkiffColors.Green
        "Connecting..." -> SkiffColors.Amber
        else -> SkiffColors.TextMuted
    }

    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = connectionStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        actions = {
            IconButton(onClick = onReconnect) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reconnect",
                    tint = SkiffColors.TextSecondary
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Settings",
                    tint = SkiffColors.TextSecondary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
private fun PairingCodeHero(
    deviceCode: String,
    connectionStatus: String
) {
    val isConnecting = connectionStatus == "Connecting..."
    val isRegistered = connectionStatus.contains("Registered", ignoreCase = true) ||
            connectionStatus.contains("Waiting", ignoreCase = true)
    val isPaired = connectionStatus == "Paired & Connected"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Your code",
                style = MaterialTheme.typography.labelMedium,
                color = SkiffColors.TextMuted
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = deviceCode,
                style = MaterialTheme.typography.displayLarge,
                fontFamily = PairCodeFont,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                letterSpacing = 6.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when {
                    isConnecting -> "Registering device..."
                    isRegistered -> "Share this code to pair"
                    isPaired -> "Connected"
                    else -> "Waiting for connection..."
                },
                style = MaterialTheme.typography.bodySmall,
                color = SkiffColors.TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActionSection(
    peerCodeInput: String,
    onPeerCodeChange: (String) -> Unit,
    onPair: () -> Unit,
    isPaired: Boolean,
    isPairing: Boolean,
    onSendFiles: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Pair input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = peerCodeInput,
                onValueChange = onPeerCodeChange,
                placeholder = { Text("Peer code") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = PairCodeFont,
                    letterSpacing = 4.sp
                ),
                shape = MaterialTheme.shapes.medium
            )

            Button(
                onClick = onPair,
                enabled = !isPairing,
                modifier = Modifier
                    .height(52.dp)
                    .widthIn(min = 90.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (isPairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = "Pair",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Send files button
        Button(
            onClick = onSendFiles,
            enabled = isPaired,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                contentColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = SkiffColors.SurfaceElevated.copy(alpha = 0.5f),
                disabledContentColor = SkiffColors.TextSecondary
            )
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isPaired) "Send Files" else "Pair to send files",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeersSection(
    peers: List<KnownPeer>,
    activePeerId: String?,
    peerCodeInput: MutableState<String>,
    onPair: (String) -> Unit
) {
    if (peers.isEmpty()) return

    var renameTarget by remember { mutableStateOf<KnownPeer?>(null) }
    val renameScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 4.dp)
    ) {
        Text(
            text = "Peers (${peers.size})",
            style = MaterialTheme.typography.labelLarge,
            color = SkiffColors.TextSecondary,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(peers) { peer ->
                val isActive = peer.deviceId == activePeerId
                val displayName = peer.displayName.ifEmpty {
                    peer.deviceId.take(8)
                }

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    } else {
                        SkiffColors.SurfaceElevated
                    },
                    modifier = Modifier
                        .clickable(enabled = !isActive) {
                            peerCodeInput.value = peer.deviceId.take(6)
                            onPair(peer.deviceId.take(6))
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { renameTarget = peer }
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isActive) SkiffColors.Green else SkiffColors.TextMuted)
                        )
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                            color = if (isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        if (isActive) {
                            Text(
                                text = "Connected",
                                style = MaterialTheme.typography.labelSmall,
                                color = SkiffColors.Green
                            )
                        }
                    }
                }
            }
        }
    }

    // Rename dialog
    renameTarget?.let { peer ->
        var newName by remember(peer) { mutableStateOf(peer.displayName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            title = {
                Text("Rename Peer", style = MaterialTheme.typography.headlineMedium)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = peer.deviceId.ifEmpty { peer.deviceId },
                        style = MaterialTheme.typography.bodySmall,
                        color = SkiffColors.TextSecondary
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            val db = AppDatabase.getDatabase(
                                SkiffBackgroundService.instance?.applicationContext ?: return@Button
                            )
                            renameScope.launch {
                                db.knownPeerDao().upsertPeer(peer.copy(displayName = newName.trim()))
                            }
                        }
                        renameTarget = null
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = SkiffColors.TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun TransferListSection(
    transfers: List<TransferEntity>,
    isPaired: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Transfers",
            style = MaterialTheme.typography.labelLarge,
            color = SkiffColors.TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 4.dp)
        )

        if (transfers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPaired)
                        "Select files to send or wait for transfers"
                    else
                        "Pair a device to start sharing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SkiffColors.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(transfers, key = { it.fileId }) { transfer ->
                    TransferTimelineItem(transfer = transfer)
                }
            }
        }
    }
}

@Composable
private fun TransferTimelineItem(transfer: TransferEntity) {
    val progress = if (transfer.fileSize > 0) {
        (transfer.bytesTransferred.toFloat() / transfer.fileSize).coerceIn(0f, 1f)
    } else 0f

    val isCompleted = transfer.status == TransferStatus.COMPLETED
    val isFailed = transfer.status == TransferStatus.FAILED
    val isCancelled = transfer.status == TransferStatus.CANCELLED
    val isOutgoing = transfer.direction == TransferDirection.SEND
    val isActive = transfer.status == TransferStatus.TRANSFERRING ||
            transfer.status == TransferStatus.PENDING

    val statusColor = when {
        isCompleted -> SkiffColors.Green
        isFailed -> SkiffColors.Coral
        isCancelled -> SkiffColors.TextMuted
        else -> MaterialTheme.colorScheme.primary
    }

    val directionIcon = if (isOutgoing) Icons.Default.KeyboardArrowUp
    else Icons.Default.KeyboardArrowDown

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Direction indicator
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(statusColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = directionIcon,
                contentDescription = if (isOutgoing) "Sent" else "Received",
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transfer.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    isFailed -> SkiffColors.Coral
                    isCancelled -> SkiffColors.TextSecondary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (isActive) {
                // Active progress
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(MaterialTheme.shapes.small),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = SkiffColors.Border,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatBytes(transfer.bytesTransferred)} / ${formatBytes(transfer.fileSize)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkiffColors.TextSecondary
                )
                Text(
                    text = when {
                        isCompleted -> "Complete"
                        isFailed -> "Failed"
                        isCancelled -> "Cancelled"
                        transfer.status == TransferStatus.TRANSFERRING -> "${(progress * 100).toInt()}%"
                        transfer.status == TransferStatus.PENDING -> "Waiting..."
                        else -> "${(progress * 100).toInt()}%"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }
        }

        // Completion checkmark or cancel button
        if (isCompleted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Completed",
                tint = SkiffColors.Green,
                modifier = Modifier.size(22.dp)
            )
        } else if (isActive) {
            IconButton(
                onClick = {
                    SkiffBackgroundService.cancelTransfer(
                        SkiffBackgroundService.instance ?: return@IconButton,
                        transfer.fileId,
                        transfer.direction
                    )
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel transfer",
                    tint = SkiffColors.TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
private fun PairRequestDialog(
    code: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onReject() },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                text = "Connection Request",
                style = MaterialTheme.typography.headlineMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "A device wants to pair with you. Verify the code matches what the other device shows.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SkiffColors.TextSecondary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Code:",
                        style = MaterialTheme.typography.bodySmall,
                        color = SkiffColors.TextMuted
                    )
                    Text(
                        text = code,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = PairCodeFont,
                            letterSpacing = 4.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Accept")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onReject,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SkiffColors.TextSecondary
                )
            ) {
                Text("Reject")
            }
        }
    )
}

@Composable
private fun SettingsDialog(
    customSavePathUri: String?,
    onChangeSaveLocation: () -> Unit,
    onResetSaveLocation: () -> Unit,
    onDismiss: () -> Unit
) {
    var showDebugLogs by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Save location section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Save Location",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (customSavePathUri != null) {
                            "Custom folder selected"
                        } else {
                            "Downloads folder (default)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SkiffColors.TextSecondary
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onChangeSaveLocation,
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Change Location")
                        }
                        if (customSavePathUri != null) {
                            TextButton(onClick = onResetSaveLocation) {
                                Text("Reset", color = SkiffColors.TextSecondary)
                            }
                        }
                    }
                }

                Divider(color = SkiffColors.Border, thickness = 1.dp)

                // Debug logs section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Debug Logs",
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(onClick = { showDebugLogs = !showDebugLogs }) {
                            Text(
                                if (showDebugLogs) "Hide" else "Show (${AppLogger.logHistory.size})"
                            )
                        }
                    }
                    if (showDebugLogs) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                AppLogger.logHistory.forEach { log ->
                                    Text(
                                        text = log,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = SkiffColors.TextSecondary,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

// ── Utilities ────────────────────────────────────────────────────────────────

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }
}
