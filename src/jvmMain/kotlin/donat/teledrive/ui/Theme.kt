package donat.teledrive.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** SYSTEM follows Windows (Daylight / Graphite); the other ten are fixed looks. Chart colors validated on every surface. */
enum class AppTheme(val label: String, val description: String) {
    SYSTEM("System", "Follows Windows: Daylight or Graphite"),
    DAYLIGHT("Daylight", "Clean white with blue accents"),
    GRAPHITE("Graphite", "Neutral dark for long sessions"),
    OCEAN("Ocean", "Deep navy with cyan accents"),
    AMOLED("AMOLED", "True black, violet accent"),
    SEPIA("Sepia", "Warm paper, easy on the eyes"),
    FOREST("Forest", "Deep green with fresh mint"),
    SAKURA("Sakura", "Soft blossom pink, rose accent"),
    SUNSET("Sunset", "Dark plum with glowing coral"),
    LAVENDER("Lavender", "Airy lilac with royal purple"),
    NEON("Neon", "Midnight with electric lime"),
}

@Composable
fun colorsFor(theme: AppTheme): ColorScheme = when (theme) {
    AppTheme.SYSTEM -> colorsFor(if (isSystemInDarkTheme()) AppTheme.GRAPHITE else AppTheme.DAYLIGHT)
    AppTheme.DAYLIGHT -> scheme(false,
        primary = 0x1F6FEB, onPrimary = 0xFFFFFF, primaryContainer = 0xD6E4FF, onPrimaryContainer = 0x0B2A66,
        secondaryContainer = 0xDCE5F5, onSecondaryContainer = 0x1B2433,
        surface = 0xF7F8FA, onSurface = 0x1A1C1E, surfaceVariant = 0xE6E9EF, onSurfaceVariant = 0x5A6170,
        containers = listOf(0xFFFFFF, 0xF2F4F7, 0xECEFF3, 0xE7EAEF, 0xE1E5EB), outline = 0xA3AAB8, outlineVariant = 0xD5DAE2,
    )
    AppTheme.GRAPHITE -> scheme(true,
        primary = 0x8AB4F8, onPrimary = 0x0B2A55, primaryContainer = 0x1E3A66, onPrimaryContainer = 0xD6E4FF,
        secondaryContainer = 0x2E3440, onSecondaryContainer = 0xE2E6EE,
        surface = 0x121417, onSurface = 0xE3E5E8, surfaceVariant = 0x2A2E35, onSurfaceVariant = 0xA9B0BC,
        containers = listOf(0x0D0F11, 0x181B1F, 0x1C1F24, 0x23272D, 0x2A2E35), outline = 0x5E6673, outlineVariant = 0x353A42,
    )
    AppTheme.OCEAN -> scheme(true,
        primary = 0x4FC3F7, onPrimary = 0x00344A, primaryContainer = 0x0B4A6B, onPrimaryContainer = 0xC9EBFF,
        secondaryContainer = 0x16324A, onSecondaryContainer = 0xD4E8F7,
        surface = 0x0B1724, onSurface = 0xE1ECF5, surfaceVariant = 0x1C3247, onSurfaceVariant = 0x9FB6C9,
        containers = listOf(0x07111B, 0x0F1E2D, 0x132436, 0x182B3F, 0x1E3349), outline = 0x4E6A82, outlineVariant = 0x22384D,
    )
    AppTheme.AMOLED -> scheme(true,
        primary = 0xBB86FC, onPrimary = 0x2B0B4F, primaryContainer = 0x3A1E5F, onPrimaryContainer = 0xEBDDFF,
        secondaryContainer = 0x1E1E1E, onSecondaryContainer = 0xE6E1E5,
        surface = 0x000000, onSurface = 0xECECEC, surfaceVariant = 0x1A1A1A, onSurfaceVariant = 0xA8A8A8,
        containers = listOf(0x000000, 0x0A0A0A, 0x0F0F0F, 0x141414, 0x1A1A1A), outline = 0x4A4A4A, outlineVariant = 0x262626,
    )
    AppTheme.SEPIA -> scheme(false,
        primary = 0x8B5A2B, onPrimary = 0xFFFFFF, primaryContainer = 0xF1DCC2, onPrimaryContainer = 0x3A2410,
        secondaryContainer = 0xE6D7BD, onSecondaryContainer = 0x3B2F20,
        surface = 0xF6EEDF, onSurface = 0x3B3024, surfaceVariant = 0xE9DDC8, onSurfaceVariant = 0x6E5F4C,
        containers = listOf(0xFBF6EC, 0xF2E8D6, 0xEEE3CF, 0xE9DDC7, 0xE3D6BF), outline = 0xB4A285, outlineVariant = 0xDCCDB3,
    )
    AppTheme.FOREST -> scheme(true,
        primary = 0x7BD88F, onPrimary = 0x0B3818, primaryContainer = 0x1F5130, onPrimaryContainer = 0xC8F5D2,
        secondaryContainer = 0x24392C, onSecondaryContainer = 0xD5E8DA,
        surface = 0x0F1A14, onSurface = 0xE0EDE4, surfaceVariant = 0x22332A, onSurfaceVariant = 0xA3B8AA,
        containers = listOf(0x0A130E, 0x132019, 0x17261D, 0x1C2D23, 0x22352A), outline = 0x557062, outlineVariant = 0x2A3D32,
    )
    AppTheme.SAKURA -> scheme(false,
        primary = 0xC2185B, onPrimary = 0xFFFFFF, primaryContainer = 0xFFD9E4, onPrimaryContainer = 0x3E0019,
        secondaryContainer = 0xF6DDE5, onSecondaryContainer = 0x3A1F28,
        surface = 0xFFF7F9, onSurface = 0x2B1B20, surfaceVariant = 0xF3E1E6, onSurfaceVariant = 0x6E5860,
        containers = listOf(0xFFFFFF, 0xFCEFF2, 0xF8E8EC, 0xF5E1E7, 0xF0D9E0), outline = 0xB8A0A8, outlineVariant = 0xE6CED6,
    )
    AppTheme.SUNSET -> scheme(true,
        primary = 0xFF8A65, onPrimary = 0x4A1500, primaryContainer = 0x6B2A14, onPrimaryContainer = 0xFFDBCF,
        secondaryContainer = 0x3D2537, onSecondaryContainer = 0xF2DCEB,
        surface = 0x1A1018, onSurface = 0xF1E3EC, surfaceVariant = 0x33222E, onSurfaceVariant = 0xC2A9B8,
        containers = listOf(0x130B11, 0x21151E, 0x261923, 0x2D1F2A, 0x352531), outline = 0x7A6070, outlineVariant = 0x3F2D39,
    )
    AppTheme.LAVENDER -> scheme(false,
        primary = 0x6D4BD8, onPrimary = 0xFFFFFF, primaryContainer = 0xE6DEFF, onPrimaryContainer = 0x22005D,
        secondaryContainer = 0xE8E1F7, onSecondaryContainer = 0x2A2340,
        surface = 0xFAF8FF, onSurface = 0x1C1A22, surfaceVariant = 0xE7E2F2, onSurfaceVariant = 0x5F5A6E,
        containers = listOf(0xFFFFFF, 0xF4F1FB, 0xEFEBF7, 0xE9E5F3, 0xE3DEEE), outline = 0xA49DB5, outlineVariant = 0xD6D0E3,
    )
    AppTheme.NEON -> scheme(true,
        primary = 0xD4FF3A, onPrimary = 0x1A2200, primaryContainer = 0x3A4A00, onPrimaryContainer = 0xEEFFB0,
        secondaryContainer = 0x2B1640, onSecondaryContainer = 0xF0D9FF,
        surface = 0x0B0B12, onSurface = 0xECECF5, surfaceVariant = 0x1C1C2A, onSurfaceVariant = 0xA6A6C0,
        containers = listOf(0x06060B, 0x101019, 0x14141F, 0x191926, 0x1F1F2E), outline = 0x4E4E6E, outlineVariant = 0x26263A,
    )
}

