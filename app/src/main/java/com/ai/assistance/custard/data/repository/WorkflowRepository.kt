package com.ai.assistance.custard.data.repository

import android.content.Context
import android.os.Environment
import com.ai.assistance.custard.R
import com.ai.assistance.custard.util.AppLogger
import com.ai.assistance.custard.core.workflow.NodeExecutionState
import com.ai.assistance.custard.core.workflow.WorkflowExecutor
import com.ai.assistance.custard.core.workflow.WorkflowScheduler
import com.ai.assistance.custard.data.model.ExecutionStatus
import com.ai.assistance.custard.data.model.Workflow
import com.ai.assistance.custard.data.model.WorkflowExecutionRecord
import com.ai.assistance.custard.data.model.TriggerNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import android.content.Intent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流仓库
 * 负责工作流的持久化存储和管理
 */
class WorkflowRepository(private val context: Context) {
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        classDiscriminator = "__type"
    }
    
    // Lazy initialization to avoid WorkManager initialization issues during app startup
    private val scheduler by lazy { WorkflowScheduler(context) }
    
    companion object {
        private const val TAG = "WorkflowRepository"
        private const val WORKFLOW_DIR = "Custard/workflow"
        private const val EXECUTION_LOG_SUB_DIR = "_execution_logs"
        private const val MAX_EXECUTION_LOG_FILES_PER_WORKFLOW = 30

        private const val SPEECH_TRIGGER_CACHE_TTL_MS = 2000L
        private val speechTriggerLastFireAtMs = ConcurrentHashMap<String, Long>()

        @Volatile
        private var speechTriggerCachedWorkflows: List<Workflow>? = null

        @Volatile
        private var speechTriggerCachedAtMs: Long = 0L

        val workflowUpdateEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        fun notifyWorkflowsChanged() {
            speechTriggerCachedWorkflows = null
            speechTriggerCachedAtMs = 0L
            workflowUpdateEvents.tryEmit(Unit)
        }
    }
    
    /**
     * 获取工作流存储目录
     */
    private fun getWorkflowDirectory(): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val workflowDir = File(downloadDir, WORKFLOW_DIR)
        if (!workflowDir.exists()) {
            workflowDir.mkdirs()
        }
        return workflowDir
    }
    
    /**
     * 获取工作流文件
     */
    private fun getWorkflowFile(workflowId: String): File {
        return File(getWorkflowDirectory(), "$workflowId.json")
    }

    private fun getExecutionLogDirectory(workflowId: String, createIfMissing: Boolean = true): File {
        val dir = File(getWorkflowDirectory(), "$EXECUTION_LOG_SUB_DIR/$workflowId")
        if (createIfMissing && !dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun saveExecutionRecord(record: WorkflowExecutionRecord) {
        try {
            val dir = getExecutionLogDirectory(record.workflowId)
            val safeRunId = record.runId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val file = File(dir, "${record.startedAt}_$safeRunId.json")
            file.writeText(json.encodeToString(record))

            val allFiles = dir.listFiles { f -> f.isFile && f.extension == "json" }?.toList().orEmpty()
            if (allFiles.size > MAX_EXECUTION_LOG_FILES_PER_WORKFLOW) {
                allFiles.sortedBy { it.lastModified() }
                    .take(allFiles.size - MAX_EXECUTION_LOG_FILES_PER_WORKFLOW)
                    .forEach { oldFile ->
                        runCatching { oldFile.delete() }
                    }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save workflow execution record: ${record.workflowId}", e)
        }
    }

    private fun hasScheduleTrigger(workflow: Workflow): Boolean {
        return workflow.nodes.filterIsInstance<TriggerNode>().any { it.triggerType == "schedule" }
    }
    
    /**
     * 获取所有工作流
     */
    suspend fun getAllWorkflows(): Result<List<Workflow>> = withContext(Dispatchers.IO) {
        try {
            val workflowDir = getWorkflowDirectory()
            val workflows = workflowDir.listFiles { file ->
                file.isFile && file.extension == "json"
            }?.mapNotNull { file ->
                try {
                    val content = file.readText()
                    json.decodeFromString<Workflow>(content)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to parse workflow file: ${file.name}", e)
                    null
                }
            }?.sortedByDescending { it.updatedAt } ?: emptyList()
            
            Result.success(workflows)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get all workflows", e)
            Result.failure(e)
        }
    }
    
    /**
     * 根据ID获取工作流
     */
    suspend fun getWorkflowById(id: String): Result<Workflow?> = withContext(Dispatchers.IO) {
        try {
            val file = getWorkflowFile(id)
            if (!file.exists()) {
                return@withContext Result.success(null)
            }
            
            val content = file.readText()
            val workflow = json.decodeFromString<Workflow>(content)
            Result.success(workflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get workflow by id: $id", e)
            Result.failure(e)
        }
    }

    suspend fun getLatestExecutionRecord(workflowId: String): Result<WorkflowExecutionRecord?> = withContext(Dispatchers.IO) {
        try {
            val dir = getExecutionLogDirectory(workflowId, createIfMissing = false)
            if (!dir.exists()) {
                return@withContext Result.success(null)
            }
            val latestFile =
                dir.listFiles { f -> f.isFile && f.extension == "json" }
                    ?.maxByOrNull { it.lastModified() }
                    ?: return@withContext Result.success(null)

            val content = latestFile.readText()
            val record = json.decodeFromString<WorkflowExecutionRecord>(content)
            Result.success(record)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get latest execution record for workflow: $workflowId", e)
            Result.failure(e)
        }
    }
    
    /**
     * 创建工作流
     */
    suspend fun createWorkflow(workflow: Workflow): Result<Workflow> = withContext(Dispatchers.IO) {
        try {
            val file = getWorkflowFile(workflow.id)
            val content = json.encodeToString(workflow)
            file.writeText(content)
            
            AppLogger.d(TAG, "Workflow created: ${workflow.id}")
            
            // Schedule if enabled and has schedule trigger
            if (workflow.enabled && hasScheduleTrigger(workflow)) {
                scheduleWorkflow(workflow.id)
            }

            notifyWorkflowsChanged()
            
            Result.success(workflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create workflow", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新工作流
     */
    suspend fun updateWorkflow(workflow: Workflow): Result<Workflow> = withContext(Dispatchers.IO) {
        try {
            val updatedWorkflow = workflow.copy(updatedAt = System.currentTimeMillis())
            val file = getWorkflowFile(updatedWorkflow.id)
            val content = json.encodeToString(updatedWorkflow)
            file.writeText(content)
            
            AppLogger.d(TAG, "Workflow updated: ${updatedWorkflow.id}")
            
            // Reschedule workflow only when it has a schedule trigger
            if (updatedWorkflow.enabled && hasScheduleTrigger(updatedWorkflow)) {
                rescheduleWorkflow(updatedWorkflow.id)
            }

            notifyWorkflowsChanged()
            
            Result.success(updatedWorkflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update workflow", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除工作流
     */
    suspend fun deleteWorkflow(id: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Cancel schedule first
            unscheduleWorkflow(id)
            
            val file = getWorkflowFile(id)
            val deleted = if (file.exists()) {
                file.delete()
            } else {
                false
            }

            runCatching {
                val logDir = getExecutionLogDirectory(id, createIfMissing = false)
                if (logDir.exists()) {
                    logDir.deleteRecursively()
                }
            }
            
            AppLogger.d(TAG, "Workflow deleted: $id, success: $deleted")
            if (deleted) {
                notifyWorkflowsChanged()
            }
            Result.success(deleted)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete workflow", e)
            Result.failure(e)
        }
    }
    
    /**
     * 触发工作流执行
     * @param id 工作流ID
     * @param triggerNodeId 指定要触发的节点ID，如果为null则触发所有触发节点
     */
    suspend fun triggerWorkflow(
        id: String,
        triggerNodeId: String? = null,
        triggerExtras: Map<String, String> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val workflowResult = getWorkflowById(id)
            val workflow = workflowResult.getOrNull()

            if (workflow == null) {
                return@withContext Result.failure(Exception(context.getString(R.string.workflow_not_exist, id)))
            }

            if (!workflow.enabled) {
                return@withContext Result.failure(Exception(context.getString(R.string.workflow_disabled, workflow.name)))
            }

            AppLogger.d(TAG, "Triggering workflow: ${workflow.name} (${workflow.id})")
            if (triggerNodeId != null) {
                AppLogger.d(TAG, "With specific trigger node: $triggerNodeId")
            }
            
            // 更新为执行中状态
            updateExecutionStatus(id, ExecutionStatus.RUNNING, System.currentTimeMillis())
            
            // 创建执行器并执行工作流
            val executor = WorkflowExecutor(context)
            val result = executor.executeWorkflow(workflow, triggerNodeId, triggerExtras) { nodeId, state ->
                // 这里可以通过 Flow 或其他机制传递状态更新到 UI
                AppLogger.d(TAG, "Node $nodeId state: $state")
            }
            result.executionRecord?.let { saveExecutionRecord(it) }
            
            // 更新执行统计
            val executionStatus = if (result.success) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED
            updateExecutionStatistics(id, executionStatus, result.executionTime)
            
            if (result.success) {
                Result.success(context.getString(R.string.workflow_execute_success, workflow.name))
            } else {
                Result.failure(Exception(result.message))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to trigger workflow", e)
            // 执行失败时也更新状态
            updateExecutionStatus(id, ExecutionStatus.FAILED, System.currentTimeMillis())
            Result.failure(e)
        }
    }
    
    /**
     * 触发工作流执行（带状态回调）
     * @param id 工作流ID
     * @param triggerNodeId 指定要触发的节点ID，如果为null则触发所有触发节点
     * @param onNodeStateChange 节点状态变化回调
     */
    suspend fun triggerWorkflowWithCallback(
        id: String,
        triggerNodeId: String? = null,
        triggerExtras: Map<String, String> = emptyMap(),
        onNodeStateChange: (nodeId: String, state: NodeExecutionState) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val workflowResult = getWorkflowById(id)
            val workflow = workflowResult.getOrNull()

            if (workflow == null) {
                return@withContext Result.failure(Exception(context.getString(R.string.workflow_not_exist, id)))
            }

            if (!workflow.enabled) {
                return@withContext Result.failure(Exception(context.getString(R.string.workflow_disabled, workflow.name)))
            }

            AppLogger.d(TAG, "Triggering workflow with callback: ${workflow.name} (${workflow.id})")
            if (triggerNodeId != null) {
                AppLogger.d(TAG, "With specific trigger node: $triggerNodeId")
            }
            
            // 更新为执行中状态
            updateExecutionStatus(id, ExecutionStatus.RUNNING, System.currentTimeMillis())
            
            // 创建执行器并执行工作流
            val executor = WorkflowExecutor(context)
            val result = executor.executeWorkflow(workflow, triggerNodeId, triggerExtras, onNodeStateChange)
            result.executionRecord?.let { saveExecutionRecord(it) }
            
            // 更新执行统计
            val executionStatus = if (result.success) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED
            updateExecutionStatistics(id, executionStatus, result.executionTime)
            
            if (result.success) {
                Result.success(context.getString(R.string.workflow_execute_success, workflow.name))
            } else {
                Result.failure(Exception(result.message))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to trigger workflow with callback", e)
            // 执行失败时也更新状态
            updateExecutionStatus(id, ExecutionStatus.FAILED, System.currentTimeMillis())
            Result.failure(e)
        }
    }
    
    /**
     * 更新工作流执行状态（仅状态和时间）
     */
    private suspend fun updateExecutionStatus(
        id: String,
        status: ExecutionStatus,
        executionTime: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val workflowResult = getWorkflowById(id)
            val workflow = workflowResult.getOrNull() ?: return@withContext
            
            val updatedWorkflow = workflow.copy(
                lastExecutionStatus = status,
                lastExecutionTime = executionTime
            )
            
            val file = getWorkflowFile(id)
            val content = json.encodeToString(updatedWorkflow)
            file.writeText(content)
            
            AppLogger.d(TAG, "Workflow execution status updated: $id -> $status")
            notifyWorkflowsChanged()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update execution status", e)
        }
    }
    
    /**
     * 更新工作流执行统计信息
     */
    private suspend fun updateExecutionStatistics(
        id: String,
        status: ExecutionStatus,
        executionTime: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val workflowResult = getWorkflowById(id)
            val workflow = workflowResult.getOrNull() ?: return@withContext
            
            val updatedWorkflow = workflow.copy(
                lastExecutionStatus = status,
                lastExecutionTime = executionTime,
                totalExecutions = workflow.totalExecutions + 1,
                successfulExecutions = if (status == ExecutionStatus.SUCCESS) {
                    workflow.successfulExecutions + 1
                } else {
                    workflow.successfulExecutions
                }
            )
            
            val file = getWorkflowFile(id)
            val content = json.encodeToString(updatedWorkflow)
            file.writeText(content)
            
            AppLogger.d(TAG, "Workflow execution statistics updated: $id (total: ${updatedWorkflow.totalExecutions}, success: ${updatedWorkflow.successfulExecutions})")
            notifyWorkflowsChanged()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update execution statistics", e)
        }
    }
    
    /**
     * Schedule a workflow
     */
    fun scheduleWorkflow(id: String): Boolean {
        return try {
            val workflowResult = kotlinx.coroutines.runBlocking { getWorkflowById(id) }
            val workflow = workflowResult.getOrNull()
            
            if (workflow == null) {
                AppLogger.w(TAG, "Workflow not found for scheduling: $id")
                return false
            }
            
            if (!workflow.enabled) {
                AppLogger.d(TAG, "Workflow is disabled, not scheduling: $id")
                return false
            }

            if (!hasScheduleTrigger(workflow)) {
                return false
            }
            
            scheduler.scheduleWorkflow(workflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to schedule workflow: $id", e)
            false
        }
    }
    
    /**
     * Unschedule a workflow
     */
    fun unscheduleWorkflow(id: String) {
        try {
            scheduler.cancelWorkflow(id)
            AppLogger.d(TAG, "Workflow unscheduled: $id")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to unschedule workflow: $id", e)
        }
    }
    
    /**
     * Reschedule a workflow (cancel + schedule)
     */
    fun rescheduleWorkflow(id: String): Boolean {
        unscheduleWorkflow(id)
        return scheduleWorkflow(id)
    }
    
    /**
     * Check if workflow is scheduled
     */
    suspend fun isWorkflowScheduled(id: String): Boolean {
        return try {
            scheduler.isWorkflowScheduled(id)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to check schedule status: $id", e)
            false
        }
    }
    
    /**
     * Get next execution time for a workflow
     */
    suspend fun getNextExecutionTime(id: String): Long? = withContext(Dispatchers.IO) {
        try {
            val workflowResult = getWorkflowById(id)
            val workflow = workflowResult.getOrNull() ?: return@withContext null
            scheduler.getNextExecutionTime(workflow)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get next execution time: $id", e)
            null
        }
    }


    /**
     * Finds and triggers workflows based on a Tasker event.
     * It checks all enabled workflows for a Tasker trigger node whose configuration matches the event data.
     *
     * @param params The list of parameters received from Tasker.
     */
    suspend fun triggerWorkflowsByTaskerEvent(params: List<String>?) = withContext(Dispatchers.IO) {
        if (params.isNullOrEmpty()) return@withContext

        AppLogger.d(TAG, "Checking for Tasker-triggered workflows with params: $params")
        val workflows = getAllWorkflows().getOrNull() ?: return@withContext

        coroutineScope {
            workflows.filter { it.enabled }.forEach { workflow ->
                workflow.nodes.forEach { node ->
                    if (node is TriggerNode && node.triggerType == "tasker") {
                        // Matching logic: The node's config expects a "command".
                        // It checks if any of the parameters from Tasker exactly matches this command.
                        // Example config: `{"command": "start_meeting"}`.
                        // This will match if any of the params from Tasker is "start_meeting" (case-insensitive).
                        val command = node.triggerConfig["command"]
                        if (command != null && params.any { it.equals(command, ignoreCase = true) }) {
                            AppLogger.d(TAG, "Tasker trigger matched for workflow '${workflow.name}' on node '${node.name}'. Triggering.")
                            launch {
                                triggerWorkflow(workflow.id, node.id)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds and triggers workflows based on a received Intent.
     * It checks all enabled workflows for an Intent trigger node whose configuration matches the Intent's action.
     *
     * @param intent The Intent received by the BroadcastReceiver.
     */
    suspend fun triggerWorkflowsByIntentEvent(intent: Intent) = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Checking for Intent-triggered workflows for action: ${intent.action}")
        val workflows = getAllWorkflows().getOrNull() ?: return@withContext

        val extras: Map<String, String> = try {
            val bundle = intent.extras
            if (bundle == null) {
                emptyMap()
            } else {
                bundle.keySet().associateWith { key ->
                    bundle.get(key)?.toString() ?: ""
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }

        coroutineScope {
            workflows.filter { it.enabled }.forEach { workflow ->
                workflow.nodes.forEach { node ->
                    if (node is TriggerNode && node.triggerType == "intent") {
                        // Match based on the Intent action.
                        // Example config: `{"action": "com.example.MY_ACTION"}`.
                        val expectedAction = node.triggerConfig["action"]
                        if (expectedAction != null && expectedAction.equals(intent.action, ignoreCase = true)) {
                            AppLogger.d(TAG, "Intent trigger matched for workflow '${workflow.name}' on node '${node.name}'. Triggering.")
                            launch {
                                triggerWorkflow(workflow.id, node.id, extras)
                            }
                        }
                    }
                }
            }
        }
    }
    suspend fun triggerWorkflowsBySpeechEvent(text: String, isFinal: Boolean) = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return@withContext

        val now = System.currentTimeMillis()
        val cached = speechTriggerCachedWorkflows
        val workflows = if (cached != null && now - speechTriggerCachedAtMs < SPEECH_TRIGGER_CACHE_TTL_MS) {
            cached
        } else {
            val loaded = getAllWorkflows().getOrNull() ?: emptyList()
            speechTriggerCachedWorkflows = loaded
            speechTriggerCachedAtMs = now
            loaded
        }

        fun parseBoolean(value: String?, defaultValue: Boolean): Boolean {
            val normalized = value?.trim()?.lowercase() ?: return defaultValue
            return when (normalized) {
                "true", "1", "yes", "y", "on" -> true
                "false", "0", "no", "n", "off" -> false
                else -> defaultValue
            }
        }

        coroutineScope {
            workflows.filter { it.enabled }.forEach { workflow ->
                workflow.nodes.forEach { node ->
                    if (node !is TriggerNode || node.triggerType != "speech") return@forEach

                    val pattern = node.triggerConfig["pattern"].orEmpty()
                    if (pattern.isBlank()) return@forEach

                    val requireFinal = parseBoolean(node.triggerConfig["require_final"], true)
                    if (requireFinal && !isFinal) return@forEach

                    val ignoreCase = parseBoolean(node.triggerConfig["ignore_case"], true)
                    val cooldownMs = node.triggerConfig["cooldown_ms"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 3000L
                    val cooldownKey = "${workflow.id}:${node.id}"

                    val lastFireAt = speechTriggerLastFireAtMs[cooldownKey] ?: 0L
                    if (cooldownMs > 0 && now - lastFireAt < cooldownMs) return@forEach

                    val matches = try {
                        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                        Regex(pattern, options).containsMatchIn(trimmed)
                    } catch (_: Exception) {
                        false
                    }

                    if (matches) {
                        speechTriggerLastFireAtMs[cooldownKey] = now
                        AppLogger.d(TAG, "Speech trigger matched for workflow '${workflow.name}' on node '${node.name}'. Triggering.")
                        launch {
                            triggerWorkflow(workflow.id, node.id)
                        }
                    }
                }
            }
        }
    }
}

