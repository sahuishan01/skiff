package com.transdecoder.domain

import com.transdecoder.data.local.TransferDao
import com.transdecoder.data.local.TransferDirection
import com.transdecoder.data.local.TransferEntity
import com.transdecoder.data.local.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID

class TransferManager(
    private val transferDao: TransferDao
) {
    /**
     * Prepares to receive a file.
     * Checks if a partial file already exists locally to resume.
     * Returns the byte offset from which to resume.
     */
    suspend fun prepareToReceive(
        sessionId: String,
        fileName: String,
        fileSize: Long,
        fileHash: String,
        peerDeviceId: String,
        destinationDir: File
    ): Long = withContext(Dispatchers.IO) {
        val existingRecord = transferDao.getTransferByHash(fileHash)
        val localFile = File(destinationDir, fileName)

        if (existingRecord != null && localFile.exists()) {
            val localSize = localFile.length()
            if (localSize < fileSize && existingRecord.status != TransferStatus.COMPLETED) {
                // Resume from where the file currently is
                transferDao.updateProgress(existingRecord.fileId, localSize, TransferStatus.TRANSFERRING)
                return@withContext localSize
            } else if (localSize == fileSize) {
                // Already fully downloaded
                transferDao.updateProgress(existingRecord.fileId, fileSize, TransferStatus.COMPLETED)
                return@withContext fileSize
            }
        }

        // Fresh file transfer
        val fileId = UUID.randomUUID().toString()
        val newRecord = TransferEntity(
            fileId = fileId,
            sessionId = sessionId,
            fileName = fileName,
            filePath = localFile.absolutePath,
            fileSize = fileSize,
            fileHash = fileHash,
            bytesTransferred = 0L,
            status = TransferStatus.TRANSFERRING,
            direction = TransferDirection.RECEIVE,
            peerDeviceId = peerDeviceId
        )
        transferDao.insertTransfer(newRecord)
        return@withContext 0L
    }

    /**
     * Receives file data from a network InputStream starting from a specific offset.
     */
    suspend fun receiveFileStream(
        fileHash: String,
        inputStream: InputStream,
        startOffset: Long,
        onProgress: (Long, Double) -> Unit
    ) = withContext(Dispatchers.IO) {
        val record = transferDao.getTransferByHash(fileHash) ?: return@withContext
        val localFile = File(record.filePath)

        RandomAccessFile(localFile, "rw").use { raf ->
            raf.seek(startOffset)
            var bytesTransferred = startOffset
            val buffer = ByteArray(64 * 1024) // 64KB buffer
            var bytesRead: Int
            
            var lastUpdateMs = System.currentTimeMillis()

            try {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    raf.write(buffer, 0, bytesRead)
                    bytesTransferred += bytesRead

                    val now = System.currentTimeMillis()
                    // Throttle DB updates to once every 500ms to save resources
                    if (now - lastUpdateMs > 500) {
                        transferDao.updateProgress(record.fileId, bytesTransferred, TransferStatus.TRANSFERRING)
                        lastUpdateMs = now
                    }

                    val percentage = (bytesTransferred.toDouble() / record.fileSize) * 100
                    onProgress(bytesTransferred, percentage)
                }

                // Finalize complete transfer
                transferDao.updateProgress(record.fileId, bytesTransferred, TransferStatus.COMPLETED)
                onProgress(bytesTransferred, 100.0)
            } catch (e: Exception) {
                transferDao.updateProgress(record.fileId, bytesTransferred, TransferStatus.FAILED)
                throw e
            }
        }
    }

    /**
     * Sends file data to a network OutputStream starting from a specific offset.
     */
    suspend fun sendFileStream(
        fileId: String,
        outputStream: OutputStream,
        startOffset: Long,
        onProgress: (Long, Double) -> Unit
    ) = withContext(Dispatchers.IO) {
        val record = transferDao.getTransferById(fileId) ?: return@withContext
        val file = File(record.filePath)

        if (!file.exists()) {
            transferDao.updateProgress(fileId, record.bytesTransferred, TransferStatus.FAILED)
            throw IllegalArgumentException("File not found on disk: ${file.absolutePath}")
        }

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startOffset)
            var bytesSent = startOffset
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            
            var lastUpdateMs = System.currentTimeMillis()

            try {
                transferDao.updateProgress(fileId, bytesSent, TransferStatus.TRANSFERRING)
                
                while (raf.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesSent += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMs > 500) {
                        transferDao.updateProgress(fileId, bytesSent, TransferStatus.TRANSFERRING)
                        lastUpdateMs = now
                    }

                    val percentage = (bytesSent.toDouble() / record.fileSize) * 100
                    onProgress(bytesSent, percentage)
                }

                transferDao.updateProgress(fileId, bytesSent, TransferStatus.COMPLETED)
                onProgress(bytesSent, 100.0)
            } catch (e: Exception) {
                transferDao.updateProgress(fileId, bytesSent, TransferStatus.FAILED)
                throw e
            }
        }
    }
}
