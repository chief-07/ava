package com.ava.voice

import android.content.Context
import com.ava.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * ModelDownloader manages downloading and unzipping the 40MB Vosk model zip file.
 * Exposes real-time progress via StateFlow.
 */
class ModelDownloader(private val context: Context) {
    private val TAG = "AVA:ModelDownloader"
    private val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    
    // The directory where Vosk model will be extracted
    val modelDir: File = File(context.filesDir, "vosk-model")

    sealed class DownloadState {
        object Idle : DownloadState()
        object Checking : DownloadState()
        class Downloading(val progress: Float) : DownloadState()
        object Unzipping : DownloadState()
        object Success : DownloadState()
        class Error(val message: String) : DownloadState()
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    /** Checks if the model directory already exists and is populated */
    fun isModelInstalled(): Boolean {
        if (!modelDir.exists()) return false
        val files = modelDir.listFiles()
        // If it exists and contains subfolders/files, it's installed
        return files != null && files.isNotEmpty()
    }

    /** Triggers the background download and extract process */
    suspend fun downloadAndInstall() {
        if (isModelInstalled()) {
            _state.value = DownloadState.Success
            return
        }

        _state.value = DownloadState.Checking
        val tempZip = File(context.cacheDir, "vosk-model.zip")

        withContext(Dispatchers.IO) {
            try {
                // 1. Download Model Zip
                AppLogger.d(TAG, "Starting download from: $MODEL_URL")
                val url = URL(MODEL_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP ${connection.responseCode}")
                }

                val fileLength = connection.contentLength
                val input = BufferedInputStream(connection.inputStream)
                val output = FileOutputStream(tempZip)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                
                while (input.read(data).also { count = it } != -1) {
                    total += count
                    output.write(data, 0, count)
                    if (fileLength > 0) {
                        val progress = total.toFloat() / fileLength.toFloat()
                        _state.value = DownloadState.Downloading(progress)
                    }
                }

                output.flush()
                output.close()
                input.close()
                connection.disconnect()
                
                AppLogger.d(TAG, "Download finished. Starting extraction...")
                _state.value = DownloadState.Unzipping

                // 2. Unzip into target directory
                if (modelDir.exists()) {
                    modelDir.deleteRecursively()
                }
                modelDir.mkdirs()

                ZipInputStream(tempZip.inputStream()).use { zipInput ->
                    var entry = zipInput.nextEntry
                    var isFirstDir = true
                    var rootDirName = ""

                    while (entry != null) {
                        val name = entry.name
                        if (isFirstDir && entry.isDirectory) {
                            rootDirName = name
                            isFirstDir = false
                        } else {
                            val relativePath = if (rootDirName.isNotEmpty() && name.startsWith(rootDirName)) {
                                name.substring(rootDirName.length)
                            } else {
                                name
                            }

                            if (relativePath.isNotEmpty()) {
                                val destFile = File(modelDir, relativePath)
                                if (entry.isDirectory) {
                                    destFile.mkdirs()
                                } else {
                                    destFile.parentFile?.mkdirs()
                                    FileOutputStream(destFile).use { fileOut ->
                                        zipInput.copyTo(fileOut)
                                    }
                                }
                            }
                        }
                        zipInput.closeEntry()
                        entry = zipInput.nextEntry
                    }
                }

                // 3. Clean up temp files
                tempZip.delete()
                
                AppLogger.i(TAG, "Voice model installed successfully at: ${modelDir.absolutePath}")
                _state.value = DownloadState.Success
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error installing model: ${e.message}")
                tempZip.delete()
                _state.value = DownloadState.Error(e.message ?: "Unknown download error")
            }
        }
    }
}
