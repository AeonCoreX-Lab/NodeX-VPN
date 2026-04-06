// shared/src/commonMain/kotlin/com/nodex/vpn/ui/screens/AuthScreen.kt
package com.nodex.vpn.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.nodex.vpn.auth.*
import com.nodex.vpn.ui.responsive.*
import com.nodex.vpn.ui.theme.NodeXColors
import kotlin.math.*

@Composable
fun AuthScreen(viewModel: AuthViewModel, windowSize: WindowSizeClass) {
    val uiState by viewModel.uiState.collectAsState()
    val fm = LocalFocusManager.current
    val inf = rememberInfiniteTransition(label = "auth_bg")
    val bgAnim by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(30000, easing = LinearEasing)), label = "bg")

    Box(modifier = Modifier.fillMaxSize().background(NodeXColors.Void)) {
        Canvas(modifier = Modifier.fillMaxSize()) { drawAuthBg(bgAnim) }

        if (windowSize.isExpanded) {
            // ── Desktop: logo on left, form on right ──────────────────────────
            Row(modifier = Modifier.fillMaxSize()) {
                // Left panel - branding
                Box(
                    modifier = Modifier.weight(0.45f).fillMaxHeight().background(
                        Brush.verticalGradient(listOf(NodeXColors.CyanGlow.copy(0.08f), NodeXColors.PurpleNeon.copy(0.05f), NodeXColors.Void))
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(40.dp)) {
                        AuthLogoLarge()
                        Spacer(Modifier.height(8.dp))
                        FeaturePill(Icons.Default.Shield,    "Zero-log VPN")
                        FeaturePill(Icons.Default.Public,    "Tor Network powered")
                        FeaturePill(Icons.Default.Speed,     "Rust core engine")
                        FeaturePill(Icons.Default.VpnLock,   "obfs4 bridge support")
                        FeaturePill(Icons.Default.Language,  "18+ exit countries")
                    }
                }
                // Right panel - form
                Box(modifier = Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    AuthFormCard(uiState, viewModel, fm, windowSize)
                }
            }
        } else if (windowSize.isMedium) {
            // ── Tablet: centered wider card ───────────────────────────────────
            Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp).padding(32.dp)) {
                    AuthLogoSmall()
                    Spacer(Modifier.height(28.dp))
                    AuthFormCard(uiState, viewModel, fm, windowSize)
                }
            }
        } else {
            // ── Phone: full-screen scroll ─────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AuthLogoSmall()
                Spacer(Modifier.height(32.dp))
                AuthFormCard(uiState, viewModel, fm, windowSize)
                Spacer(Modifier.height(20.dp))
                Text(
                    "By continuing you agree to our Privacy Policy.\nNodeX VPN does not log or track user activity.",
                    style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted, textAlign = TextAlign.Center, lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun AuthFormCard(uiState: AuthUiState, viewModel: AuthViewModel, fm: androidx.compose.ui.focus.FocusManager, windowSize: WindowSizeClass) {
    Surface(
        shape = RoundedCornerShape(if (windowSize.isExpanded) 24.dp else 20.dp),
        color = NodeXColors.DeepSpace.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, NodeXColors.CyanGlow.copy(alpha = 0.18f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(if (windowSize.isExpanded) 36.dp else 28.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            ModeToggle(uiState.isSignUp, viewModel::toggleMode)
            Spacer(Modifier.height(24.dp))
            AnimatedVisibility(uiState.globalError != null, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                uiState.globalError?.let { Column { ErrorCard(it, viewModel::clearError); Spacer(Modifier.height(14.dp)) } }
            }
            AnimatedVisibility(uiState.resetSent) {
                Column { SuccessCard("Password reset email sent!"); Spacer(Modifier.height(14.dp)) }
            }
            NodeXTextField(uiState.email, viewModel::onEmailChange, "Email", Icons.Outlined.Email, uiState.emailError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { fm.moveFocus(FocusDirection.Down) }))
            Spacer(Modifier.height(14.dp))
            NodeXTextField(uiState.password, viewModel::onPasswordChange, "Password", Icons.Outlined.Lock, uiState.passwordError,
                isPassword = true, passwordVisible = uiState.passwordVisible, onToggleVisible = viewModel::togglePasswordVisible,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (uiState.isSignUp) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(onNext = { fm.moveFocus(FocusDirection.Down) }, onDone = { fm.clearFocus(); viewModel.submitEmailAuth() }))
            AnimatedVisibility(uiState.isSignUp) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    NodeXTextField(uiState.confirmPassword, viewModel::onConfirmChange, "Confirm Password", Icons.Outlined.LockOpen, uiState.confirmError,
                        isPassword = true, passwordVisible = uiState.passwordVisible, onToggleVisible = viewModel::togglePasswordVisible,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { fm.clearFocus(); viewModel.submitEmailAuth() }))
                }
            }
            if (!uiState.isSignUp) {
                TextButton(onClick = viewModel::resetPassword, modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) {
                    Text("Forgot password?", style = MaterialTheme.typography.labelSmall, color = NodeXColors.CyanGlow.copy(0.8f))
                }
            } else Spacer(Modifier.height(10.dp))
            Spacer(Modifier.height(16.dp))
            PrimaryAuthButton(if (uiState.isSignUp) "Create Account" else "Sign In", uiState.isLoading) { fm.clearFocus(); viewModel.submitEmailAuth() }
            Spacer(Modifier.height(18.dp))
            DividerWithText("or continue with")
            Spacer(Modifier.height(14.dp))
            GoogleAuthButton(uiState.isGoogleLoading, viewModel::signInWithGoogle)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun AuthLogoLarge() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(90.dp).background(NodeXColors.CyanGlow.copy(0.12f), RoundedCornerShape(28.dp))) {
            Icon(Icons.Default.Shield, null, tint = NodeXColors.CyanGlow, modifier = Modifier.size(52.dp))
        }
        Text("NodeX VPN", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = NodeXColors.TextPrimary)
        Text("Serverless · Anonymous · Encrypted", style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AuthLogoSmall() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp).background(NodeXColors.CyanGlow.copy(0.15f), RoundedCornerShape(18.dp))) {
            Icon(Icons.Default.Shield, null, tint = NodeXColors.CyanGlow, modifier = Modifier.size(34.dp))
        }
        Text("NodeX VPN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = NodeXColors.TextPrimary)
        Text("Encrypted · Anonymous · Fast", style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextSecondary)
    }
}

