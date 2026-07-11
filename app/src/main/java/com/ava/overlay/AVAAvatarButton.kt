package com.ava.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.content.SharedPreferences
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ava.R
import java.io.File

/**
 * AVAAvatarButton — circular floating avatar button docked in the status bar.
 * Features a slightly dark frosted glassmorphic background (high-contrast with white face),
 * linear gradient border highlight, and a blurred pulsing outer glow:
 *   - White (Pulsing): Working / Executing task
 *   - Light Blue (Pulsing): Completed successfully
 *   - Light Red (Pulsing): Error / Failed
 *   - Amber (Pulsing): Request User Input / Clarification
 *
 * Implements padding containers to prevent square clipping of outer glow,
 * and preference listeners to instantly refresh the custom avatar.
 */
@Composable
fun AVAAvatarButton(
    isRunning: Boolean,
    isDone: Boolean,
    needsUser: Boolean,
    isError: Boolean,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    
    // Dynamic preference-change tracking for immediate UI updates
    var customPath by remember {
        mutableStateOf(
            context.getSharedPreferences("ava_config", Context.MODE_PRIVATE)
                .getString("custom_avatar_path", null)
        )
    }

    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("ava_config", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            if (key == "custom_avatar_path") {
                customPath = sharedPrefs.getString("custom_avatar_path", null)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val customBitmap = remember(customPath) {
        if (customPath != null) {
            val file = File(customPath!!)
            if (file.exists()) {
                try {
                    BitmapFactory.decodeFile(file.absolutePath)
                } catch (e: Exception) {
                    null
                }
            } else null
        } else null
    }

    // Determine target glow color based on the current agent state
    val glowColor = when {
        isError -> Color(0xFFEF5350)     // Soft Red
        isDone -> Color(0xFF4FC3F7)      // Light Blue
        needsUser -> Color(0xFFFFB74D)   // Amber/Orange
        isRunning -> Color(0xFFFFFFFF)   // White
        else -> Color.Transparent
    }

    // Slow infinite pulsing transition
    val infiniteTransition = rememberInfiniteTransition(label = "GlowPulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    // Outer wrapping Box with padding. This ensures that the overall window size
    // is larger than the 36.dp button, giving the glow plenty of room to render
    // without getting clipped at the WindowManager layout boundaries!
    Box(
        modifier = Modifier
            .padding(16.dp)
            .wrapContentSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                // 1. Blurred pulsing background glow (drawBehind has room because of outer padding)
                .drawBehind {
                    if (glowColor != Color.Transparent) {
                        val radiusPx = 8.dp.toPx()
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = Paint().apply {
                                color = glowColor.copy(alpha = glowAlpha).toArgb()
                                isAntiAlias = true
                                maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
                            }
                            drawCircle(
                                size.width / 2f,
                                size.height / 2f,
                                (size.width / 2f) + 1.dp.toPx(),
                                paint
                            )
                        }
                    }
                }
                // 2. Slightly dark frosted glassmorphic background wash (50% frosted sheen)
                .background(Color(0xFF202026).copy(alpha = 0.50f), CircleShape)
                // 3. Dual-gradient reflection border outline
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.45f), // Shiny reflection highlight
                            Color.White.copy(alpha = 0.05f) // Darker bottom edge
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(100f, 100f)
                    ),
                    shape = CircleShape
                )
                .clip(CircleShape)
                // 4. Pointer input for drag gestures
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    )
                }
                // 5. Click handler
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (customBitmap != null) {
                Image(
                    bitmap = customBitmap.asImageBitmap(),
                    contentDescription = "AVA Custom Avatar",
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.ic_ava_avatar),
                    contentDescription = "AVA Avatar",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
