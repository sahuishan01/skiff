package com.transdecoder.data.local

import androidx.room.Entity

enum class TransferStatus {
    PENDING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    PAUSED
}

enum class TransferDirection {
    SEND,
    RECEIVE
}

@Entity(tableName = "transfers", primaryKeys = ["fileId", "direction"])
data class TransferEntity(
    val fileId: String, // UUID
    val sessionId: String, // UUID
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val fileHash: String,
    val bytesTransferred: Long = 0L,
    val status: TransferStatus = TransferStatus.PENDING,
    val direction: TransferDirection,
    val peerDeviceId: String,
    val updatedAt: Long = System.currentTimeMillis()
)
