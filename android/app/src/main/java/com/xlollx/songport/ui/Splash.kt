package com.xlollx.songport.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import kotlinx.coroutines.delay

/**
 * The opening screen, over the content on a cold start: the brand beam across the whole screen
 * (the system splash can only be a flat colour), the mark on its glass tile at the hero's scale,
 * the app's name under it. It holds for [HOLD_MS] and fades out in [FADE_MS]; the content
 * composes underneath meanwhile, so nothing waits for it. With the system's animations off
 * (animator duration scale 0, as on the test emulators) it is not shown at all.
 */
object Splash {
    const val HOLD_MS = 800L
    const val FADE_MS = 300
    /** The system splash view fades this long before it is removed, so the two screens blend. */
    const val SYSTEM_FADE_MS = 200L

    fun animationsOff(ctx: Context): Boolean = android.provider.Settings.Global.getFloat(
        ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f
}

/**
 * Shows [BrandSplash] over the caller's content when [cold] (a process start, not a recreation)
 * and the system animates; [onVisible] reports whether it is on screen, for the system bars.
 */
@Composable
fun SplashOverlay(cold: Boolean, onVisible: (Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var visible by remember { mutableStateOf(cold && !Splash.animationsOff(ctx)) }
    LaunchedEffect(Unit) {
        if (visible) { delay(Splash.HOLD_MS); visible = false }
    }
    LaunchedEffect(visible) { onVisible(visible) }
    AnimatedVisibility(visible, exit = fadeOut(tween(Splash.FADE_MS))) {
        BrandSplash()
    }
}

/** The beam full-bleed, the tile with the mark in the middle, the name below in white. */
@Composable
fun BrandSplash() {
    Column(
        Modifier.fillMaxSize().brandBeam(edgeY = 0.3f)
            // Touches wait for the real screen.
            .pointerInput(Unit) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandTile(88.dp)
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.app_name), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
