package com.droidlink.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DroidLinkColors = darkColorScheme(
    primary = NeonGreen,
    onPrimary = NearBlack,
    secondary = NeonGreen,
    background = NearBlack,
    onBackground = SoftWhite,
    surface = PanelBlack,
    onSurface = SoftWhite,
    outline = MutedGray
)

@Composable
fun DroidLinkTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DroidLinkColors,
        typography = Typography,
        content = content
    )
}
