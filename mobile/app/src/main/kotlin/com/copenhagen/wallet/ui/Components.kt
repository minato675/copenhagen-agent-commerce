package com.copenhagen.wallet.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.copenhagen.wallet.BuildConfig
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copenhagen.wallet.data.*

object Carbon {
    val Canvas = Color(0xFFF5F6F8); val Surface = Color.White
    val Blue = Color(0xFF2457E6); val Ink = Color(0xFF111318)
    val Muted = Color(0xFF5E6572); val Layer = Color(0xFFECEFF3)
    val Line = Color(0xFFDFE3EA); val Boundary = Color(0xFF7A8291)
    val Error = Color(0xFFC43345); val Positive = Color(0xFF087A55); val Warning = Color(0xFF996000)
}
@Composable fun RemoteImage(url: String?, description: String?, modifier: Modifier = Modifier) {
    val imageUrl = url?.takeIf { it.startsWith("https://") || (BuildConfig.DEBUG && it.startsWith("http://")) }
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Carbon.Layer), contentAlignment = Alignment.Center) {
        if (!loaded) Glyph(WalletIcon.Box, null, Modifier.size(28.dp), Carbon.Muted)
        AsyncImage(model = imageUrl, contentDescription = description,
            contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize(),
            onLoading = { loaded = false }, onSuccess = { loaded = true }, onError = { loaded = false })
    }
}
@Composable fun CopenhagenTheme(content: @Composable () -> Unit) = MotionPreferences {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Carbon.Blue, onPrimary = Color.White,
            surface = Carbon.Surface, background = Carbon.Canvas, onBackground = Carbon.Ink, onSurface = Carbon.Ink,
            surfaceVariant = Carbon.Layer, onSurfaceVariant = Carbon.Muted, outline = Carbon.Boundary,
            outlineVariant = Carbon.Line, error = Carbon.Error, surfaceContainer = Carbon.Canvas,
            secondaryContainer = Color(0xFFE5ECFF), onSecondaryContainer = Carbon.Blue),
        shapes = Shapes(RoundedCornerShape(8.dp), RoundedCornerShape(12.dp), RoundedCornerShape(20.dp), RoundedCornerShape(24.dp), RoundedCornerShape(32.dp)),
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp),
            headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.6).sp),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        ), content = content)
}
@Composable fun Page(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = if (maxWidth < 360.dp) 16.dp else 20.dp
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}
@Composable fun Heading(text: String) { Text(text, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() }) }
@Composable fun Section(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (action != null) TextButton(onClick = onAction) { Text(action, style = MaterialTheme.typography.bodyMedium) }
    }
}
@Composable fun Action(text: String, enabled: Boolean = true, tag: String = "", onClick: () -> Unit) =
    Action(text, enabled, tag, loading = false, onClick = onClick)

