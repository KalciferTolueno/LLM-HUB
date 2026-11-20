package com.llmhub.llmhub.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.llmhub.llmhub.LlmHubApplication
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.notification.ModelRunningNotifier
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Unified Inference Service that routes requests to the appropriate backend Service
 * (MediaPipe or ONNX) based on the model format.
 */
class UnifiedInferenceService(private val context: Context) : InferenceService {

    /**
     * One in-flight generation at a time across all backends. The local HTTP API and in-app chat
     * share the same [InferenceService] instance; concurrent native inference crashes the process.
     */
    private val generationMutex = Mutex()

    private val mediaPipeService = MediaPipeInferenceService(context)
    private val liteRtLmService = LiteRtLmInferenceService(context)
    private val onnxService = OnnxInferenceService(context)
    private val nexaService = NexaInferenceService(context)
    
    private var currentService: InferenceService = mediaPipeService
    private var currentModel: LLMModel? = null

    /** LAN API shares [generationMutex]; cancel HTTP work before unload/swap or session clear. */
    private fun interruptLocalApiRequests(reason: String) {
        try {
            (context.applicationContext as? LlmHubApplication)?.localApiServer?.cancelInFlightRequests(reason)
        } catch (e: Throwable) {
            Log.w("UnifiedInferenceService", "interruptLocalApiRequests", e)
        }
    }

    override suspend fun loadModel(model: LLMModel, preferredBackend: LlmInference.Backend?, deviceId: String?): Boolean {
        // Determine which service to use initially
        if (model.modelFormat == "gguf" && (nexaService as? com.llmhub.llmhub.inference.NexaInferenceService)?.isAvailable() != true) {
            throw AllBackendsFailedException("GGUF models require the Nexa SDK which is not available on this device")
        }
        val isGemma4 = model.name.contains("Gemma-4", ignoreCase = true)
        val targetService = when (model.modelFormat) {
            "onnx" -> onnxService
            "gguf" -> nexaService
            "litertlm" -> if (isGemma4) liteRtLmService else mediaPipeService
            else -> mediaPipeService
        }

        // Same service and same model already loaded with same backend: skip reload (honor user's CPU/GPU choice on next load)
        if (currentService == targetService) {
            val loaded = currentService.getCurrentlyLoadedModel()
            val currentBackend = currentService.getCurrentlyLoadedBackend()
            if (loaded?.name == model.name && (preferredBackend == null || preferredBackend == currentBackend)) {
                currentModel = model
                updateAgentTools(model)
                ModelRunningNotifier.show(context, model.name)
                return true
            }
        }

        interruptLocalApiRequests("model load/replace")

        // If switching services, unload the old one
        if (currentService != targetService && currentModel != null) {
            currentService.unloadModel()
        }

        currentService = targetService
        currentModel = model
        
        try {
            val success = currentService.loadModel(model, preferredBackend, deviceId)
            if (!success) {
                currentModel = null
                ModelRunningNotifier.dismiss(context)
                throw AllBackendsFailedException("Backend ${currentService.javaClass.simpleName} failed to load model '${model.name}'")
            }
            updateAgentTools(model)
            ModelRunningNotifier.show(context, model.name)
            return true
        } catch (e: AllBackendsFailedException) {
            currentModel = null
            ModelRunningNotifier.dismiss(context)
            throw e
        } catch (e: Exception) {
            android.util.Log.e("UnifiedInferenceService", "Service ${currentService.javaClass.simpleName} failed to load model '${model.name}'", e)
            currentModel = null
            ModelRunningNotifier.dismiss(context)
            throw AllBackendsFailedException("Failed to load model '${model.name}': ${e.message}")
        }
    }

    override suspend fun loadModel(
        model: LLMModel, 
        preferredBackend: LlmInference.Backend?, 
        disableVision: Boolean, 
        disableAudio: Boolean,
        deviceId: String?
    ): Boolean {
        if (model.modelFormat == "gguf" && (nexaService as? com.llmhub.llmhub.inference.NexaInferenceService)?.isAvailable() != true) {
            throw AllBackendsFailedException("GGUF models require the Nexa SDK which is not available on this device")
        }
        val isGemma4 = model.name.contains("Gemma-4", ignoreCase = true)
        val targetService = when (model.modelFormat) {
            "onnx" -> onnxService
            "gguf" -> nexaService
            "litertlm" -> if (isGemma4) liteRtLmService else mediaPipeService
            else -> mediaPipeService
        }

        // Same service and same model already loaded with same backend: skip reload (honor user's CPU/GPU choice on next load)
        if (currentService == targetService) {
            val loaded = currentService.getCurrentlyLoadedModel()
            val currentBackend = currentService.getCurrentlyLoadedBackend()
            if (loaded?.name == model.name && (preferredBackend == null || preferredBackend == currentBackend)) {
                currentModel = model
                updateAgentTools(model)
                ModelRunningNotifier.show(context, model.name)
                return true
            }
        }

        interruptLocalApiRequests("model load/replace")

        // If switching services, unload the old one
        if (currentService != targetService && currentModel != null) {
            currentService.unloadModel()
        }

        currentService = targetService
        currentModel = model
        
        try {
            val success = currentService.loadModel(model, preferredBackend, disableVision, disableAudio, deviceId)
            if (!success) {
                currentModel = null
                ModelRunningNotifier.dismiss(context)
                throw AllBackendsFailedException("Backend ${currentService.javaClass.simpleName} failed to load model '${model.name}'")
            }
            updateAgentTools(model)
            ModelRunningNotifier.show(context, model.name)
            return true
        } catch (e: AllBackendsFailedException) {
            currentModel = null
            ModelRunningNotifier.dismiss(context)
            throw e
        } catch (e: Exception) {
            android.util.Log.e("UnifiedInferenceService", "Service ${currentService.javaClass.simpleName} failed to load model '${model.name}'", e)
            currentModel = null
            ModelRunningNotifier.dismiss(context)
            throw AllBackendsFailedException("Failed to load model '${model.name}': ${e.message}")
        }
    }

