package com.llmhub.llmhub.screens

import android.app.Application
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.llmhub.llmhub.LlmHubApplication
import com.llmhub.llmhub.R
import com.llmhub.llmhub.components.NeuralHubColors
import com.llmhub.llmhub.components.NeuralHubSectionHeading
import com.llmhub.llmhub.components.NeuralHubUiDefaults
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.viewmodels.ModelDownloadViewModel
import com.llmhub.llmhub.viewmodels.ThemeViewModel
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalApiScreen(
    navController: NavController,
    themeViewModel: ThemeViewModel
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val app = application as LlmHubApplication
    val inference = app.inferenceService

    val modelDownloadViewModel: ModelDownloadViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )

    val isServerRunning by themeViewModel.isApiServerRunning.collectAsState()
    val apiServerBusy by themeViewModel.apiServerBusy.collectAsState()
    val serverIp = themeViewModel.getApiServerIp()
    val apiError by themeViewModel.apiServerError.collectAsState()

    val models by modelDownloadViewModel.models.collectAsState()
    val downloadedLlmModels = remember(models) {
        models.filter { m ->
            m.isDownloaded && (m.category == "text" || m.category == "multimodal")
        }
    }

    var loadingModelName by remember { mutableStateOf<String?>(null) }
    var loadedRefresh by remember { mutableIntStateOf(0) }
    val loadedModel = remember(loadedRefresh) { inference.getCurrentlyLoadedModel() }

    var loadDialogModel by remember { mutableStateOf<LLMModel?>(null) }
    var loadDialogBackend by remember { mutableStateOf(LlmInference.Backend.GPU) }
    var showDefaultModelMenu by remember { mutableStateOf(false) }
    val localApiDefaultName by themeViewModel.localApiDefaultModelName.collectAsState()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedBorderColor = NeuralHubColors.AccentCyan,
        unfocusedBorderColor = NeuralHubColors.GlassBorder,
        cursorColor = NeuralHubColors.AccentCyan,
        focusedLabelColor = NeuralHubColors.TextDim,
        unfocusedLabelColor = NeuralHubColors.TextDim,
        focusedTrailingIconColor = NeuralHubColors.AccentCyan,
        unfocusedTrailingIconColor = NeuralHubColors.TextDim
    )

    LaunchedEffect(apiError) {
        val msg = apiError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        themeViewModel.clearApiServerError()
    }

    Scaffold(
        containerColor = NeuralHubUiDefaults.scaffoldContainerColor,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.local_api_title),
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Color.White
                        )
                    }
                },
                colors = NeuralHubUiDefaults.topAppBarColors()
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = NeuralHubUiDefaults.glassCardColors(),
                    border = NeuralHubUiDefaults.glassCardBorderStroke(),
                    elevation = NeuralHubUiDefaults.glassCardElevation()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isServerRunning) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (isServerRunning) NeuralHubColors.AccentGreen else NeuralHubColors.TextDim,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.local_api_card_service_title),
                                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                                    color = NeuralHubColors.TextDim,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (isServerRunning) {
                                        stringResource(R.string.local_api_server_running)
                                    } else {
                                        stringResource(R.string.local_api_server_stopped)
                                    },
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isServerRunning) NeuralHubColors.AccentGreen else Color.White
                                )
                            }
                        }
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            LocalApiPulseFrame(
                                pulse = isServerRunning,
                                accent = if (isServerRunning) NeuralHubColors.AccentGreen else NeuralHubColors.AccentCyan,
                                modifier = Modifier
                            ) {
                                FilledIconButton(
                                    onClick = { themeViewModel.toggleApiServer() },
                                    enabled = !apiServerBusy,
                                    modifier = Modifier.size(56.dp),
                                    shape = CircleShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = if (isServerRunning) {
                                            NeuralHubColors.AccentGreen.copy(alpha = 0.22f)
                                        } else {
                                            NeuralHubColors.AccentCyan.copy(alpha = 0.22f)
                                        },
                                        contentColor = Color.White,
                                        disabledContainerColor = Color.White.copy(alpha = 0.08f),
                                        disabledContentColor = Color.White.copy(alpha = 0.38f)
                                    )
                                ) {
                                    if (apiServerBusy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 2.5.dp,
                                            color = Color.White
                                        )
                                    } else if (isServerRunning) {
                                        Icon(
                                            imageVector = Icons.Filled.Stop,
                                            contentDescription = stringResource(R.string.local_api_stop),
                                            modifier = Modifier.size(30.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = stringResource(R.string.local_api_start),
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isServerRunning && serverIp != null) {
                item {
                    NeuralHubSectionHeading(text = stringResource(R.string.local_api_card_endpoints_title))
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
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.local_api_web_chat, serverIp),
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.local_api_endpoint, serverIp),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = NeuralHubColors.TextDim,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.local_api_hint_wifi),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = NeuralHubColors.TextDim
                )
            }

            item {
                ExposedDropdownMenuBox(
                    expanded = showDefaultModelMenu,
                    onExpandedChange = { showDefaultModelMenu = !showDefaultModelMenu }
                ) {
                    OutlinedTextField(
                        value = localApiDefaultName ?: stringResource(R.string.local_api_default_model_none),
                        onValueChange = {},
                        readOnly = true,
                        label = {
                            Text(stringResource(R.string.local_api_default_model_label))
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showDefaultModelMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = fieldColors,
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = showDefaultModelMenu,
                        onDismissRequest = { showDefaultModelMenu = false },
                        containerColor = NeuralHubUiDefaults.panelContainerColor
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.local_api_default_model_none), color = Color.White) },
                            onClick = {
                                themeViewModel.setLocalApiDefaultModelName(null)
                                showDefaultModelMenu = false
                            }
                        )
                        downloadedLlmModels.forEach { m ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(m.name, color = Color.White) },
                                onClick = {
                                    themeViewModel.setLocalApiDefaultModelName(m.name)
                                    showDefaultModelMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.local_api_default_model_hint),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = NeuralHubColors.TextDim
                )
            }

            item {
                loadedModel?.let { m ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = NeuralHubUiDefaults.glassCardColors(),
                        border = BorderStroke(1.dp, NeuralHubColors.AccentGreen.copy(alpha = 0.45f)),
                        elevation = NeuralHubUiDefaults.glassCardElevation()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = NeuralHubColors.AccentGreen,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.local_api_loaded),
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuralHubColors.AccentGreen
                                )
                                Text(
                                    text = m.name,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            item {
                NeuralHubSectionHeading(text = stringResource(R.string.local_api_card_models_title))
            }

            if (downloadedLlmModels.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.local_api_no_llm_models),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            } else {
                items(downloadedLlmModels, key = { it.name + it.modelFormat }) { model ->
                    val isBusy = loadingModelName == model.name
                    val isLoaded = loadedModel?.name == model.name
                    LocalApiModelCard(
                        model = model,
                        isLoaded = isLoaded,
                        isBusy = isBusy,
                        onRequestLoad = {
                            loadDialogModel = model
                            val gpuOk = model.supportsGpu &&
                                !(model.modelFormat == "litertlm" &&
                                    model.name.contains("Gemma-4", ignoreCase = true))
                            loadDialogBackend =
                                if (gpuOk) LlmInference.Backend.GPU else LlmInference.Backend.CPU
                        }
                    )
                }
            }
        }
    }

    loadDialogModel?.let { dialogModel ->
        AlertDialog(
            onDismissRequest = { loadDialogModel = null },
            containerColor = NeuralHubUiDefaults.panelContainerColor,
            title = {
                Text(
                    stringResource(R.string.select_backend),
                    color = Color.White
                )
            },
            text = {
                val gpuOptionAvailable = dialogModel.supportsGpu &&
                    !(dialogModel.modelFormat == "litertlm" &&
                        dialogModel.name.contains("Gemma-4", ignoreCase = true))
                LaunchedEffect(dialogModel.name) {
                    if (!gpuOptionAvailable) {
                        loadDialogBackend = LlmInference.Backend.CPU
                    }
                }
                Column(
                    modifier = Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!gpuOptionAvailable) {
                        Text(
                            text = stringResource(R.string.local_api_gpu_not_supported),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = NeuralHubColors.TextDim
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = loadDialogBackend == LlmInference.Backend.CPU,
                                onClick = { loadDialogBackend = LlmInference.Backend.CPU },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = loadDialogBackend == LlmInference.Backend.CPU,
                            onClick = { loadDialogBackend = LlmInference.Backend.CPU }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.backend_cpu), color = Color.White)
                    }
                    if (gpuOptionAvailable) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = loadDialogBackend == LlmInference.Backend.GPU,
                                    onClick = { loadDialogBackend = LlmInference.Backend.GPU },
                                    role = Role.RadioButton
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = loadDialogBackend == LlmInference.Backend.GPU,
                                onClick = { loadDialogBackend = LlmInference.Backend.GPU }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.backend_gpu), color = Color.White)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val modelToLoad = loadDialogModel
                        val backend = loadDialogBackend
                        loadDialogModel = null
                        if (modelToLoad == null) return@TextButton
                        scope.launch {
                            loadingModelName = modelToLoad.name
                            try {
                                val gpuOk = modelToLoad.supportsGpu &&
                                    !(modelToLoad.modelFormat == "litertlm" &&
                                        modelToLoad.name.contains("Gemma-4", ignoreCase = true))
                                val preferred = when {
                                    !gpuOk -> LlmInference.Backend.CPU
                                    else -> backend
                                }
                                val ok = withContext(Dispatchers.IO) {
                                    inference.loadModel(modelToLoad, preferred, null)
                                }
                                if (ok) {
                                    loadedRefresh++
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.local_api_load_success, modelToLoad.name)
                                    )
                                } else {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.local_api_load_failed, "load returned false")
                                    )
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        R.string.local_api_load_failed,
                                        e.message ?: e.javaClass.simpleName
                                    )
                                )
                            } finally {
                                loadingModelName = null
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.local_api_load_model), color = NeuralHubColors.AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { loadDialogModel = null }) {
                    Text(stringResource(R.string.cancel), color = NeuralHubColors.TextDim)
                }
            }
        )
    }
}

