package com.kymjs.ai.custard.integrations.intent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kymjs.ai.custard.core.tools.defaultTool.standard.StandardChatManagerTool
import com.kymjs.ai.custard.core.tools.MessageSendResultData
import com.kymjs.ai.custard.data.model.AITool
import com.kymjs.ai.custard.data.model.ToolParameter
import com.kymjs.ai.custard.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ExternalChatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ExternalChatReceiver"

        const val ACTION_EXTERNAL_CHAT = "com.kymjs.ai.custard.EXTERNAL_CHAT"
        const val ACTION_EXTERNAL_CHAT_RESULT = "com.kymjs.ai.custard.EXTERNAL_CHAT_RESULT"

        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_GROUP = "group"
        const val EXTRA_CREATE_NEW_CHAT = "create_new_chat"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CREATE_IF_NONE = "create_if_none"

        const val EXTRA_SHOW_FLOATING = "show_floating"
        const val EXTRA_AUTO_EXIT_AFTER_MS = "auto_exit_after_ms"
        const val EXTRA_STOP_AFTER = "stop_after"

        const val EXTRA_REPLY_ACTION = "reply_action"
        const val EXTRA_REPLY_PACKAGE = "reply_package"

        const val EXTRA_RESULT_SUCCESS = "success"
        const val EXTRA_RESULT_CHAT_ID = "chat_id"
        const val EXTRA_RESULT_AI_RESPONSE = "ai_response"
        const val EXTRA_RESULT_ERROR = "error"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXTERNAL_CHAT) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                val group = intent.getStringExtra(EXTRA_GROUP)
                val createNewChat = intent.getBooleanExtra(EXTRA_CREATE_NEW_CHAT, false)
                val chatId = intent.getStringExtra(EXTRA_CHAT_ID)
                val createIfNone = intent.getBooleanExtra(EXTRA_CREATE_IF_NONE, true)

                val showFloating = intent.getBooleanExtra(EXTRA_SHOW_FLOATING, false)
                val autoExitAfterMs = intent.getLongExtra(EXTRA_AUTO_EXIT_AFTER_MS, -1L)
                val stopAfter = intent.getBooleanExtra(EXTRA_STOP_AFTER, false)

                val replyAction = intent.getStringExtra(EXTRA_REPLY_ACTION)?.takeIf { it.isNotBlank() }
                    ?: ACTION_EXTERNAL_CHAT_RESULT
                val replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE)?.takeIf { it.isNotBlank() }

                if (message.isNullOrBlank()) {
                    sendResultBroadcast(
                        context = context,
                        action = replyAction,
                        packageName = replyPackage,
                        requestId = requestId,
                        success = false,
                        chatId = null,
                        aiResponse = null,
                        error = "Missing extra: $EXTRA_MESSAGE"
                    )
                    return@launch
                }

                val chatTool = StandardChatManagerTool(context.applicationContext)

                if (showFloating) {
                    val params = mutableListOf<ToolParameter>()
                    if (autoExitAfterMs > 0) {
                        params += ToolParameter(name = "timeout_ms", value = autoExitAfterMs.toString())
                    }
                    chatTool.startChatService(AITool(name = "start_chat_service", parameters = params))
                }

                if (!createNewChat && chatId.isNullOrBlank() && !createIfNone) {
                    val listResult = chatTool.listChats(AITool(name = "list_chats"))
                    val current = (listResult.result as? com.kymjs.ai.custard.core.tools.ChatListResultData)?.currentChatId
                    if (current.isNullOrBlank()) {
                        sendResultBroadcast(
                            context = context,
                            action = replyAction,
                            packageName = replyPackage,
                            requestId = requestId,
                            success = false,
                            chatId = null,
                            aiResponse = null,
                            error = "No current chat and create_if_none=false"
                        )
                        return@launch
                    }
                }

                if (createNewChat) {
                    val params = mutableListOf<ToolParameter>()
                    if (!group.isNullOrBlank()) {
                        params += ToolParameter(name = "group", value = group)
                    }
                    chatTool.createNewChat(AITool(name = "create_new_chat", parameters = params))
                }

                val sendParams = mutableListOf(
                    ToolParameter(name = "message", value = message)
                )
                if (!createNewChat && !chatId.isNullOrBlank()) {
                    sendParams += ToolParameter(name = "chat_id", value = chatId)
                }

                val sendResult = chatTool.sendMessageToAI(AITool(name = "send_message_to_ai", parameters = sendParams))
                val resultData = sendResult.result

                val resultChatId = (resultData as? MessageSendResultData)?.chatId
                val aiResponse = (resultData as? MessageSendResultData)?.aiResponse

                sendResultBroadcast(
                    context = context,
                    action = replyAction,
                    packageName = replyPackage,
                    requestId = requestId,
                    success = sendResult.success,
                    chatId = resultChatId,
                    aiResponse = aiResponse,
                    error = sendResult.error
                )

                if (stopAfter) {
                    chatTool.stopChatService(AITool(name = "stop_chat_service"))
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to handle external chat", e)
                val replyAction = intent.getStringExtra(EXTRA_REPLY_ACTION)?.takeIf { it.isNotBlank() }
                    ?: ACTION_EXTERNAL_CHAT_RESULT
                val replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE)?.takeIf { it.isNotBlank() }
                sendResultBroadcast(
                    context = context,
                    action = replyAction,
                    packageName = replyPackage,
                    requestId = intent.getStringExtra(EXTRA_REQUEST_ID),
                    success = false,
                    chatId = null,
                    aiResponse = null,
                    error = e.message ?: "Unknown error"
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun sendResultBroadcast(
        context: Context,
        action: String,
        packageName: String?,
        requestId: String?,
        success: Boolean,
        chatId: String?,
        aiResponse: String?,
        error: String?
    ) {
        val out = Intent(action)
        if (!packageName.isNullOrBlank()) {
            out.`package` = packageName
        }
        if (!requestId.isNullOrBlank()) {
            out.putExtra(EXTRA_REQUEST_ID, requestId)
        }
        out.putExtra(EXTRA_RESULT_SUCCESS, success)
        if (!chatId.isNullOrBlank()) {
            out.putExtra(EXTRA_RESULT_CHAT_ID, chatId)
        }
        if (!aiResponse.isNullOrBlank()) {
            out.putExtra(EXTRA_RESULT_AI_RESPONSE, aiResponse)
        }
        if (!error.isNullOrBlank()) {
            out.putExtra(EXTRA_RESULT_ERROR, error)
        }
        context.sendBroadcast(out)
    }
}
