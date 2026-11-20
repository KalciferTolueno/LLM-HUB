package com.llmhub.llmhub.viewmodels

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.LlmHubApplication
import com.llmhub.llmhub.data.ThemeMode
import com.llmhub.llmhub.data.ThemePreferences
import com.llmhub.llmhub.R
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelPreferences
import com.llmhub.llmhub.inference.UnifiedInferenceService
import com.llmhub.llmhub.service.LocalApiForegroundService
import com.llmhub.llmhub.utils.LocaleHelper
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

class ThemeViewModel(private val context: Context, private val inferenceService: com.llmhub.llmhub.inference.InferenceService? = null) : ViewModel() {
    private val themePreferences = ThemePreferences(context)
    private val modelPrefsForTools = ModelPreferences(context.applicationContext)

    private val app: LlmHubApplication
        get() = context.applicationContext as LlmHubApplication

    /** Shared with [LocalApiForegroundService] via [LlmHubApplication.localApiServer]. */
    val apiServer: com.llmhub.llmhub.inference.LlmApiServer?
        get() = if (inferenceService != null) app.localApiServer else null

    /** Last value used for LAN ↔ agent-tools policy (see [commitApiRunningState]). */
    private var committedApiServerRunning: Boolean = false

    private fun commitApiRunningState(now: Boolean) {
        _isApiServerRunning.value = now
        val was = committedApiServerRunning
        if (was != now) {
            val inf = inferenceService as? UnifiedInferenceService
            if (inf != null) {
                if (now) {
                    inf.setAgentToolsEnabled(false)
                } else {
                    val m = inf.getCurrentlyLoadedModel()
                    if (m != null) {
                        val cfg = runBlocking(Dispatchers.IO) {
                            modelPrefsForTools.getModelConfig(m.name)
                        }
                        inf.setAgentToolsEnabled(cfg?.agentToolsEnabled ?: true)
                    }
                }
            }
        }
        committedApiServerRunning = now
    }

    fun syncLocalApiServerState() {
        commitApiRunningState(apiServer?.isRunning() == true)
    }
    private val _isApiServerRunning = MutableStateFlow(false)
    val isApiServerRunning: StateFlow<Boolean> = _isApiServerRunning.asStateFlow()

    private val _apiServerBusy = MutableStateFlow(false)
    val apiServerBusy: StateFlow<Boolean> = _apiServerBusy.asStateFlow()

    private val _apiServerError = MutableStateFlow<String?>(null)
    val apiServerError: StateFlow<String?> = _apiServerError.asStateFlow()

    fun clearApiServerError() {
        _apiServerError.value = null
    }