@Composable
private fun FeaturePill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = NodeXColors.DeepSpace.copy(0.7f), border = BorderStroke(1.dp, NodeXColors.NebulaDark), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = NodeXColors.CyanGlow, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextPrimary)
        }
    }
}

@Composable
private fun ModeToggle(isSignUp: Boolean, onToggle: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = NodeXColors.DarkMatter) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            listOf("Sign In" to false, "Sign Up" to true).forEach { (label, mode) ->
                val active = isSignUp == mode
                Surface(onClick = { if (!active) onToggle() }, shape = RoundedCornerShape(10.dp), color = if (active) NodeXColors.CyanGlow else Color.Transparent, modifier = Modifier.weight(1f)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 10.dp)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) NodeXColors.Void else NodeXColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeXTextField(
    value: String, onValueChange: (String) -> Unit, label: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector, error: String? = null,
    isPassword: Boolean = false, passwordVisible: Boolean = false, onToggleVisible: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column {
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            leadingIcon = { Icon(leadingIcon, null, tint = if (error != null) NodeXColors.RedAlert else NodeXColors.TextSecondary, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (isPassword && onToggleVisible != null) {{
                IconButton(onClick = onToggleVisible) { Icon(if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, tint = NodeXColors.TextMuted, modifier = Modifier.size(20.dp)) }
            }} else null,
            isError = error != null, singleLine = true,
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NodeXColors.CyanGlow, unfocusedBorderColor = NodeXColors.NebulaDark,
                errorBorderColor = NodeXColors.RedAlert, focusedLabelColor = NodeXColors.CyanGlow,
                unfocusedLabelColor = NodeXColors.TextMuted, errorLabelColor = NodeXColors.RedAlert,
                focusedTextColor = NodeXColors.TextPrimary, unfocusedTextColor = NodeXColors.TextPrimary,
                cursorColor = NodeXColors.CyanGlow, focusedContainerColor = NodeXColors.DarkMatter,
                unfocusedContainerColor = NodeXColors.DarkMatter, errorContainerColor = NodeXColors.RedAlert.copy(0.08f),
            ),
        )
        if (error != null) Text(error, style = MaterialTheme.typography.labelSmall, color = NodeXColors.RedAlert, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
    }
}

@Composable
private fun PrimaryAuthButton(text: String, isLoading: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !isLoading, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NodeXColors.CyanGlow, disabledContainerColor = NodeXColors.CyanDim)) {
        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NodeXColors.Void, strokeWidth = 2.dp)
        else Text(text, fontWeight = FontWeight.Bold, color = NodeXColors.Void, fontSize = 15.sp)
    }
}

