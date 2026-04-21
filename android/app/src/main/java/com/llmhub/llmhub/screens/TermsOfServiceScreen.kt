package com.llmhub.llmhub.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.llmhub.llmhub.R
import com.llmhub.llmhub.components.NeuralHubColors
import com.llmhub.llmhub.components.NeuralHubSectionHeading
import com.llmhub.llmhub.components.NeuralHubUiDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsOfServiceScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = NeuralHubUiDefaults.scaffoldContainerColor,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.terms_of_service)) },
                colors = NeuralHubUiDefaults.topAppBarColors(),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_back),
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                TosIntroCard()
            }
            item {
                TosSection(
                    title = R.string.tos_acceptance_title,
                    body = R.string.tos_acceptance_text
                )
            }
            item {
                TosSection(
                    title = R.string.tos_app_description_title,
                    body = R.string.tos_app_description_text
                )
            }
            item {
                TosSection(
                    title = R.string.tos_user_responsibilities_title,
                    body = R.string.tos_user_responsibilities_text
                )
            }
            item {
                TosSection(
                    title = R.string.tos_privacy_data_title,
                    body = R.string.tos_privacy_data_text
                )
            }
            item {
                TosSection(
                    title = R.string.tos_model_usage_title,
                    body = R.string.tos_model_usage_text
                )
            }
            item {
                TosSection(
                    title = R.string.tos_disclaimer_title,
                    body = R.string.tos_disclaimer_text
                )
            }
            item {
                TosSection(
                    title = R.string.tos_limitation_liability_title,
                    body = R.string.tos_limitation_liability_text
                )
            }
            item {
                TosSection(
                    title = R.string.tos_open_source_title,
                    body = R.string.tos_open_source_text
                )
            }
            item {
                TosSection(
                    title = R.string.tos_changes_title,
                    body = R.string.tos_changes_text
                )
            }
            item {
                TosSection(
                    title = R.string.tos_contact_title,
                    body = R.string.tos_contact_text
                )
            }
        }
    }
}

@Composable
private fun TosGlassCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = NeuralHubUiDefaults.glassCardColors(),
        border = NeuralHubUiDefaults.glassCardBorderStroke(),
        elevation = NeuralHubUiDefaults.glassCardElevation()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun TosIntroCard() {
    TosGlassCard {
        Text(
            text = stringResource(R.string.terms_of_service),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = stringResource(R.string.tos_last_updated),
            style = MaterialTheme.typography.bodySmall,
            color = NeuralHubColors.TextDim
        )
        Text(
            text = stringResource(R.string.tos_welcome_text),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Justify,
            color = Color.White.copy(alpha = 0.92f)
        )
    }
}

@Composable
private fun TosSection(title: Int, body: Int) {
    TosGlassCard {
        NeuralHubSectionHeading(text = stringResource(title))
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Justify,
            color = Color.White.copy(alpha = 0.92f)
        )
    }
}
