package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.apps.InstalledAppsProvider

@Composable
fun AppIconImage(
    packageName: String,
    appName: String,
    appsProvider: InstalledAppsProvider,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    val iconBitmap: ImageBitmap? by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = packageName,
    ) {
        value = appsProvider.getAppIcon(packageName)
    }

    val shape = RoundedCornerShape(size * 0.28f)
    val bmp = iconBitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "$appName icon",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                text = appName.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
