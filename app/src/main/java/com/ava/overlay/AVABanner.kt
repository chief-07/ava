package com.ava.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// AVA brand colors
private val AVABlue    = Color(0xFF1A73E8)
private val AVADark    = Color(0xFF1C1C2E)
private val AVAText    = Color(0xFFFFFFFF)
private val AVASubtext = Color(0xFFAAAAAA)
private val AVAStop    = Color(0xFFE53935)

/**
 * AVABanner — the top overlay banner shown during task execution.
 *
 * Mirrors the Voice Access banner pattern:
 * - Anchored to the top of the screen
 * - Shows task name + current action/status
 * - Stop button always visible
 * - User prompt input shown when agent needs clarification
 */
@Composable
fun AVABanner(
    task: String,
    status: String,
    showUserPrompt: Boolean,
    userPromptText: String,
    onStop: () -> Unit,
    onUserInput: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AVADark)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // ── Main banner row ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // AVA dot indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(AVABlue, RoundedCornerShape(50))
            )

            Spacer(Modifier.width(8.dp))

            // Status text — the main live readout
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.take(40) + if (task.length > 40) "…" else "",
                    color = AVASubtext,
                    fontSize = 10.sp,
                    maxLines = 1
                )
                Text(
                    text = status,
                    color = AVAText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // Stop button
            TextButton(
                onClick = onStop,
                colors = ButtonDefaults.textButtonColors(contentColor = AVAStop),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("STOP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── User prompt row (shown when agent needs input) ───────────────────
        if (showUserPrompt) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(Modifier.height(6.dp))

            UserInputRow(
                promptText = userPromptText,
                onSubmit = onUserInput
            )
        }
    }
}

@Composable
private fun UserInputRow(
    promptText: String,
    onSubmit: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    Column {
        Text(
            text = promptText,
            color = Color(0xFFFFD600),
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f).height(48.dp),
                placeholder = {
                    Text("Type your response...", fontSize = 12.sp, color = AVASubtext)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AVABlue,
                    unfocusedBorderColor = AVASubtext,
                    cursorColor = AVABlue,
                    focusedTextColor = AVAText,
                    unfocusedTextColor = AVAText
                ),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSubmit(inputText)
                        inputText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AVABlue),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text("OK", fontSize = 12.sp)
            }
        }
    }
}
