package com.llmhub.llmhub.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared scaffold / app bar styling for screens shown inside [NeuralHubShell]
 * (transparent body so radial gradients show through; glassy bar on scroll).
 */
@OptIn(ExperimentalMaterial3Api::class)
object NeuralHubUiDefaults {
    val scaffoldContainerColor: Color = Color.Transparent

    /** Drawer / sheet backgrounds: slightly lifted from [NeuralHubColors.Bg] for legibility. */
    val panelContainerColor: Color = Color(0xFF08080C)

    val drawerContainerColor: Color = NeuralHubColors.Bg

    val modalBottomSheetContainerColor: Color = panelContainerColor

    /** Matches [NeuralHubShell] bottom nav scrim; chat input bar and similar chrome. */
    val bottomChromeScrimColor: Color = Color(0xCC0A0A0C)

    val glassListItemShape = RoundedCornerShape(12.dp)

    @Composable
    fun topAppBarColors(): TopAppBarColors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = NeuralHubColors.GlassHighlight.copy(alpha = 0.45f),
            navigationIconContentColor = Color.White,
            titleContentColor = Color.White,
            actionIconContentColor = NeuralHubColors.AccentCyan
        )

    @Composable
    fun glassCardColors(contentColor: Color = Color.White): CardColors =
        CardDefaults.cardColors(
            containerColor = NeuralHubColors.Glass,
            contentColor = contentColor
        )

    @Composable
    fun glassCardElevation(): CardElevation =
        CardDefaults.cardElevation(defaultElevation = 0.dp)

    fun glassCardBorderStroke(): BorderStroke =
        BorderStroke(1.dp, NeuralHubColors.GlassBorder)
}
