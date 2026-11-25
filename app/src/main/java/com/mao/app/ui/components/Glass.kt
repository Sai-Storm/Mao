package com.mao.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mao.app.R
import com.mao.app.ui.theme.MaoColors

/** Deep charcoal backdrop with two soft golden glows. */
@Composable
fun MaoBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaoColors.Charcoal900)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(MaoColors.Gold.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.1f, size.height * 0.05f),
                        radius = size.maxDimension * 0.55f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(MaoColors.GoldDeep.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.9f),
                        radius = size.maxDimension * 0.5f,
                    )
                )
            },
        content = content,
    )
}

val GlassShape = RoundedCornerShape(22.dp)

fun Modifier.glass(shape: Shape = GlassShape, strong: Boolean = false): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            if (strong) listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.07f))
            else listOf(Color.White.copy(alpha = 0.085f), Color.White.copy(alpha = 0.04f))
        )
    )
    .border(
        1.dp,
        Brush.linearGradient(listOf(MaoColors.Gold.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f), MaoColors.Gold.copy(alpha = 0.15f))),
        shape,
    )

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .glass()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun GoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val active = enabled && !loading
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) Brush.horizontalGradient(listOf(MaoColors.GoldSoft, MaoColors.Gold, MaoColors.GoldDeep))
                else Brush.horizontalGradient(listOf(MaoColors.Charcoal700, MaoColors.Charcoal700))
            )
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = MaoColors.Gold, strokeWidth = 2.5.dp)
        } else {
            if (icon != null) {
                Icon(icon, null, tint = if (active) MaoColors.OnGold else MaoColors.TextMuted, modifier = Modifier.size(20.dp))
                Box(Modifier.size(8.dp))
            }
            Text(text, color = if (active) MaoColors.OnGold else MaoColors.TextMuted, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaoColors.Gold, icon: ImageVector? = null) {
    Row(
        modifier
            .height(48.dp)
            .glass(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(20.dp)); Box(Modifier.size(8.dp)) }
        Text(text, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** Round gold-on-glass icon button with a 48dp touch target. */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaoColors.Gold, size: Dp = 44.dp) {
    Box(
        modifier
            .size(size)
            .glass(CircleShape)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun Avatar(url: String?, name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, isGroup: Boolean = false) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(MaoColors.GoldDeep, MaoColors.Gold.copy(alpha = 0.7f))))
            .border(1.dp, MaoColors.Gold.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (isGroup) {
            Icon(Icons.Rounded.Groups, null, tint = MaoColors.OnGold, modifier = Modifier.size(size * 0.55f))
        } else {
            Text(initials(name), color = MaoColors.OnGold, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp)
        }
    }
}

fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

@Composable
fun MaoLogo(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    Image(painterResource(R.drawable.mao_logo), contentDescription = "Mao logo", modifier = modifier.size(size))
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaoColors.Gold) }
        }
        leading?.invoke()
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .then(if (onTitleClick != null) Modifier.clickable(role = Role.Button, onClick = onTitleClick) else Modifier)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaoColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    leadingIcon: ImageVector? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        leadingIcon = leadingIcon?.let { { Icon(it, null, tint = MaoColors.Gold) } },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaoColors.Gold,
            unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
            focusedContainerColor = Color.White.copy(alpha = 0.05f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
            focusedLabelColor = MaoColors.Gold,
            cursorColor = MaoColors.Gold,
        ),
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaoColors.Gold.copy(alpha = 0.85f),
        letterSpacing = 1.2.sp,
    )
}

@Composable
fun RoleBadge(text: String, color: Color = MaoColors.Gold) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
fun CenteredLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaoColors.Gold)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MaoLogo(size = 96.dp)
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text(body, color = MaoColors.TextSecondary, modifier = Modifier.padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Box(Modifier.padding(top = 20.dp)) { action() } }
    }
}
