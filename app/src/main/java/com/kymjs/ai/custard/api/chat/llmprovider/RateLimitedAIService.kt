package com.kymjs.ai.custard.api.chat.llmprovider

import android.content.Context
import com.kymjs.ai.custard.data.model.ModelParameter
import com.kymjs.ai.custard.data.model.ToolPrompt
import com.kymjs.ai.custard.util.stream.Stream
import kotlinx.coroutines.sync.Semaphore

class RateLimitedAIService(
    private val delegate: AIService,
    private val rateLimiter: SlidingWindowRateLimiter?,
    private val concurrencySemaphore: Semaphore?
) : AIService by delegate {

    override suspend fun sendMessage(
        context: Context,
        message: String,
        chatHistory: List<Pair<String, String>>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onNonFatalError: suspend (error: String) -> Unit
    ): Stream<String> = com.kymjs.ai.custard.util.stream.stream {
        rateLimiter?.acquire()
        concurrencySemaphore?.acquire()

        try {
            delegate.sendMessage(
                context = context,
                message = message,
                chatHistory = chatHistory,
                modelParameters = modelParameters,
                enableThinking = enableThinking,
                stream = stream,
                availableTools = availableTools,
                preserveThinkInHistory = preserveThinkInHistory,
                onTokensUpdated = onTokensUpdated,
                onNonFatalError = onNonFatalError
            ).collect { chunk ->
                emit(chunk)
            }
        } finally {
            concurrencySemaphore?.release()
        }
    }
}
