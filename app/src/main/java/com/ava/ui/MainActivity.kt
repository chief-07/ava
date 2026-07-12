package com.ava.ui

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ava.service.AVAAccessibilityService
import com.ava.util.AppLogger
import com.ava.voice.SpeechInput
import com.ava.voice.ModelDownloader
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.first

private val AVABlue = Color(0xFF1A73E8)
private val AVADark = Color(0xFF1C1C2E)
private val AVAText = Color(0xFFFFFFFF)
private val AVASubtext = Color(0xFFAAAAAA)

private const val TAG = "MainActivity"

/**
 * MainActivity — AVA's setup, launch, and live logs console screen.
 */
class MainActivity : ComponentActivity() {

    private lateinit var speechInput: SpeechInput
    private lateinit var modelDownloader: ModelDownloader

    // Compose State variables for permissions status
    private var accessibilityEnabled by mutableStateOf(false)
    private var overlayEnabled by mutableStateOf(false)
    private var audioGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(this)  // enable file-backed log persistence
        speechInput = SpeechInput(this)
        modelDownloader = ModelDownloader(this)

        AppLogger.i(TAG, "MainActivity created")

        // If launched from Google Assistant ("Hey Google, open AVA") — start listening
        val fromAssistant = intent?.action == Intent.ACTION_ASSIST
        if (fromAssistant) {
            AppLogger.i(TAG, "Launched via Assistant shortcut")
            startListeningForTask()
        }

