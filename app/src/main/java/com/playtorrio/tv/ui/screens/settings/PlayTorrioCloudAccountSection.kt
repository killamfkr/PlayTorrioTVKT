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
import com.playtorrio.tv.data.cloud.CloudConfig
import com.playtorrio.tv.data.cloud.PlayTorrioCloudRepository
import com.playtorrio.tv.data.stremio.StremioAddonRepository
import kotlinx.coroutines.launch

private val AccentPrimary = Color(0xFF818CF8)

@Composable
fun PlayTorrioCloudAccountSection(
    onAddonsChanged: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(PlayTorrioCloudRepository.signedInEmail(ctx).orEmpty()) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(PlayTorrioCloudRepository.isSignedIn(ctx)) }

    LaunchedEffect(Unit) {
        if (signedIn) {
            runCatching { PlayTorrioCloudRepository.startupPullIfSignedIn(ctx) }
            onAddonsChanged()
        }
    }

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

    if (!CloudConfig.isConfigured()) {
        Text(
            "PlayTorrio Cloud is unavailable.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.45f),
        )
        return
    }

    Text(
        text = if (signedIn) {
            "Signed in as ${email.ifBlank { "your account" }}. Syncs continue watching, " +
                "Stremio addons, and IPTV portals with PlayTorrio on your phone."
        } else {
            "Sign in with the same email and password as PlayTorrio on your phone. " +
                "Syncs continue watching, Stremio addons, and IPTV portals."
        },
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.5f),
    )
    Spacer(Modifier.height(12.dp))

    if (!signedIn) {
        CloudTextField(
            value = email,
            onValueChange = { email = it; error = null; message = null },
            placeholder = "Email",
            imeAction = ImeAction.Next,
        )
        Spacer(Modifier.height(8.dp))
        CloudTextField(
            value = password,
            onValueChange = { password = it; error = null; message = null },
            placeholder = "Password",
            isPassword = true,
            imeAction = ImeAction.Done,
        )
        Spacer(Modifier.height(12.dp))
        CloudActionButton(
            label = if (busy) "" else "Sign in & sync",
            busy = busy,
            onClick = {
                if (email.isBlank() || password.isBlank()) {
                    error = "Email and password are required."
                    return@CloudActionButton
                }
                scope.launch {
                    busy = true
                    error = null
                    message = null
                    PlayTorrioCloudRepository.signIn(ctx, email.trim(), password)
                        .onSuccess {
                            signedIn = true
                            password = ""
                            email = PlayTorrioCloudRepository.signedInEmail(ctx).orEmpty()
                            onAddonsChanged()
                            message = "Signed in and synced from PlayTorrio Cloud."
                        }
                        .onFailure { error = it.message ?: "Sign in failed." }
                    busy = false
                }
            },
        )
    } else {
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
                            PlayTorrioCloudRepository.pullAll(ctx)
                            onAddonsChanged()
                            message = "Pulled latest addons and continue watching."
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
                            message = "Pushed addons and continue watching to cloud."
                        }.onFailure { error = it.message ?: "Push failed." }
                        busy = false
                    }
                },
            )
            CloudActionButton(
                label = if (busy) "" else "Sign out",
                busy = false,
                onClick = {
                    PlayTorrioCloudRepository.signOut(ctx)
                    signedIn = false
                    email = ""
                    password = ""
                    message = "Signed out."
                    error = null
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

@Composable
private fun CloudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    imeAction: ImeAction,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
        cursorBrush = SolidColor(AccentPrimary),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        modifier = Modifier
            .fillMaxWidth(0.75f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.04f))
            .border(
                1.dp,
                if (focused) AccentPrimary.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(10.dp),
            )
            .onFocusChanged { focused = it.hasFocus }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, color = Color.White.copy(alpha = 0.3f), fontSize = 14.sp)
            }
            inner()
        },
    )
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
