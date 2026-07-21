package com.playtorrio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.playtorrio.tv.data.cloud.PlayTorrioCloudRepository
import com.playtorrio.tv.data.stremio.StremioAddonRepository
import com.playtorrio.tv.ui.screens.cloud.PlayTorrioCloudSignInCard
import kotlinx.coroutines.launch

private val AccentPrimary = Color(0xFF818CF8)

@Composable
fun PlayTorrioCloudAccountSection(
    onAddonsChanged: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(32.dp))
    Text(
        text = "PLAYTORRIO CLOUD",
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
        ),
        color = Color.White.copy(alpha = 0.4f),
    )
    Spacer(Modifier.height(12.dp))

    PlayTorrioCloudSignInCard(
        compact = false,
        onSignedIn = onAddonsChanged,
    )

    if (PlayTorrioCloudRepository.isSignedIn(ctx)) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CloudActionButton(
                label = if (busy) "" else "Pull from cloud",
                busy = busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        message = null
                        runCatching {
                            PlayTorrioCloudRepository.pullForActiveProfile(ctx)
                            onAddonsChanged()
                            message = "Pulled latest data for this profile."
                        }.onFailure { error = it.message ?: "Pull failed." }
                        busy = false
                    }
                },
            )
            CloudActionButton(
                label = if (busy) "" else "Push to cloud",
                busy = busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        message = null
                        runCatching {
                            PlayTorrioCloudRepository.pushAll(ctx)
                            message = "Pushed profile data to cloud."
                        }.onFailure { error = it.message ?: "Push failed." }
                        busy = false
                    }
                },
            )
        }
    }

    message?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = Color(0xFF86EFAC), fontSize = 13.sp)
    }
    error?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = Color(0xFFF87171), fontSize = 13.sp)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloudActionButton(
    label: String,
    busy: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) AccentPrimary else AccentPrimary.copy(alpha = 0.7f))
            .onFocusChanged { focused = it.hasFocus }
            .focusable()
            .onKeyEvent { evt ->
                if (evt.type == KeyEventType.KeyDown &&
                    (evt.key == Key.DirectionCenter || evt.key == Key.Enter) &&
                    !busy
                ) {
                    onClick()
                    true
                } else false
            }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), Color.White, strokeWidth = 2.dp)
        } else {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}
