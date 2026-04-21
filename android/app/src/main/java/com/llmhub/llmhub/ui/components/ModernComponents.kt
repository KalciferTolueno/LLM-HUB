package com.llmhub.llmhub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmhub.llmhub.components.NeuralHubColors
import com.llmhub.llmhub.components.NeuralHubUiDefaults
import java.util.Locale

@Composable
fun ModernCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            colors = NeuralHubUiDefaults.glassCardColors(),
            border = NeuralHubUiDefaults.glassCardBorderStroke(),
            elevation = NeuralHubUiDefaults.glassCardElevation(),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                content = content
            )
        }
    } else {
        Card(
            modifier = modifier,
            colors = NeuralHubUiDefaults.glassCardColors(),
            border = NeuralHubUiDefaults.glassCardBorderStroke(),
            elevation = NeuralHubUiDefaults.glassCardElevation(),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                content = content
            )
        }
    }
}

@Composable
fun IconWithLabel(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Column(
        modifier = modifier.padding(vertical = 16.dp)
    ) {
        Text(
            text = title.uppercase(Locale.getDefault()),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            color = NeuralHubColors.TextDim,
            fontWeight = FontWeight.Bold
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NeuralHubColors.TextDim
            )
        }
    }
}

@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = contentColor
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ModernProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    showPercentage: Boolean = true
) {
    Column(modifier = modifier) {
        if (showPercentage) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Progress",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