private fun c(rgb: Int) = Color(0xFF000000.toInt() or rgb)

/** containers = lowest, low, default, high, highest (cards use the last one). Unlisted roles keep the M3 light/dark defaults. */
private fun scheme(
    dark: Boolean, primary: Int, onPrimary: Int, primaryContainer: Int, onPrimaryContainer: Int, secondaryContainer: Int, onSecondaryContainer: Int,
    surface: Int, onSurface: Int, surfaceVariant: Int, onSurfaceVariant: Int, containers: List<Int>, outline: Int, outlineVariant: Int,
) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = c(primary), onPrimary = c(onPrimary), primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
    secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
    background = c(surface), surface = c(surface), onBackground = c(onSurface), onSurface = c(onSurface),
    surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
    surfaceContainerLowest = c(containers[0]), surfaceContainerLow = c(containers[1]), surfaceContainer = c(containers[2]),
    surfaceContainerHigh = c(containers[3]), surfaceContainerHighest = c(containers[4]), outline = c(outline), outlineVariant = c(outlineVariant),
)

/** Android 16-style (Material 3 Expressive) rounder corners for every card, chip, menu and dialog. */
val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

/** Dark or light *as rendered* (theme-aware, unlike isSystemInDarkTheme). */
@Composable
fun isDarkUi(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
