package dev.lanbridge

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Keep the reference design's neutral surfaces independent of wallpaper colors.
internal fun bridgeColorScheme(dark: Boolean) = if(dark) darkColorScheme(
    primary = Color(0xFFD9E0F0), onPrimary = Color(0xFF202737),
    primaryContainer = Color(0xFF394254), onPrimaryContainer = Color(0xFFF0F3FA),
    secondaryContainer = Color(0xFF394254), onSecondaryContainer = Color(0xFFF0F3FA),
    background = Color(0xFF121315), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF121315), onSurface = Color(0xFFF2F2F2),
    surfaceContainer = Color(0xFF202124), surfaceContainerLow = Color(0xFF202124),
    surfaceContainerHigh = Color(0xFF2A2B2F), onSurfaceVariant = Color(0xFFB3B4B8),
    outline = Color(0xFF707176), outlineVariant = Color(0xFF3D3E43)
) else lightColorScheme(
    primary = Color(0xFF46536C), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9DEEA), onPrimaryContainer = Color(0xFF17191D),
    secondaryContainer = Color(0xFFD9DEEA), onSecondaryContainer = Color(0xFF17191D),
    background = Color(0xFFFCFCFC), onBackground = Color(0xFF151515),
    surface = Color(0xFFFCFCFC), onSurface = Color(0xFF151515),
    surfaceContainer = Color(0xFFF3F3F3), surfaceContainerLow = Color(0xFFF3F3F3),
    surfaceContainerHigh = Color(0xFFECECEC), onSurfaceVariant = Color(0xFF676767),
    outline = Color(0xFF939393), outlineVariant = Color(0xFFDADADA)
)
