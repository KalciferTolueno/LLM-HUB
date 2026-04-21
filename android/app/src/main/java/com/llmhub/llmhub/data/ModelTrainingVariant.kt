package com.llmhub.llmhub.data

/**
 * Whether a model is instruction-tuned (IT), a raw pretrained base, or not applicable
 * (embeddings, image generation, etc.). Used for catalog filtering.
 */
enum class ModelTrainingVariant {
    IT,
    BASE,
    NA
}

fun LLMModel.resolvedTrainingVariant(): ModelTrainingVariant {
    trainingKind?.let { return it }
    return when (category) {
        "embedding", "image_generation", "qnn_npu", "mnn_cpu" -> ModelTrainingVariant.NA
        else -> ModelTrainingVariant.IT
    }
}