        setContent {
            AVASetupScreen(
                accessibilityGranted = accessibilityEnabled,
                overlayGranted = overlayEnabled,
                audioGranted = audioGranted,
                notificationsGranted = notificationsGranted,
                modelDownloader = modelDownloader,
                onStartListening = { startListeningForTask() },
                onSaveApiKey = { key -> saveApiKey(key) },
                onSaveSerpApiKey = { key -> saveSerpApiKey(key) },
                onOpenAccessibilitySettings = { openAccessibilitySettings() },
                onOpenOverlaySettings = { openOverlaySettings() },
                onRequestAudioPermission = { requestMicrophonePermission() },
                onRequestNotificationPermission = { requestNotificationPermission() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        syncPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechInput.destroy()
    }

    private fun syncPermissions() {
        accessibilityEnabled = AVAAccessibilityService.instance != null
        overlayEnabled = Settings.canDrawOverlays(this)
        audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestMicrophonePermission() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        syncPermissions()
        AppLogger.i(TAG, "Permissions updated: request $requestCode")
    }

    private fun startListeningForTask() {
        val service = AVAAccessibilityService.instance
        if (service == null) {
            AppLogger.e(TAG, "Accessibility Service is not enabled. Go to Settings and turn it on.")
        } else {
            AppLogger.i(TAG, "Opening overlay banner... Minimizing app.")
            service.showIdleBannerAndListen()
            moveTaskToBack(true)
        }
    }

    private fun saveApiKey(key: String) {
        getSharedPreferences("ava_config", MODE_PRIVATE)
            .edit()
            .putString("gemini_api_key", key)
            .apply()
        AppLogger.i(TAG, "Gemini API key saved")
    }

    private fun saveSerpApiKey(key: String) {
        getSharedPreferences("ava_config", MODE_PRIVATE)
            .edit()
            .putString("serp_api_key", key)
            .apply()
        AppLogger.i(TAG, "SerpAPI key saved")
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }
}

// ─── UI ───────────────────────────────────────────────────────────────────────

@Composable
fun AVASetupScreen(
    accessibilityGranted: Boolean,
    overlayGranted: Boolean,
    audioGranted: Boolean,
    notificationsGranted: Boolean,
    modelDownloader: ModelDownloader,
    onStartListening: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onSaveSerpApiKey: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(
        context.getSharedPreferences("ava_config", 0)
            .getString("gemini_api_key", "") ?: ""
    )}
    var apiKeySaved by remember { mutableStateOf(false) }
    var serpApiKey by remember { mutableStateOf(
        context.getSharedPreferences("ava_config", 0)
            .getString("serp_api_key", "") ?: ""
    )}
    var serpApiKeySaved by remember { mutableStateOf(false) }
    var useSplitScreen by remember { mutableStateOf(
        context.getSharedPreferences("ava_config", 0)
            .getBoolean("use_split_screen", false)
    )}

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AVADark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        // ── Header ───────────────────────────────────────────────────────────
        Text("AVA", color = AVABlue, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("AI Voice Agent", color = AVASubtext, fontSize = 14.sp)

        Spacer(Modifier.height(16.dp))

        // ── Setup items list ──
            // Permission cards
            PermissionCard(
                title = "1. Accessibility Service",
                description = "Required to read screen and perform taps",
                granted = accessibilityGranted,
                onGrant = onOpenAccessibilitySettings
            )
            Spacer(Modifier.height(8.dp))
            PermissionCard(
                title = "2. Draw Over Apps",
                description = "Required to show top action banner",
                granted = overlayGranted,
                onGrant = onOpenOverlaySettings
            )
            Spacer(Modifier.height(8.dp))
            PermissionCard(
                title = "3. Microphone Access",
                description = "Required to capture your voice tasks",
                granted = audioGranted,
                onGrant = onRequestAudioPermission
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Spacer(Modifier.height(8.dp))
                PermissionCard(
                    title = "4. Notifications",
                    description = "Required to run overlay banner service",
                    granted = notificationsGranted,
                    onGrant = onRequestNotificationPermission
                )
            }

            Spacer(Modifier.height(16.dp))

            // API Key input
            Text("Gemini API Key", color = AVAText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        apiKeySaved = false
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Paste Gemini API key", color = AVASubtext, fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AVABlue,
                        unfocusedBorderColor = AVASubtext,
                        cursorColor = AVABlue,
                        focusedTextColor = AVAText,
                        unfocusedTextColor = AVAText
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSaveApiKey(apiKey)
                        apiKeySaved = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AVABlue)
                ) {
                    Text(if (apiKeySaved) "Saved" else "Save", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // SerpAPI Key input
            Text("SerpAPI Key (Optional)", color = AVAText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = serpApiKey,
                    onValueChange = {
                        serpApiKey = it
                        serpApiKeySaved = false
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Paste SerpAPI key for live info", color = AVASubtext, fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AVABlue,
                        unfocusedBorderColor = AVASubtext,
                        cursorColor = AVABlue,
                        focusedTextColor = AVAText,
                        unfocusedTextColor = AVAText
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSaveSerpApiKey(serpApiKey)
                        serpApiKeySaved = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AVABlue)
                ) {
                    Text(if (serpApiKeySaved) "Saved" else "Save", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Multitasking Toggle Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A3E), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Run in Multitasking Mode", color = AVAText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("Launch target apps in Split-Screen or Floating Popup mode", color = AVASubtext, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = useSplitScreen,
                    onCheckedChange = { checked ->
                        useSplitScreen = checked
                        context.getSharedPreferences("ava_config", 0).edit().apply {
                            putBoolean("use_split_screen", checked)
                            apply()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AVAText,
                        checkedTrackColor = AVABlue,
                        uncheckedThumbColor = AVASubtext,
                        uncheckedTrackColor = AVADark
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            // Launcher Notification Toggle Card
            var showNotification by remember { mutableStateOf(
                context.getSharedPreferences("ava_config", 0).getBoolean("show_persistent_notification", false)
            )}
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A3E), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show Launcher Notification", color = AVAText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("Keep a persistent notification to easily launch AVA from anywhere", color = AVASubtext, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = showNotification,
                    onCheckedChange = { checked ->
                        showNotification = checked
                        context.getSharedPreferences("ava_config", 0).edit().apply {
                            putBoolean("show_persistent_notification", checked)
                            apply()
                        }
                        val intent = Intent(context, AVAAccessibilityService::class.java).apply {
                            action = AVAAccessibilityService.ACTION_REFRESH_NOTIFICATION
                        }
                        try {
                            context.startService(intent)
                        } catch (e: Exception) {
                            AppLogger.e("MainActivity", "Failed to refresh notification: ${e.message}")
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AVAText,
                        checkedTrackColor = AVABlue,
                        uncheckedThumbColor = AVASubtext,
                        uncheckedTrackColor = AVADark
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Voice Model Downloader Card ───────────────────────────────────
            var downloadState by remember { mutableStateOf(modelDownloader.state.value) }
            val coroutineScope = rememberCoroutineScope()

            LaunchedEffect(modelDownloader) {
                modelDownloader.state.collect {
                    downloadState = it
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A3E), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Offline Voice Model", color = AVAText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    
                    val subtext = when (val state = downloadState) {
                        is ModelDownloader.DownloadState.Idle -> "Download local speech recognition model for silent wake word triggers (40MB)"
                        is ModelDownloader.DownloadState.Checking -> "Checking storage..."
                        is ModelDownloader.DownloadState.Downloading -> "Downloading: ${(state.progress * 100).toInt()}%"
                        is ModelDownloader.DownloadState.Unzipping -> "Unpacking files to local storage..."
                        is ModelDownloader.DownloadState.Success -> "Installed successfully! Continuous wake word ready."
                        is ModelDownloader.DownloadState.Error -> "Error: ${state.message}"
                    }
                    Text(subtext, color = AVASubtext, fontSize = 11.sp)
                    
                    if (downloadState is ModelDownloader.DownloadState.Downloading) {
                        Spacer(Modifier.height(8.dp))
                        val progress = (downloadState as ModelDownloader.DownloadState.Downloading).progress
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth(),
                            color = AVABlue,
                            trackColor = Color.Gray.copy(alpha = 0.3f)
                        )
                    } else if (downloadState is ModelDownloader.DownloadState.Unzipping) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = AVABlue,
                            trackColor = Color.Gray.copy(alpha = 0.3f)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                
                when (downloadState) {
                    is ModelDownloader.DownloadState.Idle, is ModelDownloader.DownloadState.Error -> {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    modelDownloader.downloadAndInstall()
                                    // Refresh wake word listener after successful install
                                    val intent = Intent(context, AVAAccessibilityService::class.java).apply {
                                        action = AVAAccessibilityService.ACTION_REFRESH_NOTIFICATION
                                    }
                                    try {
                                        context.startService(intent)
                                    } catch (e: Exception) {}
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AVABlue),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Download", fontSize = 11.sp)
                        }
                    }
                    is ModelDownloader.DownloadState.Success -> {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(Color(0xFF81C784), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = AVABlue,
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Custom Avatar Card
            var customAvatarPath by remember { mutableStateOf(
                context.getSharedPreferences("ava_config", 0).getString("custom_avatar_path", null)
            )}
            val customAvatarBitmap = remember(customAvatarPath) {
                if (customAvatarPath != null) {
                    val file = File(customAvatarPath!!)
                    if (file.exists()) {
                        try {
                            BitmapFactory.decodeFile(file.absolutePath)
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                } else null
            }
            val pickMedia = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                if (uri != null) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val destinationFile = File(context.filesDir, "custom_avatar.png")
                        inputStream?.use { input ->
                            destinationFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        context.getSharedPreferences("ava_config", 0).edit().apply {
                            putString("custom_avatar_path", destinationFile.absolutePath)
                            apply()
                        }
                        customAvatarPath = destinationFile.absolutePath
                        AppLogger.i("MainActivity", "Custom avatar updated! Restart the service if needed.")
                    } catch (e: Exception) {
                        AppLogger.e("MainActivity", "Failed to save avatar image: ${e.message}")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A3E), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Custom Overlay Avatar", color = AVAText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("Select a photo from your gallery to replace the default smiley button", color = AVASubtext, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (customAvatarBitmap != null) {
                        Image(
                            bitmap = customAvatarBitmap.asImageBitmap(),
                            contentDescription = "Custom Avatar Preview",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .border(1.dp, AVABlue, CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.1f), CircleShape)
                                .border(1.dp, AVASubtext, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("☺", color = AVAText, fontSize = 18.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = {
                                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AVABlue),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Select", fontSize = 11.sp)
                        }
                        if (customAvatarPath != null) {
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = {
                                    context.getSharedPreferences("ava_config", 0).edit().apply {
                                        remove("custom_avatar_path")
                                        apply()
                                    }
                                    customAvatarPath = null
                                    AppLogger.i("MainActivity", "Custom avatar reset to default!")
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
                            ) {
                                Text("Reset", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Spacer(Modifier.height(16.dp))

            // ── Logs Console ───────────────────────────────────────────────────
            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
            val logs by AppLogger.logs.collectAsState()
            val reversedLogs = remember(logs) { logs.reversed() }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Logs Console", color = AVAText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row {
                    TextButton(
                        onClick = {
                            AppLogger.clear()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF5350))
                    ) {
                        Text("Clear", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            val allLogs = logs.joinToString("\n")
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(allLogs))
                            AppLogger.i("MainActivity", "Logs copied to clipboard!")
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = AVABlue)
                    ) {
                        Text("Copy", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = false
                ) {
                    items(reversedLogs) { logLine ->
                        Text(
                            text = logLine,
                            color = when {
                                logLine.contains("❌") -> Color(0xFFEF5350)
                                logLine.contains("⚠️") -> Color(0xFFFFCA28)
                                else -> Color(0xFF81C784)
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

        Spacer(Modifier.height(16.dp))

        // ── Mic button ────────────────────────────────────────────────────────
        val canStart = accessibilityGranted && overlayGranted && audioGranted && notificationsGranted && apiKey.isNotBlank()

        Button(
            onClick = onStartListening,
            enabled = canStart,
            modifier = Modifier.size(64.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (canStart) AVABlue else Color.Gray
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("🎤", fontSize = 28.sp)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (canStart) "Tap mic to give AVA a task" else "Grant permissions above to start",
            color = AVASubtext,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF2A2A3E)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = AVAText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(description, color = AVASubtext, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Text("✅", fontSize = 18.sp)
            } else {
                TextButton(
                    onClick = onGrant,
                    colors = ButtonDefaults.textButtonColors(contentColor = AVABlue),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Enable", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
