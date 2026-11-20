package com.llmhub.llmhub.data

/**
 * High-level model family for catalog navigation (Gemma, Qwen, etc.).
 * Derived from [LLMModel.name] / category — no schema change on [LLMModel].
 */
fun LLMModel.catalogFamily(): String {
    val n = name.lowercase()
    when (category) {
        "embedding" -> {
            return when {
                n.contains("nomic") -> "Nomic"
                n.contains("bge") -> "BGE"
                n.contains("minilm") || n.contains("all-minilm") -> "MiniLM"
                n.contains("e5") && n.contains("multilingual") -> "E5"
                n.contains("e5") -> "E5"
                else -> "Other"
            }
        }
        "image_generation", "qnn_npu", "mnn_cpu" -> {
            return when {
                n.contains("stable") || n.contains("sdxl") || n.contains("sd-") -> "Stable Diffusion"
                else -> "Image"
            }
        }
    }
    return when {
        n.contains("gemma") -> "Gemma"
        n.contains("qwen") -> "Qwen"
        n.contains("llama") || n.contains("meta-llama") -> "Llama"
        n.contains("mistral") || n.contains("mixtral") -> "Mistral"
        n.contains("phi-") || n.contains(" phi") || n.startsWith("phi") -> "Phi"
        n.contains("granite") -> "Granite"
        n.contains("liquid") -> "Liquid"
        n.contains("gpt-oss") || n.contains("gpt_oss") || n.contains("gpt oss") -> "GPT-OSS"
        n.contains("olmo") -> "Olmo"
        n.contains("smollm") || n.contains("smol ") -> "SmolLM"
        else -> "Other"
    }
}

private val CATALOG_FAMILY_ORDER = listOf(
    "Gemma", "Qwen", "Llama", "Mistral", "Phi", "Granite", "Liquid",
    "GPT-OSS", "Olmo", "SmolLM",
    "Nomic", "BGE", "MiniLM", "E5",
    "Stable Diffusion", "Image",
    "Other"
)

fun sortedCatalogFamilyLabels(keys: Set<String>): List<String> {
    val ordered = CATALOG_FAMILY_ORDER.filter { it in keys }
    val rest = (keys - ordered.toSet()).sorted()
    return ordered + rest
}
