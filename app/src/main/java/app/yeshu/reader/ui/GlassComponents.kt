package app.yeshu.reader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A lightweight aurora rendered behind the translucent surfaces.  It gives the
 * glass something to refract visually without relying on API 31-only blur APIs.
 */
@Composable
internal fun AuroraBackground(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val top = if (dark) Color(0xFF090D1D) else Color(0xFFF5F7FF)
    val bottom = if (dark) Color(0xFF10162B) else Color(0xFFF1F4FA)
    val blueAlpha = if (dark) 0.22f else 0.18f
    val violetAlpha = if (dark) 0.18f else 0.13f
    val cyanAlpha = if (dark) 0.13f else 0.12f

    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))

        fun glow(center: Offset, radius: Float, color: Color) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0f)),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }

        val span = size.maxDimension
        glow(Offset(size.width * 0.08f, size.height * 0.10f), span * 0.38f, ElectricBlue.copy(alpha = blueAlpha))
        glow(Offset(size.width * 0.94f, size.height * 0.28f), span * 0.36f, ActiveViolet.copy(alpha = violetAlpha))
        glow(Offset(size.width * 0.30f, size.height * 0.92f), span * 0.34f, LuminousCyan.copy(alpha = cyanAlpha))
    }
}

/**
 * Cross-version frosted surface: translucent tint, a directional white rim,
 * soft elevation and an internal specular highlight.  Keeping the treatment in
 * one component makes light and dark mode contrast predictable.
 */
@Composable
internal fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    tint: Color? = null,
    elevation: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = tint ?: if (dark) Color(0xFF172039) else Color.White
    val rimStrong = if (dark) 0.28f else 0.86f
    val rimSoft = if (dark) 0.08f else 0.38f

    val rim = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = rimStrong),
            Color.White.copy(alpha = rimSoft),
            ElectricBlue.copy(alpha = if (dark) 0.20f else 0.08f)
        )
    )
    val panelModifier = modifier
        .clip(shape)
        .border(BorderStroke(1.dp, rim), shape)
        .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))

    Box(panelModifier) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(base.copy(alpha = if (dark) 0.46f else 0.50f))
        }
        Box(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
internal fun GlassPill(
    modifier: Modifier = Modifier,
    color: Color = ElectricBlue,
    content: @Composable BoxScope.() -> Unit
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = if (dark) 0.22f else 0.11f))
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.16f else 0.72f), RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
        content = content
    )
}
