package com.ava.voice

import android.content.Context
import com.ava.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelDownloader {
    private const val TAG = "AVA:ModelDownloader"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    private const val ZIP_FILE_NAME = "vosk-model.zip"
    const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"

    /**
     * Downloads and extracts the Vosk small acoustic model if not already present.
     * Updates SharedPreferences with the model path when complete.
     */
    suspend fun downloadAndExtractModel(context: Context, onProgress: (String) -> Unit): String? {
        val destDir = File(context.filesDir, MODEL_DIR_NAME)
        val sharedPrefs = context.getSharedPreferences("ava_config", Context.MODE_PRIVATE)

        // If already unzipped and valid, return path immediately
        if (destDir.exists() && destDir.isDirectory && destDir.listFiles()?.isNotEmpty() == true) {
            val savedPath = destDir.absolutePath
            sharedPrefs.edit().putString("vosk_model_path", savedPath).apply()
            return savedPath
        }

        return withContext(Dispatchers.IO) {
            try {
                onProgress("Downloading voice model (approx. 40MB)...")
                AppLogger.i(TAG, "Starting download from $MODEL_URL")
                
                val url = URL(MODEL_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    AppLogger.e(TAG, "Server returned HTTP ${connection.responseCode}")
                    onProgress("Download failed: Server error ${connection.responseCode}")
                    return@withContext null
                }

                val fileLength = connection.contentLength
                val cacheZip = File(context.cacheDir, ZIP_FILE_NAME)
                
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(cacheZip).use { output ->
                        val data = ByteArray(4096)
                        var total: Long = 0
                        var count: Int
                        var lastProgress = 0L

                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            output.write(data, 0, count)
                            
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastProgress > 1000) {
                                val progressPercent = if (fileLength > 0) (total * 100 / fileLength).toInt() else -1
                                val msg = if (progressPercent >= 0) "Downloading: $progressPercent%" else "Downloading..."
                                onProgress(msg)
                                lastProgress = currentTime
                            }
                        }
                    }
                }

                AppLogger.i(TAG, "Download finished. Unzipping to ${destDir.absolutePath}...")
                onProgress("Extracting voice model files...")
                
                unzip(cacheZip, context.filesDir)
                
                // Clean up cached zip file
                if (cacheZip.exists()) {
                    cacheZip.delete()
                }

                if (destDir.exists() && destDir.isDirectory) {
                    val finalPath = destDir.absolutePath
                    sharedPrefs.edit().putString("vosk_model_path", finalPath).apply()
                    onProgress("Voice model loaded successfully!")
                    AppLogger.i(TAG, "Voice model setup complete. Path: $finalPath")
                    finalPath
                } else {
                    onProgress("Extraction failed: directory not found")
                    AppLogger.e(TAG, "Extraction directory does not exist after unzip")
                    null
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to download/extract voice model: ${e.message}", e)
                onProgress("Voice model download error: ${e.message}")
                null
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(4096)
            while (entry != null) {
                val newFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
