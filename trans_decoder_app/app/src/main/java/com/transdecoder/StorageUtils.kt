package com.transdecoder

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object StorageUtils {

    const val PREF_KEY_SAVE_PATH_URI = "custom_save_path_uri"

    /**
     * Checks if a valid, writable folder has been selected by the user
     * and persistable permissions are currently retained.
     */
    fun isFolderSelectedAndValid(context: Context): Boolean {
        val prefs = context.getSharedPreferences("skiff_prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString(PREF_KEY_SAVE_PATH_URI, null) ?: return false
        return try {
            val uri = Uri.parse(uriStr)
            val hasPersistedPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            if (!hasPersistedPermission) {
                AppLogger.log("Storage: Persisted URI write permission missing for $uriStr")
                return false
            }
            val docDir = DocumentFile.fromTreeUri(context, uri)
            docDir != null && docDir.exists() && docDir.canWrite()
        } catch (e: Exception) {
            AppLogger.log("Storage: Error validating folder: ${e.message}")
            false
        }
    }

    /**
     * Returns a user-friendly display name or path for the selected folder.
     */
    fun getFolderDisplayName(context: Context): String? {
        val prefs = context.getSharedPreferences("skiff_prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString(PREF_KEY_SAVE_PATH_URI, null) ?: return null
        return try {
            val uri = Uri.parse(uriStr)
            val doc = DocumentFile.fromTreeUri(context, uri)
            doc?.name ?: uri.lastPathSegment ?: uriStr
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Persists the chosen tree URI permissions and saves to SharedPreferences.
     */
    fun saveFolderUri(context: Context, uri: Uri): Boolean {
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            val prefs = context.getSharedPreferences("skiff_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_KEY_SAVE_PATH_URI, uri.toString()).apply()
            AppLogger.log("Storage: Successfully persisted save location: $uri")
            true
        } catch (e: Exception) {
            AppLogger.log("Storage: Failed to take persistent permissions: ${e.message}")
            false
        }
    }

    /**
     * Creates or overwrites a DocumentFile in the selected folder.
     * Returns the content:// URI as a string, or null if creation failed.
     */
    fun createDocumentFile(context: Context, fileName: String, mimeType: String = "application/octet-stream"): String? {
        val prefs = context.getSharedPreferences("skiff_prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString(PREF_KEY_SAVE_PATH_URI, null) ?: return null
        return try {
            val treeUri = Uri.parse(uriStr)
            val docDir = DocumentFile.fromTreeUri(context, treeUri)
            if (docDir != null && docDir.exists() && docDir.canWrite()) {
                docDir.findFile(fileName)?.delete()
                val newDoc = docDir.createFile(mimeType, fileName)
                newDoc?.uri?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.log("Storage: Error creating document file: ${e.message}")
            null
        }
    }
}
