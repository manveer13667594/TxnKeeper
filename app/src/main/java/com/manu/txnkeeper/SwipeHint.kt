package com.manu.txnkeeper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.manu.manudesignsystem.components.ManuCard
import com.manu.manudesignsystem.theme.ManuDesignSystem

/**
 * A small hint card at the top of the home screen, one hint at a time:
 *   1. swipe left  -> voice entry
 *   2. swipe right -> camera
 * A hint goes away when it is closed with the cross, or when that shortcut is used for the first time;
 * then the next one shows. When both are gone nothing is shown (and it never comes back).
 * The animation file only has the left swipe, so the right one is that same file flipped.
 */
@Composable
fun SwipeHintCard(settings: AppSettings, modifier: Modifier = Modifier) {
    val voice = !settings.voiceHintDone
    val camera = !settings.cameraHintDone
    if (!voice && !camera) return

    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.swipe_left))
    // The drawing is black: paint it in the text colour so it shows in light and dark mode
    val tint = MaterialTheme.colorScheme.onSurface
    val dynamic = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR_FILTER,
            value = SimpleColorFilter(tint.toArgb()),
            keyPath = arrayOf("**")
        )
    )

    val title = if (voice) "Swipe left to add by voice" else "Swipe right to scan a receipt"
    val detail = if (voice) {
        "Anywhere on this screen. Say a transaction instead of typing it."
    } else {
        "Anywhere on this screen. Take or pick a photo and the form fills itself."
    }

    ManuCard(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(end = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LottieAnimation(
                    composition = composition,
                    iterations = LottieConstants.IterateForever,
                    dynamicProperties = dynamic,
                    modifier = Modifier
                        .size(84.dp)
                        // Voice = the drawing as it is (swipe left); camera = flipped (swipe right)
                        .graphicsLayer(scaleX = if (voice) 1f else -1f)
                )
                Column(
                    modifier = Modifier.padding(vertical = ManuDesignSystem.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = { if (voice) settings.markVoiceHintDone() else settings.markCameraHintDone() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close hint",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}