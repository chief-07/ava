package com.ava.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * Features a frosted glassmorphic background, linear gradient border highlight,
 * and a blurred, pulsing outer glow indicating active status:
 *   - White (Pulsing): Working / Executing task
 *   - Light Blue (Pulsing): Completed successfully
 *   - Light Red (Pulsing): Error / Failed
 *   - Amber (Pulsing): Request User Input / Clarification
 *
 * Supports dragging anywhere on the screen.
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
    val prefs = remember(context) { context.getSharedPreferences("ava_config", Context.MODE_PRIVATE) }
    val customPath = prefs.getString("custom_avatar_path", null)

    val customBitmap = remember(customPath) {
        if (customPath != null) {
            val file = File(customPath)
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

    // Set up a slow infinite pulsing transition (2000ms period)
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

    Box(
        modifier = Modifier
            .size(36.dp)
            // 1. Blurred pulsing background glow using native Canvas BlurMaskFilter
            .drawBehind {
                if (glowColor != Color.Transparent) {
                    val radiusPx = 8.dp.toPx()
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = Paint().apply {
                            color = glowColor.copy(alpha = glowAlpha).toArgb()
                            isAntiAlias = true
                            maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
                        }
                        // Draw the blurred glow slightly larger than the button (radius + 2dp)
                        drawCircle(
                            size.width / 2f,
                            size.height / 2f,
                            (size.width / 2f) + 1.dp.toPx(),
                            paint
                        )
                    }
                }
            }
            // 2. Frosted glass translucent background (darkened for high contrast with white face)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            // 3. Linear gradient reflection border outline
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.5f),   // Top-left reflection
                        Color.White.copy(alpha = 0.05f)  // Bottom-right shadow fade
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(80f, 80f)
                ),
                shape = CircleShape
            )
            .clip(CircleShape)
            // 4. Pointer input for drag gesture detection
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
            // 5. Standard click handler for tap triggers
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
