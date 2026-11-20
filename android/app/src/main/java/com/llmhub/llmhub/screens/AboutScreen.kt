package com.llmhub.llmhub.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
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
fun AboutScreen(
    onNavigateBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    
    Scaffold(
        containerColor = NeuralHubUiDefaults.scaffoldContainerColor,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                colors = NeuralHubUiDefaults.topAppBarColors(),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(48.dp)
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
                // App Logo and Title
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        Surface(
                            modifier = Modifier.size(64.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = NeuralHubColors.Glass,
                            tonalElevation = 0.dp,
                            border = BorderStroke(1.dp, NeuralHubColors.GlassBorder)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.clip(MaterialTheme.shapes.medium)
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.ic_brand_logo),
                                    contentDescription = "App Logo",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    Text(
                        text = stringResource(R.string.version_number),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeuralHubColors.TextDim
                    )
                }
            }
            
            item {
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
                        NeuralHubSectionHeading(
                            text = stringResource(R.string.about_llm_hub),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        
                        Text(
                            text = stringResource(R.string.about_description),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Justify,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }
                }
            }
            
            item {
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
                        NeuralHubSectionHeading(
                            text = stringResource(R.string.key_features),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        
                        val features = listOf(
                            stringResource(R.string.feature_multiple_models),
                            stringResource(R.string.feature_on_device),
                            stringResource(R.string.feature_vision_support),
                            stringResource(R.string.feature_gpu_acceleration),
                            stringResource(R.string.feature_offline_usage),
                            stringResource(R.string.feature_privacy_first),
                            stringResource(R.string.feature_modern_ui)
                        )
                        
                        features.forEach { feature ->
                            Text(
                                text = feature,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NeuralHubColors.TextDim,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            
            item {
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
                        NeuralHubSectionHeading(
                            text = stringResource(R.string.tech_stack),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        
                        val technologies = listOf(
                            stringResource(R.string.tech_kotlin_compose),
                            stringResource(R.string.tech_mediapipe_litert),
                            stringResource(R.string.tech_quantization),
                            stringResource(R.string.tech_gpu_acceleration),
                            stringResource(R.string.tech_models_source)
                        )
                        
                        technologies.forEach { tech ->
                            Text(
                                text = tech,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NeuralHubColors.TextDim,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            
            item {
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
                        NeuralHubSectionHeading(
                            text = stringResource(R.string.contact_support),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = "Email",
                                    modifier = Modifier.size(20.dp),
                                    tint = NeuralHubColors.AccentCyan
                                )
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                Text(
                                    text = stringResource(R.string.contact_email),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                            
                            TextButton(
                                onClick = {
                                    uriHandler.openUri("mailto:timmyboy0623@gmail.com")
                                },
                                modifier = Modifier.padding(start = 28.dp)
                            ) {
                                Text(
                                    text = "timmyboy0623@gmail.com",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NeuralHubColors.AccentCyan
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Code,
                                    contentDescription = "Source Code",
                                    modifier = Modifier.size(20.dp),
                                    tint = NeuralHubColors.AccentCyan
                                )
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                Text(
                                    text = stringResource(R.string.source_code),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                            
                            TextButton(
                                onClick = {
                                    uriHandler.openUri("https://github.com/timmyy123/LLM-Hub")
                                },
                                modifier = Modifier.padding(start = 28.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.github_repository),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NeuralHubColors.AccentCyan
                                )
                            }
                        }
                    }
                }
            }
            
            item {
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
                        NeuralHubSectionHeading(
                            text = stringResource(R.string.acknowledgments),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        
                        Text(
                            text = stringResource(R.string.acknowledgments_text),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Justify,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = stringResource(R.string.sd_models_credit),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Justify,
                            color = NeuralHubColors.TextDim
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = stringResource(R.string.nexa_sdk_credit),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Justify,
                            color = NeuralHubColors.TextDim
                        )
                    }
                }
            }
        }
    }
}
