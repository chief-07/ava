package com.ava.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ava.service.AVAAccessibilityService
import com.ava.util.AppLogger
import com.ava.voice.SpeechInput
import kotlinx.coroutines.launch

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

    // Compose State variables for permissions status
    private var accessibilityEnabled by mutableStateOf(false)
    private var overlayEnabled by mutableStateOf(false)
    private var audioGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(this)  // enable file-backed log persistence
        speechInput = SpeechInput(this)

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
                onStartListening = { startListeningForTask() },
                onSaveApiKey = { key -> saveApiKey(key) },
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
        if (!audioGranted) {
            AppLogger.w(TAG, "Cannot listen: Microphone permission not granted")
            requestMicrophonePermission()
            return
        }

        AppLogger.i(TAG, "Listening for task...")
        lifecycleScope.launch {
            val task = speechInput.listenOnce()
            if (task.isNullOrBlank()) {
                AppLogger.w(TAG, "Speech input was empty or cancelled")
            } else {
                AppLogger.i(TAG, "Speech recognized: \"$task\"")
                
                val service = AVAAccessibilityService.instance
                if (service == null) {
                    AppLogger.e(TAG, "Accessibility Service is not enabled. Go to Settings and turn it on.")
                } else {
                    val started = service.startTask(task)
                    if (started) {
                        AppLogger.i(TAG, "Task started successfully. Minimizing app...")
                        // Move to background so AVA can work across apps
                        moveTaskToBack(true)
                    } else {
                        AppLogger.e(TAG, "Failed to start task. Check API key.")
                    }
                }
            }
        }
    }

    private fun saveApiKey(key: String) {
        getSharedPreferences("ava_config", MODE_PRIVATE)
            .edit()
            .putString("gemini_api_key", key)
            .apply()
        AppLogger.i(TAG, "Gemini API key saved")
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
    onStartListening: () -> Unit,
    onSaveApiKey: (String) -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AVADark)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        // ── Header ───────────────────────────────────────────────────────────
        Text("AVA", color = AVABlue, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text("AI Voice Agent", color = AVASubtext, fontSize = 14.sp)

        Spacer(Modifier.height(16.dp))

        // ── Scrollable list of setup items ────────────────────────────────────
        Column(modifier = Modifier.weight(1f)) {
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

            // ── Logs Console ───────────────────────────────────────────────────
            Text("Logs Console", color = AVAText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            val logs by AppLogger.logs.collectAsState()
            val reversedLogs = remember(logs) { logs.reversed() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // expand to fill remaining space
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
