package com.llmhub.llmhub.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Section labels matching [SystemMonitorHomeScreen] (monospace, uppercase, dim).
 */
@Composable
fun NeuralHubSectionHeading(
    text: String,
    modifier: Modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = NeuralHubColors.TextDim,
        letterSpacing = 1.sp,
        modifier = modifier
    )
}
