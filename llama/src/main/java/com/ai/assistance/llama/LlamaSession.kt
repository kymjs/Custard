package com.ai.assistance.llama

class LlamaSession private constructor(
    private var sessionPtr: Long
) {

    companion object {
        fun isAvailable(): Boolean = runCatching { LlamaNative.nativeIsAvailable() }.getOrDefault(false)

        fun getUnavailableReason(): String = runCatching { LlamaNative.nativeGetUnavailableReason() }
            .getOrDefault("llama.cpp backend unavailable")

        fun create(
            pathModel: String,
            nThreads: Int,
            nCtx: Int
        ): LlamaSession? {
            if (!isAvailable()) return null
            val ptr = LlamaNative.nativeCreateSession(pathModel, nThreads, nCtx)
            if (ptr == 0L) return null
            return LlamaSession(ptr)
        }
    }

    @Volatile
    private var released = false

    private val lock = Any()

    private fun checkValid() {
        if (released || sessionPtr == 0L) {
            throw RuntimeException("LlamaSession has been released")
        }
    }

    fun countTokens(text: String): Int {
        synchronized(lock) {
            checkValid()
            return LlamaNative.nativeCountTokens(sessionPtr, text)
        }
    }

    fun generateStream(prompt: String, maxTokens: Int, onToken: (String) -> Boolean): Boolean {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeGenerateStream(
            ptr,
            prompt,
            maxTokens,
            object : LlamaNative.GenerationCallback {
                override fun onToken(token: String): Boolean = onToken(token)
            }
        )
    }

    fun setToolCallGrammar(grammar: String, triggerPatterns: List<String>): Boolean {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeSetToolCallGrammar(
            ptr,
            grammar,
            triggerPatterns.toTypedArray()
        )
    }

    fun clearToolCallGrammar(): Boolean {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeClearToolCallGrammar(ptr)
    }

    fun applyChatTemplate(
        roles: List<String>,
        contents: List<String>,
        addAssistant: Boolean
    ): String? {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeApplyChatTemplate(
            ptr,
            roles.toTypedArray(),
            contents.toTypedArray(),
            addAssistant
        )
    }

    fun setSamplingParams(
        temperature: Float,
        topP: Float,
        topK: Int,
        repetitionPenalty: Float,
        frequencyPenalty: Float,
        presencePenalty: Float,
        penaltyLastN: Int = 64
    ): Boolean {
        val ptr: Long
        synchronized(lock) {
            checkValid()
            ptr = sessionPtr
        }

        return LlamaNative.nativeSetSamplingParams(
            ptr,
            temperature,
            topP,
            topK,
            repetitionPenalty,
            frequencyPenalty,
            presencePenalty,
            penaltyLastN
        )
    }

    fun cancel() {
        synchronized(lock) {
            if (released || sessionPtr == 0L) return
            LlamaNative.nativeCancel(sessionPtr)
        }
    }

    fun release() {
        val ptr: Long
        synchronized(lock) {
            if (released) return
            released = true
            ptr = sessionPtr
            sessionPtr = 0L
        }
        if (ptr != 0L) {
            LlamaNative.nativeReleaseSession(ptr)
        }
    }
}