    override suspend fun unloadModel() {
        interruptLocalApiRequests("model unloaded")
        currentService.unloadModel()
        currentModel = null
        ModelRunningNotifier.dismiss(context)
    }

    override suspend fun generateResponse(prompt: String, model: LLMModel): String {
        return generationMutex.withLock {
            currentService.generateResponse(prompt, model)
        }
    }

    override suspend fun generateResponseStream(prompt: String, model: LLMModel): Flow<String> {
        return flow {
            generationMutex.withLock {
                currentService.generateResponseStream(prompt, model).collect { emit(it) }
            }
        }
    }

    override suspend fun generateResponseStreamWithSession(
        prompt: String,
        model: LLMModel,
        chatId: String,
        images: List<Bitmap>,
        audioData: ByteArray?,
        webSearchEnabled: Boolean,
        imagePaths: List<String>,
        useAgentTools: Boolean
    ): Flow<String> {
        return flow {
            generationMutex.withLock {
                currentService
                    .generateResponseStreamWithSession(
                        prompt,
                        model,
                        chatId,
                        images,
                        audioData,
                        webSearchEnabled,
                        imagePaths,
                        useAgentTools
                    )
                    .collect { emit(it) }
            }
        }
    }

    override suspend fun resetChatSession(chatId: String) {
        currentService.resetChatSession(chatId)
    }

    override suspend fun onCleared() {
        interruptLocalApiRequests("inference onCleared")
        mediaPipeService.onCleared()
        liteRtLmService.onCleared()
        onnxService.onCleared()
        if ((nexaService as? com.llmhub.llmhub.inference.NexaInferenceService)?.isAvailable() == true) {
            nexaService.onCleared()
        }
    }

    override fun getCurrentlyLoadedModel(): LLMModel? {
        return currentService.getCurrentlyLoadedModel()
    }

    override fun getCurrentlyLoadedBackend(): LlmInference.Backend? {
        return currentService.getCurrentlyLoadedBackend()
    }

    override fun getMemoryWarningForImages(images: List<Bitmap>): String? {
        return currentService.getMemoryWarningForImages(images)
    }

    override fun wasSessionRecentlyReset(chatId: String): Boolean {
        return currentService.wasSessionRecentlyReset(chatId)
    }

    override fun setGenerationParameters(maxTokens: Int?, topK: Int?, topP: Float?, temperature: Float?, nGpuLayers: Int?, enableThinking: Boolean?, contextWindow: Int?) {
        mediaPipeService.setGenerationParameters(maxTokens, topK, topP, temperature, nGpuLayers, enableThinking, contextWindow)
        liteRtLmService.setGenerationParameters(maxTokens, topK, topP, temperature, nGpuLayers, enableThinking, contextWindow)
        onnxService.setGenerationParameters(maxTokens, topK, topP, temperature, nGpuLayers, enableThinking, contextWindow)
        if ((nexaService as? com.llmhub.llmhub.inference.NexaInferenceService)?.isAvailable() == true) {
            nexaService.setGenerationParameters(maxTokens, topK, topP, temperature, nGpuLayers, enableThinking, contextWindow)
        }
    }

    override fun isVisionCurrentlyDisabled(): Boolean {
        return currentService.isVisionCurrentlyDisabled()
    }

    override fun isAudioCurrentlyDisabled(): Boolean {
        return currentService.isAudioCurrentlyDisabled()
    }

    override fun isGpuBackendEnabled(): Boolean {
        return currentService.isGpuBackendEnabled()
    }

    override fun getEffectiveMaxTokens(model: LLMModel): Int {
        return when (model.modelFormat) {
            "onnx" -> onnxService.getEffectiveMaxTokens(model)
            "gguf" -> if ((nexaService as? com.llmhub.llmhub.inference.NexaInferenceService)?.isAvailable() == true) nexaService.getEffectiveMaxTokens(model) else mediaPipeService.getEffectiveMaxTokens(model)
            "litertlm" -> liteRtLmService.getEffectiveMaxTokens(model)
            else -> mediaPipeService.getEffectiveMaxTokens(model)
        }
    }

    /**
     * Activate or deactivate the Gemma-4 agent skills toolset.
     * Tools are enabled only for Gemma-4 models because they are specifically trained
     * for function calling via the LiteRT-LM SDK. All other models get tools cleared.
     */
    private var agentToolsEnabled: Boolean = true

    fun setAgentToolsEnabled(enabled: Boolean) {
        agentToolsEnabled = enabled
        currentModel?.let { updateAgentTools(it) }
    }

    private fun updateAgentTools(model: LLMModel) {
        if (agentToolsEnabled && model.modelFormat == "litertlm" && model.name.contains("Gemma-4", ignoreCase = true)) {
            liteRtLmService.setAgentTools(ChatAgentSkillsTools(context))
        } else {
            liteRtLmService.setAgentTools(null)
        }
    }
}
