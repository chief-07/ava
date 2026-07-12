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
 * Designed with a premium "slightly dark frosted glass dome" aesthetic:
 *   - Native Window-Level background blur (configured in layout params)
 *   - Specular diagonal glare highlight creating a physical glass reflection effect
 *   - Gradient border reflection outline
 *   - State-driven glowing animations (Working/Done/Failed/Input)
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
    
    // Auto-refresh when avatar preference changes
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

    val glowColor = when {
        isError -> Color(0xFFEF5350)
        isDone -> Color(0xFF4FC3F7)
        needsUser -> Color(0xFFFFB74D)
        isRunning -> Color(0xFFFFFFFF)
        else -> Color.Transparent
    }

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

    // Outer container with padding so the canvas glow doesn't get clipped into a square
    Box(
        modifier = Modifier
            .padding(16.dp)
            .wrapContentSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                // 1. Blurred background state glow
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
                // 2. High-contrast frosted glass wash base
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2E2E3C).copy(alpha = 0.88f), // Lighter frosted center wash
                            Color(0xFF14141E).copy(alpha = 0.95f)  // Solid dark frosted edge
                        )
                    ),
                    shape = CircleShape
                )
                // 3. Specular glass dome reflection (white glare offset to top-left)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.35f), // Shiner reflection glare
                            Color.Transparent
                        ),
                        center = Offset(8f, 8f),
                        radius = 40f
                    ),
                    shape = CircleShape
                )
                // 4. Gradient glass border edge
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.65f), // High-contrast rim highlight
                            Color.White.copy(alpha = 0.15f)  // Bottom edge shadow
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(80f, 80f)
                    ),
                    shape = CircleShape
                )
                .clip(CircleShape)
                // 5. Drag gesture interception
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
                // 6. Click handler
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (customBitmap != null) {
                Image(
                    bitmap = customBitmap.asImageBitmap(),
                    contentDescription = "Custom Avatar",
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
