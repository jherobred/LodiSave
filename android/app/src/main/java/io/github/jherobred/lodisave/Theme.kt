package io.github.jherobred.lodisave

import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** The Kape palette from the design kit. */
data class Tokens(
    val ground: Color, val surface: Color, val soft: Color, val line: Color, val ink: Color, val muted: Color,
    val accent: Color, val accentInk: Color, val pick: Color, val ok: Color, val bad: Color,
)

val Dark = Tokens(
    ground = Color(0xFF0F0F10), surface = Color(0xFF18181A), soft = Color(0xFF202023), line = Color(0xFF2C2C2F),
    ink = Color(0xFFF4F3F1), muted = Color(0xFFA3A19C), accent = Color(0xFFFF8A3D), accentInk = Color(0xFF0F0F10),
    pick = Color(0xFFE8B37A), ok = Color(0xFF3DD68C), bad = Color(0xFFFF6B5E),
)
val Light = Tokens(
    ground = Color(0xFFF6F5F3), surface = Color(0xFFFFFFFF), soft = Color(0xFFEFEEEB), line = Color(0xFFE3E1DD),
    ink = Color(0xFF151413), muted = Color(0xFF6B6964), accent = Color(0xFFB84C0C), accentInk = Color(0xFFFFFFFF),
    pick = Color(0xFFC98E52), ok = Color(0xFF1A7F4B), bad = Color(0xFFC2362B),
)

val LocalTokens = staticCompositionLocalOf { Dark }

/** Whether things move: the Motion setting, off too while a game is in front (gaming mode). */
val LocalMotion = compositionLocalOf { true }

val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)
val GeistMono = FontFamily(Font(R.font.geist_mono, FontWeight.Medium))

/** Springs from the design kit: Float k200 c15, Drift k140 c17, Snap k900 c56 (damping ratio = c / 2√k). */
fun <T> floaty() = spring<T>(dampingRatio = 0.53f, stiffness = 200f)
fun <T> drift() = spring<T>(dampingRatio = 0.72f, stiffness = 140f)
fun <T> snap() = spring<T>(dampingRatio = 0.93f, stiffness = 900f)

@Composable
fun LodiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val t = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(primary = t.accent, onPrimary = t.accentInk, surface = t.surface, onSurface = t.ink, background = t.ground, onBackground = t.ink)
    else lightColorScheme(primary = t.accent, onPrimary = t.accentInk, surface = t.surface, onSurface = t.ink, background = t.ground, onBackground = t.ink)
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography.run {
        copy(
            bodyLarge = bodyLarge.copy(fontFamily = Geist), bodyMedium = bodyMedium.copy(fontFamily = Geist),
            labelLarge = labelLarge.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold),
            titleLarge = titleLarge.copy(fontFamily = Geist, fontWeight = FontWeight.Bold),
        )
    }) {
        CompositionLocalProvider(LocalTokens provides t, androidx.compose.material3.LocalTextStyle provides TextStyle(fontFamily = Geist, color = t.ink)) {
            content()
        }
    }
}
