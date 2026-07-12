package com.ava.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ava.R
import java.io.File

/**
 * AVAAvatarButton — circular floating avatar button that fluidly morphs
 * into a pill-shaped transcription/action banner when activated.
 */
@Composable
fun AVAAvatarButton(
    isRunning: Boolean,
    isDone: Boolean,
    needsUser: Boolean,
    isError: Boolean,
    isListening: Boolean,
    liveTranscription: String,
    statusText: String,
    isUserExpanded: Boolean,
    onToggleExpand: (Boolean) -> Unit,
    onStopTask: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onClickText: () -> Unit
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

    // Determine target dimensions based on state (can be minimized to circle during active tasks)
    val isPill = isListening || needsUser || isUserExpanded
    val targetWidth = if (isPill) 280.dp else 36.dp

    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "MorphWidth"
    )

    // Glow configurations
    val glowColor = when {
        isError -> Color(0xFFEF5350)
        isDone -> Color(0xFF4FC3F7)
        needsUser -> Color(0xFFFFB74D)
        isRunning || isListening -> Color(0xFFFFFFFF)
        else -> Color.Transparent
    }

    val infiniteTransition = rememberInfiniteTransition(label = "UIEffects")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    val thinkingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ThinkingPulse"
    )

    // Outer container with padding so the canvas glow doesn't get clipped
    Box(
        modifier = Modifier
            .padding(16.dp)
            .wrapContentSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(animatedWidth)
                .heightIn(min = 36.dp)
                // 1. Blurred background state glow
                .drawBehind {
                    if (glowColor != Color.Transparent) {
                        val radiusPx = 16.dp.toPx()
                        val paddingPx = 3.dp.toPx()
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = Paint().apply {
                                color = glowColor.copy(alpha = glowAlpha).toArgb()
                                isAntiAlias = true
                                maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
                            }
                            val cornerRadiusPx = 18.dp.toPx() + paddingPx
                            drawRoundRect(
                                -paddingPx,
                                -paddingPx,
                                size.width + paddingPx,
                                size.height + paddingPx,
                                cornerRadiusPx,
                                cornerRadiusPx,
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
                    shape = RoundedCornerShape(18.dp)
                )
                // 3. Specular glass dome reflection (white glare offset to top-left)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.35f), // Shiny reflection glare
                            Color.Transparent
                        ),
                        center = Offset(8f, 8f),
                        radius = 40f
                    ),
                    shape = RoundedCornerShape(18.dp)
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
                    shape = RoundedCornerShape(18.dp)
                )
                .clip(RoundedCornerShape(18.dp))
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
                // 6. Click handler (only when in Circle state to expand)
                .then(
                    if (!isPill) {
                        Modifier.clickable { onToggleExpand(true) }
                    } else Modifier
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left avatar/smiley box (stays stationary on the left side)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .then(
                                if (isPill) {
                                    Modifier.clickable { onToggleExpand(false) }
                                } else Modifier
                            ),
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
                            AVASmileyFace(color = Color.White)
                        }
                    }

                    // Animated text container
                    AnimatedVisibility(
                        visible = isPill && animatedWidth > 120.dp,
                        enter = fadeIn(animationSpec = tween(200)),
                        exit = fadeOut(animationSpec = tween(150))
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 36.dp) // Leave exact space for the overlay Stop button!
                                .horizontalScroll(rememberScrollState())
                                .then(
                                    if (isPill && !isRunning) {
                                        Modifier.clickable { onClickText() }
                                    } else Modifier
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(Modifier.width(4.dp))
                            
                            val isThinking = isRunning && (
                                statusText.startsWith("Thinking", ignoreCase = true) || 
                                statusText.startsWith("Starting", ignoreCase = true)
                            )
                            
                            if (isListening) {
                                Text(
                                    text = if (liveTranscription.isBlank()) "Listening..." else liveTranscription,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal,
                                    fontStyle = FontStyle.Normal,
                                    maxLines = 1
                                )
                            } else if (isThinking) {
                                Text(
                                    text = "Thinking...",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.graphicsLayer { alpha = thinkingAlpha },
                                    maxLines = 1
                                )
                            } else {
                                // If the status text is long and is Done/Error/NeedsUser, we will show a title here
                                // and the full text in the expanded area below!
                                val displayText = when {
                                    statusText.length > 30 && isDone -> "Answer"
                                    statusText.length > 30 && needsUser -> "Question"
                                    statusText.length > 30 && isError -> "Error"
                                    isError -> statusText
                                    isDone -> "Done"
                                    needsUser -> statusText
                                    else -> statusText
                                }
                                Text(
                                    text = displayText,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontStyle = FontStyle.Italic,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
 
                // Stop button (white square) on the right edge of the banner (overlay)
                AnimatedVisibility(
                    visible = isPill && (isRunning || isListening || needsUser) && animatedWidth > 120.dp,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    enter = fadeIn(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(150))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(36.dp)
                            .clickable { onStopTask() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color.White) // sharp square
                        )
                    }
                }
            }
 
            // Extended content area below for long generated text
            val showExtendedText = isPill && statusText.length > 30 && (isDone || needsUser || isError)
            
            AnimatedVisibility(
                visible = showExtendedText,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                exit = androidx.compose.animation.shrinkVertically(animationSpec = tween(250)) + fadeOut(animationSpec = tween(200))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                ) {
                    // Subtle divider line
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.1f))
                    )
                    Spacer(Modifier.height(8.dp))
                    
                    // Display the full answer or message
                    Text(
                        text = statusText,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
        }
    }
}

/**
 * AVASmileyFace - Draws the happy AVA face (inverted curved eyes and a smile)
 * dynamically onto a canvas with pixel-perfect resolution.
 */
@Composable
fun AVASmileyFace(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val strokeWidthPx = 1.5.dp.toPx()
        
        // Left eye arc (inverted curve)
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(3.dp.toPx(), 4.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(3.5.dp.toPx(), 3.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeWidthPx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
        
        // Right eye arc (inverted curve)
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(11.5.dp.toPx(), 4.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(3.5.dp.toPx(), 3.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeWidthPx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
        
        // Smile mouth (smile curve)
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(4.5.dp.toPx(), 7.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(9.dp.toPx(), 7.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = strokeWidthPx,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        )
    }
}
