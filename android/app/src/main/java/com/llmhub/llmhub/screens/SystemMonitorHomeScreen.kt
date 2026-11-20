package com.llmhub.llmhub.screens

import android.app.Application
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llmhub.llmhub.LlmHubApplication
import com.llmhub.llmhub.R
import com.llmhub.llmhub.components.NeuralHubColors
import com.llmhub.llmhub.inference.InferenceService
import com.llmhub.llmhub.viewmodels.ModelDownloadViewModel
import com.llmhub.llmhub.viewmodels.ThemeViewModel
import com.llmhub.llmhub.data.LLMModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemMonitorHomeScreen(
    themeViewModel: ThemeViewModel,
    onNavigateToChatHistory: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToLocalApi: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val app = context.applicationContext as LlmHubApplication
    val modelDownloadViewModel: ModelDownloadViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
    )
    val models by modelDownloadViewModel.models.collectAsState()
    val downloadedLlmModels = remember(models) {
        models.filter { m ->
            m.isDownloaded && (m.category == "text" || m.category == "multimodal")
        }
    }

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
            HeaderSection(
                onNavigateToChatHistory = onNavigateToChatHistory,
                onNavigateToModels = onNavigateToModels,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToAbout = onNavigateToAbout
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                item {
                    ActiveModelPlate(
                        isVisible = isVisible,
                        themeViewModel = themeViewModel,
                        onNavigateToLocalApi = onNavigateToLocalApi,
                        downloadedLlmModels = downloadedLlmModels,
                        inference = app.inferenceService
                    )
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
}

@Composable
private fun HeaderSection(
    onNavigateToChatHistory: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(start = 20.dp, end = 12.dp, top = 40.dp, bottom = 14.dp)
            .drawBehind {
                drawLine(
                    color = NeuralHubColors.GlassBorder,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = NeuralHubColors.AccentCyan.copy(alpha = 0.1f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_node_badge),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = NeuralHubColors.AccentCyan,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateToChatHistory) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = stringResource(R.string.drawer_recent_chats),
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
                IconButton(onClick = onNavigateToModels) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource(R.string.download_models),
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
                IconButton(onClick = onNavigateToAbout) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.about_llm_hub),
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveModelPlate(
    isVisible: Boolean,
    themeViewModel: ThemeViewModel,
    onNavigateToLocalApi: () -> Unit,
    downloadedLlmModels: List<LLMModel>,
    inference: InferenceService
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { 50 },
            animationSpec = tween(600, easing = LinearOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(600))
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "shine")
        val shineOffset by infiniteTransition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shine_offset"
        )

        val plateContext = LocalContext.current
        val defaultName by themeViewModel.localApiDefaultModelName.collectAsState()
        val isServerRunning by themeViewModel.isApiServerRunning.collectAsState()
        val apiBusy by themeViewModel.apiServerBusy.collectAsState()
        val lanReasoningEnabled by themeViewModel.localApiReasoningEnabled.collectAsState()
        var busyStart by remember { mutableStateOf(false) }
        var busyStop by remember { mutableStateOf(false) }
        var loadedTick by remember { mutableIntStateOf(0) }
        val loadedModel = remember(loadedTick) { inference.getCurrentlyLoadedModel() }
        val engineFullyRunning = loadedModel != null && isServerRunning

        LaunchedEffect(loadedTick) {
            themeViewModel.syncLocalApiServerState()
        }

        val engineToggleEnabled = !busyStart && !busyStop && !apiBusy &&
            (loadedModel != null || (!defaultName.isNullOrBlank() && !engineFullyRunning))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(NeuralHubColors.GlassHighlight, NeuralHubColors.Glass),
                            start = Offset(0f, 0f),
                            end = Offset(100f, 100f)
                        )
                    )
                    .border(1.dp, NeuralHubColors.GlassBorder, RoundedCornerShape(12.dp))
                    .drawBehind {
                        val brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0x0DFFFFFF),
                                Color.Transparent
                            ),
                            startX = size.width * shineOffset,
                            endX = size.width * (shineOffset + 0.5f)
                        )
                        drawRect(brush = brush)
                    }
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onNavigateToLocalApi)
                    ) {
                        Text(
                            text = stringResource(R.string.home_engine_section_label),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = NeuralHubColors.TextDim,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        Text(
                            text = defaultName?.trim().takeUnless { it.isNullOrEmpty() }
                                ?: stringResource(R.string.local_api_default_model_none),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = NeuralHubColors.AccentGreen.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, NeuralHubColors.AccentGreen.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(NeuralHubColors.AccentGreen, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))

                                    val serverIp = themeViewModel.getApiServerIp()

                                    Text(
                                        text = if (isServerRunning) {
                                            stringResource(R.string.home_api_live, serverIp.orEmpty())
                                        } else {
                                            stringResource(R.string.home_status_ready)
                                        },
                                        fontSize = 11.sp,
                                        color = NeuralHubColors.AccentGreen,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            Surface(
                                color = NeuralHubColors.AccentCyan.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, NeuralHubColors.AccentCyan.copy(alpha = 0.28f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.home_lan_api_reasoning),
                                        fontSize = 10.sp,
                                        color = NeuralHubColors.AccentCyan,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Switch(
                                        checked = lanReasoningEnabled,
                                        onCheckedChange = { themeViewModel.setLocalApiReasoningEnabled(it) },
                                        modifier = Modifier.height(32.dp),
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NeuralHubColors.AccentCyan,
                                            checkedTrackColor = NeuralHubColors.AccentCyan.copy(alpha = 0.35f),
                                            uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                                            uncheckedTrackColor = Color.White.copy(alpha = 0.2f)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    FilledIconButton(
                        onClick = {
                            if (loadedModel != null) {
                                if (busyStart || busyStop) return@FilledIconButton
                                busyStop = true
                                themeViewModel.unloadInferenceModel {
                                    busyStop = false
                                    loadedTick++
                                }
                            } else {
                                if (busyStart || busyStop || engineFullyRunning) return@FilledIconButton
                                busyStart = true
                                themeViewModel.loadLocalApiDefaultModel(downloadedLlmModels) { err ->
                                    busyStart = false
                                    loadedTick++
                                    if (err != null) {
                                        Toast.makeText(plateContext, err, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = engineToggleEnabled,
                        modifier = Modifier.size(52.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (loadedModel != null) {
                                NeuralHubColors.AccentGreen.copy(alpha = 0.22f)
                            } else {
                                NeuralHubColors.AccentCyan.copy(alpha = 0.22f)
                            },
                            contentColor = Color.White,
                            disabledContainerColor = Color.White.copy(alpha = 0.08f),
                            disabledContentColor = Color.White.copy(alpha = 0.35f)
                        )
                    ) {
                        when {
                            busyStart || busyStop -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(26.dp),
                                    strokeWidth = 2.5.dp,
                                    color = Color.White
                                )
                            }
                            loadedModel != null -> {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = stringResource(R.string.home_primary_engine_stop),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.home_primary_engine_start),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

