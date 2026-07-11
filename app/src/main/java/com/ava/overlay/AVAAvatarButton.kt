package com.ava.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ava.R

/**
 * AVAAvatarButton — circular floating avatar button docked in the status bar.
 * Tapping it triggers AVA's speech/mic listening mode.
 */
@Composable
fun AVAAvatarButton(
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color.White.copy(alpha = 0.15f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_ava_avatar),
            contentDescription = "AVA Avatar",
            modifier = Modifier.size(24.dp)
        )
    }
}