@Composable
private fun LocalApiPulseFrame(
    pulse: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "local_api_pulse")
    val phase by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )
    val borderAlpha = if (pulse) phase else 0.42f
    Box(
        modifier = modifier.border(
            width = 1.5.dp,
            color = accent.copy(alpha = borderAlpha),
            shape = RoundedCornerShape(12.dp)
        )
    ) {
        content()
    }
}

@Composable
private fun LocalApiModelCard(
    model: LLMModel,
    isLoaded: Boolean,
    isBusy: Boolean,
    onRequestLoad: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = NeuralHubUiDefaults.glassCardColors(),
        border = if (isLoaded) {
            BorderStroke(1.dp, NeuralHubColors.AccentGreen.copy(alpha = 0.45f))
        } else {
            NeuralHubUiDefaults.glassCardBorderStroke()
        },
        elevation = NeuralHubUiDefaults.glassCardElevation()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoaded) {
                Text(
                    text = stringResource(R.string.local_api_loaded),
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeuralHubColors.AccentGreen
                )
            }
            Text(
                text = model.name,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = model.modelFormat,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = NeuralHubColors.TextDim
            )
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .size(28.dp),
                    strokeWidth = 2.dp,
                    color = NeuralHubColors.AccentCyan
                )
            } else {
                OutlinedButton(
                    onClick = onRequestLoad,
                    enabled = !isLoaded,
                    border = BorderStroke(1.dp, NeuralHubColors.AccentCyan),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = NeuralHubColors.AccentCyan
                    )
                ) {
                    Text(
                        if (isLoaded) stringResource(R.string.local_api_loaded)
                        else stringResource(R.string.local_api_load_model)
                    )
                }
            }
        }
    }
}
