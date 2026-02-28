package com.kymjs.ai.custard.data.model

enum class MemoryScoreMode {
    BALANCED,
    KEYWORD_FIRST,
    SEMANTIC_FIRST
}

data class MemorySearchConfig(
    val semanticThreshold: Float = 0.6f,
    val scoreMode: MemoryScoreMode = MemoryScoreMode.BALANCED,
    val keywordWeight: Float = 10.0f,
    val vectorWeight: Float = 0.0f,
    val edgeWeight: Float = 0.4f
) {
    fun normalized(): MemorySearchConfig {
        return copy(
            semanticThreshold = semanticThreshold.coerceIn(0.0f, 1.0f),
            keywordWeight = keywordWeight.coerceAtLeast(0.0f),
            vectorWeight = vectorWeight.coerceAtLeast(0.0f),
            edgeWeight = edgeWeight.coerceAtLeast(0.0f)
        )
    }
}
