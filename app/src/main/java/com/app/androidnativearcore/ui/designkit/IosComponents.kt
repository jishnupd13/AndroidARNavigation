package com.app.androidnativearcore.ui.designkit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Big iOS-style filled button: ~54dp tall, fully rounded, optional leading glyph, short label. */
@Composable
fun IosPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val bg = if (enabled) color else color.copy(alpha = 0.4f)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Text(glyph, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.size(10.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
    }
}

/** Tonal (secondary) iOS button: light fill, primary-colored label. */
@Composable
fun IosTonalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Text(glyph, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** Large round icon button with an optional short caption beneath. */
@Composable
fun IosRoundIconButton(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    size: androidx.compose.ui.unit.Dp = 60.dp,
    container: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(container).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, style = MaterialTheme.typography.titleLarge, color = tint)
        }
        if (caption != null) {
            Spacer(Modifier.size(6.dp))
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Grouped-list section: a title in caps-ish secondary text above a rounded card holding [content]. */
@Composable
fun IosSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp, top = 8.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) { Column { content() } }
    }
}

/** A single iOS grouped-list row: leading glyph, title + optional subtitle, optional trailing text/chevron. */
@Composable
fun IosRow(
    title: String,
    onClick: (() -> Unit)? = null,
    glyph: String? = null,
    subtitle: String? = null,
    trailing: String? = null,
    showChevron: Boolean = true,
) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(
        clickable.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (glyph != null) Text(glyph, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null && showChevron) {
            Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Thin inset divider between rows (iOS grouped-list style). */
@Composable
fun IosRowDivider() {
    Box(
        Modifier.fillMaxWidth().padding(start = 16.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
            .heightIn(min = 0.5.dp),
    )
}

/** A bordered round icon button variant for camera HUDs (translucent, on dark backgrounds). */
@Composable
fun IosHudRoundButton(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp,
) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = Color.White)
    }
}

@Suppress("unused")
private val hairline = BorderStroke(0.5.dp, Color.Gray)