@Composable fun Action(text: String, enabled: Boolean = true, tag: String = "", loading: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag).pressMotion(interaction)
        .then(if (focused) Modifier.border(3.dp, Carbon.Blue, RoundedCornerShape(28.dp)) else Modifier),
        enabled = enabled && !loading, shape = RoundedCornerShape(28.dp), interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Carbon.Ink, contentColor = Color.White,
            disabledContainerColor = if (enabled) Carbon.Ink else Carbon.Layer,
            disabledContentColor = if (enabled) Color.White else Carbon.Muted)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge)
            ReadActivity(loading, Color.White, Modifier.align(Alignment.CenterEnd))
        }
    }
}
@Composable fun Secondary(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    Secondary(text, enabled, loading = false, onClick = onClick)

@Composable fun Secondary(text: String, enabled: Boolean = true, loading: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp).pressMotion(interaction), enabled = enabled && !loading,
        shape = RoundedCornerShape(28.dp), interactionSource = interaction,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Carbon.Blue else Carbon.Boundary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Carbon.Ink,
            disabledContentColor = if (enabled) Carbon.Ink else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)),
        contentPadding = PaddingValues(16.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(text)
            ReadActivity(loading, Carbon.Ink, Modifier.align(Alignment.CenterEnd))
        }
    }
}
@Composable fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true, maxLength: Int = 200, multiline: Boolean = false,
    revealSecret: Boolean = false, trailingIcon: (@Composable () -> Unit)? = null) {
    OutlinedTextField(value, { onChange(it.take(maxLength)) }, modifier.fillMaxWidth(),
        label = { Text(label) }, enabled = enabled, singleLine = !multiline, shape = RoundedCornerShape(16.dp),
        visualTransformation = if (secret && !revealSecret) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailingIcon,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = !secret),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Carbon.Surface,
            unfocusedContainerColor = Carbon.Surface, disabledContainerColor = Carbon.Layer,
            unfocusedBorderColor = Carbon.Boundary, focusedBorderColor = Carbon.Blue))
}
@Composable fun Notice(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Carbon.Layer).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Glyph(WalletIcon.Info, tint = Carbon.Muted)
        Text(text, Modifier.weight(1f), color = Carbon.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable fun Tile(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val reduced = LocalReducedMotion.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Carbon.Surface)
        .then(if (focused) Modifier.border(2.dp, Carbon.Blue, RoundedCornerShape(24.dp)) else Modifier)
        .then(if (onClick == null) Modifier else Modifier.pressMotion(source).clickable(source, indication = null, role = Role.Button, onClick = onClick))
        .animateContentSize(if (reduced) snap() else Motion.surface()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable fun MerchantMark(name: String, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(17.dp)).background(Carbon.Layer), contentAlignment = Alignment.Center) {
        Text(name.take(1), style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
    }
}
@Composable fun StatusPill(status: String, uncertain: Boolean = false) {
    val positive = status == "COMPLETED" && !uncertain
    val warning = uncertain || status in listOf("PENDING", "PAYMENT_METHOD_REQUIRED", "APPROVED")
    val tint = if (positive) Carbon.Positive else if (warning) Carbon.Warning else Carbon.Muted
    Row(Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = .08f).compositeOver(Carbon.Surface)).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(if (positive) WalletIcon.Check else if (warning) WalletIcon.Clock else WalletIcon.Info, modifier = Modifier.size(16.dp), tint = tint)
        Text(if (uncertain) "상태 확인 필요" else statusLabel(status), style = MaterialTheme.typography.labelMedium, color = tint)
    }
}
@Composable fun ApprovalTile(value: Approval, highlight: Boolean = false, onVisible: (() -> Unit)? = null, click: () -> Unit) {
    LaunchedEffect(value) { onVisible?.invoke() }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val background by animateColorAsState(if (highlight) Color(0xFFE5ECFF) else Carbon.Surface,
        tween(if (LocalReducedMotion.current) 0 else Motion.Surface), label = "receipt-arrival")
    Column(Modifier.fillMaxWidth().sharedObject("approval-${value.id}").clip(RoundedCornerShape(20.dp))
        .background(background).then(if (focused) Modifier.border(2.dp, Carbon.Blue, RoundedCornerShape(20.dp)) else Modifier)
        .pressMotion(source).clickable(source, indication = null, role = Role.Button, onClick = click).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val firstItem = value.displaySummary.firstOrNull { !it.imageUrl.isNullOrBlank() }
                ?: value.displaySummary.firstOrNull { it.name.isNotBlank() }
            if (firstItem != null) ProductThumbnail(firstItem.imageUrl, Modifier.size(56.dp))
            else MerchantMark(value.displayName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value.displayName, style = MaterialTheme.typography.titleMedium)
                firstItem?.name?.takeIf { it.isNotBlank() }?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = Carbon.Muted)
                }
            }
            Glyph(WalletIcon.ArrowRight, tint = Carbon.Muted, modifier = Modifier.size(20.dp))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(approvalMoney(value.amountMinor, value.currency), style = MaterialTheme.typography.titleLarge)
            StatusPill(value.status)
        }
    }
}
@Composable fun Confirm(title: String, confirm: String, dismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = Carbon.Error) } },
        dismissButton = { TextButton(dismiss) { Text("취소") } }, shape = RoundedCornerShape(28.dp), containerColor = Carbon.Surface)
}
