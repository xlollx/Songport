package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.ads.Ads

/** Primo avvio: le tre mosse, come sono protetti gli accessi, perche' ci sono le pubblicita'. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(Brand.Indigo, Brand.Violet, Brand.Magenta)))
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            ) {
                Column {
                    BrandMark(64.dp, tint = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
                }
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Step(Icons.Filled.AccountCircle, stringResource(R.string.onboarding_step1_title), stringResource(R.string.onboarding_step1_desc))
                Step(Icons.Filled.PlaylistAdd, stringResource(R.string.onboarding_step2_title), stringResource(R.string.onboarding_step2_desc))
                Step(Icons.Filled.Schedule, stringResource(R.string.onboarding_step3_title), stringResource(R.string.onboarding_step3_desc))

                SectionCard(stringResource(R.string.security_title)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.onboarding_security), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.onboarding_credentials), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Con gli annunci si spiega perche' ci sono; senza, che l'app vive di contributi volontari.
                        Text(stringResource(if (Ads.enabled) R.string.ads_why_title else R.string.free_why_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(stringResource(if (Ads.enabled) R.string.ads_why_body else R.string.free_why_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        if (BuildConfig.KOFI_URL.isNotBlank()) {
                            TextButton(onClick = { openSupport(ctx) }) {
                                Icon(Icons.Filled.Favorite, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.support_button), color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onboarding_start)) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, desc: String) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
