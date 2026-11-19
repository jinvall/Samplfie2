package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SamplfieDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = ObsidianChassis,
    primaryContainer = NeonCyanDim,
    onPrimaryContainer = TextPrimary,
    secondary = AmberHot,
    onSecondary = ObsidianChassis,
    secondaryContainer = ElevatedBezel,
    onSecondaryContainer = AmberHot,
    tertiary = VioletMod,
    onTertiary = ObsidianChassis,
    tertiaryContainer = ElevatedBezel,
    onTertiaryContainer = VioletMod,
    error = TacCoral,
    onError = TextPrimary,
    background = ObsidianChassis,
    onBackground = TextPrimary,
    surface = GunmetalDeck,
    onSurface = TextPrimary,
    surfaceVariant = BrushedPanel,
    onSurfaceVariant = TextSecondary,
    outline = HairlineDivider,
    outlineVariant = ElevatedBezel
)

val SamplfieShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun Samplfie2Theme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SamplfieDarkColorScheme,
        typography = SamplfieTypography,
        shapes = SamplfieShapes,
        content = content
    )
}
