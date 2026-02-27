package com.ai.assistance.llama

object LlamaNative {

    init {
        LlamaLibraryLoader.loadLibraries()
    }

    @JvmStatic external fun nativeIsAvailable(): Boolean

    @JvmStatic external fun nativeGetUnavailableReason(): String

    @JvmStatic external fun nativeCreateSession(pathModel: String, nThreads: Int, nCtx: Int): Long

    @JvmStatic external fun nativeReleaseSession(sessionPtr: Long)

    @JvmStatic external fun nativeCancel(sessionPtr: Long)

    @JvmStatic external fun nativeCountTokens(sessionPtr: Long, text: String): Int

    @JvmStatic
    external fun nativeSetSamplingParams(
        sessionPtr: Long,
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        frequencyPenalty: Float,
        presencePenalty: Float,
        penaltyLastN: Int
    ): Boolean

    @JvmStatic
    external fun nativeApplyChatTemplate(
        sessionPtr: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean
    ): String?

    @JvmStatic
    external fun nativeGenerateStream(
        sessionPtr: Long,
        prompt: String,
        maxTokens: Int,
        callback: GenerationCallback
    ): Boolean

    @JvmStatic
    external fun nativeSetToolCallGrammar(
        sessionPtr: Long,
        grammar: String,
        triggerPatterns: Array<String>
    ): Boolean

    @JvmStatic
    external fun nativeClearToolCallGrammar(sessionPtr: Long): Boolean

    interface GenerationCallback {
        fun onToken(token: String): Boolean
    }
}