    fun toggleApiServer() {
        val server = apiServer
        if (server == null) {
            Log.e("ThemeViewModel", "InferenceService not available, cannot start API server")
            _apiServerError.value = "Inference service unavailable"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _apiServerBusy.value = true
                if (server.isRunning()) {
                    stopLocalApiServerSuspended()
                    Log.d("ThemeViewModel", "API server stopped")
                } else {
                    startLocalApiServerSuspended()
                    if (server.isRunning()) {
                        Log.d("ThemeViewModel", "API server started on ${server.getLocalIpAddress()}:8080")
                    }
                }
            } catch (e: Throwable) {
                Log.e("ThemeViewModel", "API server toggle failed", e)
                withContext(Dispatchers.Main) {
                    commitApiRunningState(server.isRunning())
                    _apiServerError.value = e.message ?: e.javaClass.simpleName
                }
            } finally {
                _apiServerBusy.value = false
            }
        }
    }

    /** Starts the HTTP server if it is not already running (e.g. after loading the default model from home). */
    fun startLocalApiServer() {
        val server = apiServer ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _apiServerBusy.value = true
                startLocalApiServerSuspended()
            } catch (e: Throwable) {
                Log.e("ThemeViewModel", "startLocalApiServer failed", e)
                withContext(Dispatchers.Main) {
                    commitApiRunningState(server.isRunning())
                    _apiServerError.value = e.message ?: e.javaClass.simpleName
                }
            } finally {
                _apiServerBusy.value = false
            }
        }
    }

    /** Stops the HTTP server if it is running. */
    fun stopLocalApiServer() {
        val server = apiServer ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _apiServerBusy.value = true
                stopLocalApiServerSuspended()
            } catch (e: Throwable) {
                Log.e("ThemeViewModel", "stopLocalApiServer failed", e)
                withContext(Dispatchers.Main) {
                    commitApiRunningState(server.isRunning())
                    _apiServerError.value = e.message ?: e.javaClass.simpleName
                }
            } finally {
                _apiServerBusy.value = false
            }
        }
    }

    private suspend fun startLocalApiServerSuspended() {
        val server = apiServer ?: return
        if (server.isRunning()) {
            withContext(Dispatchers.Main) {
                commitApiRunningState(true)
                _apiServerError.value = null
            }
            return
        }
        val appContext = context.applicationContext
        withContext(Dispatchers.Main) {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, LocalApiForegroundService::class.java)
            )
        }
        delay(120)
        val ok = server.isRunning()
        withContext(Dispatchers.Main) {
            commitApiRunningState(ok)
            _apiServerError.value = if (ok) null else "Could not start API server"
        }
    }

    private suspend fun stopLocalApiServerSuspended() {
        val server = apiServer ?: return
        if (!server.isRunning()) {
            withContext(Dispatchers.Main) {
                commitApiRunningState(false)
                _apiServerError.value = null
            }
            return
        }
        val appContext = context.applicationContext
        appContext.stopService(Intent(appContext, LocalApiForegroundService::class.java))
        delay(200)
        if (server.isRunning()) {
            try {
                server.stop()
            } catch (e: Throwable) {
                Log.w("ThemeViewModel", "Server stop fallback", e)
            }
        }
        withContext(Dispatchers.Main) {
            commitApiRunningState(server.isRunning())
            _apiServerError.value = null
        }
    }

    fun getApiServerIp(): String? = apiServer?.getLocalIpAddress()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    
    private val _webSearchEnabled = MutableStateFlow(true)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()
    
    private val _appLanguage = MutableStateFlow<String?>(null)
    val appLanguage: StateFlow<String?> = _appLanguage.asStateFlow()
    
    private val _selectedEmbeddingModel = MutableStateFlow<String?>(null)
    val selectedEmbeddingModel: StateFlow<String?> = _selectedEmbeddingModel.asStateFlow()

    private val _embeddingEnabled = MutableStateFlow(false)
    val embeddingEnabled: StateFlow<Boolean> = _embeddingEnabled.asStateFlow()

    private val _memoryEnabled = MutableStateFlow(false)
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    private val _autoReadoutEnabled = MutableStateFlow(false)
    val autoReadoutEnabled: StateFlow<Boolean> = _autoReadoutEnabled.asStateFlow()

    private val _localApiDefaultModelName = MutableStateFlow<String?>(null)
    val localApiDefaultModelName: StateFlow<String?> = _localApiDefaultModelName.asStateFlow()

    private val _localApiReasoningEnabled = MutableStateFlow(false)
    val localApiReasoningEnabled: StateFlow<Boolean> = _localApiReasoningEnabled.asStateFlow()
    
    init {
        // Load the saved theme preference
        viewModelScope.launch {
            themePreferences.themeMode.collect { mode ->
                _themeMode.value = mode
            }
        }
        
        // Load the saved web search preference
        viewModelScope.launch {
            themePreferences.webSearchEnabled.collect { enabled ->
                _webSearchEnabled.value = enabled
            }
        }
        
        // Load the saved language preference
        viewModelScope.launch {
            themePreferences.appLanguage.collect { language ->
                _appLanguage.value = language
            }
        }
        
        // Load the saved embedding model preference
        viewModelScope.launch {
            themePreferences.selectedEmbeddingModel.collect { model ->
                _selectedEmbeddingModel.value = model
            }
        }

        // Load the saved embedding enabled preference
        viewModelScope.launch {
            themePreferences.embeddingEnabled.collect { enabled ->
                _embeddingEnabled.value = enabled
            }
        }

        // Load the saved memory enabled preference
        viewModelScope.launch {
            themePreferences.memoryEnabled.collect { enabled ->
                _memoryEnabled.value = enabled
            }
        }

        // Load the saved auto-readout preference
        viewModelScope.launch {
            themePreferences.autoReadoutEnabled.collect { enabled ->
                _autoReadoutEnabled.value = enabled
            }
        }

        viewModelScope.launch {
            themePreferences.localApiDefaultModelName.collect { name ->
                _localApiDefaultModelName.value = name
            }
        }

        viewModelScope.launch {
            themePreferences.localApiReasoningEnabled.collect { on ->
                _localApiReasoningEnabled.value = on
            }
        }

        syncLocalApiServerState()
    }

    fun setLocalApiReasoningEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themePreferences.setLocalApiReasoningEnabled(enabled)
            _localApiReasoningEnabled.value = enabled
        }
    }

    fun setLocalApiDefaultModelName(name: String?) {
        viewModelScope.launch {
            themePreferences.setLocalApiDefaultModelName(name)
            _localApiDefaultModelName.value = name
        }
    }

    /**
     * Loads the model chosen as default on the Local network API screen (CPU/GPU follows catalog rules).
     * @return null on success, or a user-visible error string
     */
    fun loadLocalApiDefaultModel(
        downloadedModels: List<LLMModel>,
        onResult: (errorMessage: String?) -> Unit
    ) {
        val inf = inferenceService
        if (inf == null) {
            onResult(context.getString(R.string.home_primary_engine_inference_unavailable))
            return
        }
        val name = _localApiDefaultModelName.value
        if (name.isNullOrBlank()) {
            onResult(context.getString(R.string.home_primary_engine_no_default))
            return
        }
        val model = downloadedModels.find { it.name == name && it.isDownloaded }
        if (model == null) {
            onResult(context.getString(R.string.home_primary_engine_default_missing, name))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isGemma4LiteRt =
                    model.modelFormat == "litertlm" &&
                        model.name.contains("Gemma-4", ignoreCase = true)
                val backend =
                    if (!model.supportsGpu || isGemma4LiteRt) {
                        LlmInference.Backend.CPU
                    } else {
                        LlmInference.Backend.GPU
                    }
                val ok = inf.loadModel(model, backend, null)
                if (ok) {
                    try {
                        _apiServerBusy.value = true
                        startLocalApiServerSuspended()
                    } finally {
                        _apiServerBusy.value = false
                    }
                }
                withContext(Dispatchers.Main) {
                    onResult(
                        if (ok) null
                        else context.getString(R.string.home_primary_engine_load_failed)
                    )
                }
            } catch (e: Throwable) {
                Log.e("ThemeViewModel", "loadLocalApiDefaultModel", e)
                withContext(Dispatchers.Main) {
                    onResult(e.message ?: context.getString(R.string.home_primary_engine_load_failed))
                }
            }
        }
    }

    fun unloadInferenceModel(onFinished: () -> Unit = {}) {
        val inf = inferenceService ?: run {
            onFinished()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _apiServerBusy.value = true
                stopLocalApiServerSuspended()
                inf.unloadModel()
            } catch (e: Throwable) {
                Log.w("ThemeViewModel", "unloadInferenceModel", e)
            } finally {
                _apiServerBusy.value = false
                withContext(Dispatchers.Main) {
                    onFinished()
                }
            }
        }
    }
    
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themePreferences.setThemeMode(mode)
            _themeMode.value = mode
        }
    }
    
    fun setWebSearchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themePreferences.setWebSearchEnabled(enabled)
            _webSearchEnabled.value = enabled
        }
    }
    
    fun setSelectedEmbeddingModel(modelName: String?) {
        viewModelScope.launch {
            themePreferences.setSelectedEmbeddingModel(modelName)
            _selectedEmbeddingModel.value = modelName
            // Automatically enable embeddings when a model is selected, disable when null
            themePreferences.setEmbeddingEnabled(modelName != null)
        }
    }

    fun setEmbeddingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themePreferences.setEmbeddingEnabled(enabled)
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themePreferences.setMemoryEnabled(enabled)
            _memoryEnabled.value = enabled
        }
    }

    fun setAutoReadoutEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themePreferences.setAutoReadoutEnabled(enabled)
            _autoReadoutEnabled.value = enabled
        }
    }
    
    private val _languageChangeCounter = MutableStateFlow(0)
    val languageChangeCounter: StateFlow<Int> = _languageChangeCounter.asStateFlow()
    
    fun setAppLanguage(languageCode: String?) {
        viewModelScope.launch {
            val oldLanguage = _appLanguage.value
            themePreferences.setAppLanguage(languageCode)
            _appLanguage.value = languageCode
            
            // Apply the new locale
            LocaleHelper.setLocale(context, languageCode)
            
            // Only recreate if language actually changed
            if (oldLanguage != languageCode) {
                // Trigger a counter change to force recomposition
                _languageChangeCounter.value += 1
                
                // Recreate the activity if it's an Activity context
                if (context is Activity) {
                    context.recreate()
                }
            }
        }
    }
    
    fun getSupportedLanguages(): List<Pair<String, String>> {
        return LocaleHelper.getSupportedLanguages(context)
    }
    
    fun getCurrentLanguageDisplayName(): String {
        val currentLanguage = _appLanguage.value
        return if (currentLanguage != null) {
            getSupportedLanguages().find { it.first == currentLanguage }?.second ?: "System Default"
        } else {
            "System Default"
        }
    }
    
    /**
     * Get the effective language code currently being used
     */
    fun getEffectiveLanguageCode(): String {
        val userPreference = _appLanguage.value
        return if (userPreference != null && isLanguageSupported(userPreference)) {
            userPreference
        } else {
            // Fall back to system locale
            val systemLocale = LocaleHelper.getCurrentLocale(context)
            val systemLanguage = systemLocale.language
            if (isLanguageSupported(systemLanguage)) systemLanguage else "en"
        }
    }
    
    private fun isLanguageSupported(languageCode: String): Boolean {
        return LocaleHelper.isLanguageSupported(languageCode)
    }
}
