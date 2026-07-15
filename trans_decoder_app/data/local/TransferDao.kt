package com.transdecoder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY updatedAt DESC")
    fun getAllTransfersFlow(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE fileId = :fileId LIMIT 1")
    suspend fun getTransferById(fileId: String): TransferEntity?

    @Query("SELECT * FROM transfers WHERE fileHash = :fileHash LIMIT 1")
    suspend fun getTransferByHash(fileHash: String): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: TransferEntity)

    @Update
    suspend fun updateTransfer(transfer: TransferEntity)

    @Query("UPDATE transfers SET bytesTransferred = :bytesTransferred, status = :status, updatedAt = :updatedAt WHERE fileId = :fileId")
    suspend fun updateProgress(fileId: String, bytesTransferred: Long, status: TransferStatus, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM transfers WHERE fileId = :fileId")
    suspend fun deleteTransfer(fileId: String)
}
