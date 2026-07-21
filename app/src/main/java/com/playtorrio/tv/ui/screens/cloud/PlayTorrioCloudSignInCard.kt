package com.playtorrio.tv.ui.screens.cloud

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.playtorrio.tv.data.cloud.CloudConfig
import com.playtorrio.tv.data.cloud.PlayTorrioCloudRepository
import kotlinx.coroutines.launch

private val AccentPrimary = Color(0xFF818CF8)

@Composable
fun PlayTorrioCloudSignInCard(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSignedIn: () -> Unit = {},
) {
    if (!CloudConfig.isConfigured()) return

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(PlayTorrioCloudRepository.signedInEmail(ctx).orEmpty()) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var signedIn by remember { mutableStateOf(PlayTorrioCloudRepository.isSignedIn(ctx)) }

    Column(
        modifier = modifier
            .fillMaxWidth(if (compact) 0.72f else 0.55f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "PLAYTORRIO CLOUD",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
            ),
            color = Color.White.copy(alpha = 0.45f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (signedIn) {
                "Signed in as ${email.ifBlank { "your account" }}. " +
                    "Pick a profile to sync your place, shows, and favorites."
            } else {
                "Sign in with your PlayTorrio account to sync continue watching, " +
                    "My List, and addons across devices."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))

        if (!signedIn) {
            CloudField(
                value = email,
                onValueChange = { email = it; error = null; message = null },
                placeholder = "Email",
                imeAction = ImeAction.Next,
            )
            Spacer(Modifier.height(8.dp))
            CloudField(
                value = password,
                onValueChange = { password = it; error = null; message = null },
                placeholder = "Password",
                isPassword = true,
                imeAction = ImeAction.Done,
            )
            Spacer(Modifier.height(12.dp))
            CloudButton(
                label = if (busy) "" else "Sign in",
                busy = busy,
                onClick = {
                    if (email.isBlank() || password.isBlank()) {
                        error = "Email and password are required."
                        return@CloudButton
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
                                message = "Signed in. Select your profile to sync."
                                onSignedIn()
                            }
                            .onFailure { error = it.message ?: "Sign in failed." }
                        busy = false
                    }
                },
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CloudButton(
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
            Text(it, color = Color(0xFF86EFAC), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Color(0xFFF87171), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CloudField(
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
        visualTransformation = if (isPassword) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        modifier = Modifier
            .fillMaxWidth()
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
private fun CloudButton(
    label: String,
    busy: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) AccentPrimary else AccentPrimary.copy(alpha = 0.75f))
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
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), Color.White, strokeWidth = 2.dp)
        } else {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}
