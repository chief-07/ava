package com.ava.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ava.service.AVAAccessibilityService
import com.ava.voice.SpeechInput
import kotlinx.coroutines.launch

private val AVABlue = Color(0xFF1A73E8)
private val AVADark = Color(0xFF1C1C2E)
private val AVAText = Color(0xFFFFFFFF)
private val AVASubtext = Color(0xFFAAAAAA)

/**
 * MainActivity — AVA's setup and launch screen.
 *
 * Responsibilities:
 *  1. Show permission status (accessibility, overlay)
 *  2. Let the user enter their Gemini API key
 *  3. Provide a mic button to give AVA a task
 *  4. If launched via "Hey Google, open AVA" — auto-start listening
 *
 * Phase 1: functional but minimal UI.
 * Phase 2: this gets replaced with a proper polished home screen.
 */
class MainActivity : ComponentActivity() {

    private lateinit var speechInput: SpeechInput

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speechInput = SpeechInput(this)

        // If launched from Google Assistant ("Hey Google, open AVA") — start listening
        val fromAssistant = intent?.action == Intent.ACTION_ASSIST
        if (fromAssistant) {
            startListeningForTask()
        }

        setContent {
            AVASetupScreen(
                onStartListening = { startListeningForTask() },
                onSaveApiKey = { key -> saveApiKey(key) },
                onOpenAccessibilitySettings = { openAccessibilitySettings() },
                onOpenOverlaySettings = { openOverlaySettings() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechInput.destroy()
    }

    private fun startListeningForTask() {
        lifecycleScope.launch {
            val task = speechInput.listenOnce()
            if (!task.isNullOrBlank()) {
                AVAAccessibilityService.instance?.startTask(task)
                // Move to background so AVA can work across apps
                moveTaskToBack(true)
            }
        }
    }

    private fun saveApiKey(key: String) {
        getSharedPreferences("ava_config", MODE_PRIVATE)
            .edit()
            .putString("gemini_api_key", key)
            .apply()
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
    onStartListening: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val context = LocalContext.current
    var apiKey by remember { mutableStateOf(
        context.getSharedPreferences("ava_config", 0)
            .getString("gemini_api_key", "") ?: ""
    )}
    var apiKeySaved by remember { mutableStateOf(false) }

    val accessibilityEnabled = AVAAccessibilityService.instance != null
    val overlayEnabled = Settings.canDrawOverlays(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AVADark)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // ── Header ───────────────────────────────────────────────────────────
        Text("AVA", color = AVABlue, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Text("AI Voice Agent", color = AVASubtext, fontSize = 16.sp)

        Spacer(Modifier.height(40.dp))

        // ── Permission cards ──────────────────────────────────────────────────
        PermissionCard(
            title = "Accessibility Service",
            description = "Lets AVA read the screen and perform actions",
            granted = accessibilityEnabled,
            onGrant = onOpenAccessibilitySettings
        )

        Spacer(Modifier.height(12.dp))

        PermissionCard(
            title = "Draw Over Apps",
            description = "Lets AVA show the top banner while working",
            granted = overlayEnabled,
            onGrant = onOpenOverlaySettings
        )

        Spacer(Modifier.height(24.dp))

        // ── API Key input ─────────────────────────────────────────────────────
        Text("Gemini API Key", color = AVAText, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                apiKeySaved = false
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Paste your Gemini API key", color = AVASubtext) },
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

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                onSaveApiKey(apiKey)
                apiKeySaved = true
            },
            modifier = Modifier.align(Alignment.End),
            colors = ButtonDefaults.buttonColors(containerColor = AVABlue)
        ) {
            Text(if (apiKeySaved) "✓ Saved" else "Save Key")
        }

        Spacer(Modifier.weight(1f))

        // ── Mic button ────────────────────────────────────────────────────────
        val canStart = accessibilityEnabled && overlayEnabled && apiKey.isNotBlank()

        Button(
            onClick = onStartListening,
            enabled = canStart,
            modifier = Modifier.size(80.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (canStart) AVABlue else Color.Gray
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("🎤", fontSize = 32.sp)
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = if (canStart) "Tap mic to give AVA a task"
                   else "Complete setup above first",
            color = AVASubtext,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(32.dp))
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
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = AVAText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(description, color = AVASubtext, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Text("✅", fontSize = 20.sp)
            } else {
                TextButton(
                    onClick = onGrant,
                    colors = ButtonDefaults.textButtonColors(contentColor = AVABlue)
                ) {
                    Text("Enable", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
