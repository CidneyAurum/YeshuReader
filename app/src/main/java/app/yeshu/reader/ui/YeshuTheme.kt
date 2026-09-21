package app.yeshu.reader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 单一强调色系统：与 legacy 视图共用同一组色值（Accent.kt）
val ElectricBlue = Color(app.yeshu.reader.Accent.primary)
val LuminousCyan = Color(app.yeshu.reader.Accent.cyan)
val ActiveViolet = Color(app.yeshu.reader.Accent.violet)
val InkNavy = Color(0xFF0B1020)

private val LightColors = lightColorScheme(
    primary = ElectricBlue,
    secondary = ActiveViolet,
    tertiary = LuminousCyan,
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFEEF0FA),
    onPrimary = Color.White,
    onBackground = Color(0xFF171A2B),
    onSurface = Color(0xFF171A2B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8C91FF),
    secondary = Color(0xFFC77DFF),
    tertiary = LuminousCyan,
    background = InkNavy,
    surface = Color(0xFF141A2C),
    surfaceVariant = Color(0xFF202743),
    onPrimary = Color(0xFF080A18),
    onBackground = Color(0xFFF4F5FF),
    onSurface = Color(0xFFF4F5FF)
)

@Composable
fun YeshuTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