@Composable
private fun GoogleAuthButton(isLoading: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !isLoading, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, NodeXColors.NebulaDark), colors = ButtonDefaults.outlinedButtonColors(containerColor = NodeXColors.DarkMatter)) {
        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NodeXColors.CyanGlow, strokeWidth = 2.dp)
        else {
            Canvas(Modifier.size(20.dp)) {
                val cx = size.width/2; val cy = size.height/2; val r = size.minDimension*0.42f
                drawArc(Color(0xFF4285F4), -10f, 100f, false, style = Stroke(r*0.3f))
                drawArc(Color(0xFF34A853),  90f,  90f, false, style = Stroke(r*0.3f))
                drawArc(Color(0xFFFBBC05), 180f,  45f, false, style = Stroke(r*0.3f))
                drawArc(Color(0xFFEA4335), 225f, 125f, false, style = Stroke(r*0.3f))
                drawLine(Color(0xFF4285F4), Offset(cx, cy), Offset(cx+r, cy), r*0.3f)
            }
            Spacer(Modifier.width(10.dp))
            Text("Continue with Google", color = NodeXColors.TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}

@Composable
private fun DividerWithText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = NodeXColors.NebulaDark)
        Text(text, style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
        HorizontalDivider(Modifier.weight(1f), color = NodeXColors.NebulaDark)
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = NodeXColors.RedAlert.copy(0.12f), border = BorderStroke(1.dp, NodeXColors.RedAlert.copy(0.4f))) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.ErrorOutline, null, tint = NodeXColors.RedAlert, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.labelSmall, color = NodeXColors.RedAlert, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.Close, null, tint = NodeXColors.RedAlert, modifier = Modifier.size(14.dp)) }
        }
    }
}

@Composable
private fun SuccessCard(message: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = NodeXColors.GreenPulse.copy(0.1f), border = BorderStroke(1.dp, NodeXColors.GreenPulse.copy(0.4f))) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.CheckCircle, null, tint = NodeXColors.GreenPulse, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.labelSmall, color = NodeXColors.GreenPulse)
        }
    }
}

private fun DrawScope.drawAuthBg(angle: Float) {
    val w = size.width; val h = size.height
    val cx1 = w*0.2f + w*0.3f* sin(Math.toRadians(angle.toDouble())).toFloat()
    val cy1 = h*0.2f + h*0.2f* cos(Math.toRadians(angle.toDouble())).toFloat()
    val cx2 = w*0.8f + w*0.2f* sin(Math.toRadians((angle+120).toDouble())).toFloat()
    val cy2 = h*0.7f + h*0.15f*cos(Math.toRadians((angle+120).toDouble())).toFloat()
    drawCircle(NodeXColors.CyanGlow.copy(0.05f),  w*0.5f, Offset(cx1, cy1))
    drawCircle(NodeXColors.PurpleNeon.copy(0.04f), w*0.45f, Offset(cx2, cy2))
    val step = 45f
    var x = 0f; while (x <= w) { drawLine(NodeXColors.CyanGlow.copy(0.02f), Offset(x,0f), Offset(x,h)); x+=step }
    var y = 0f; while (y <= h) { drawLine(NodeXColors.CyanGlow.copy(0.02f), Offset(0f,y), Offset(w,y)); y+=step }
}
