package com.ai.assistance.custard.ui.features.workflow.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.custard.R
import com.ai.assistance.custard.core.config.SystemToolPrompts
import com.ai.assistance.custard.core.tools.AIToolHandler
import com.ai.assistance.custard.data.model.Workflow
import com.ai.assistance.custard.data.model.WorkflowNode
import com.ai.assistance.custard.data.model.TriggerNode
import com.ai.assistance.custard.data.model.ExecuteNode
import com.ai.assistance.custard.data.model.ConditionNode
import com.ai.assistance.custard.data.model.ConditionOperator
import com.ai.assistance.custard.data.model.LogicNode
import com.ai.assistance.custard.data.model.LogicOperator
import com.ai.assistance.custard.data.model.ExtractNode
import com.ai.assistance.custard.data.model.ExtractMode
import com.ai.assistance.custard.data.model.ParameterValue
import com.ai.assistance.custard.data.model.ToolParameterSchema
import com.ai.assistance.custard.data.model.WorkflowExecutionRecord
import com.ai.assistance.custard.ui.components.CustomScaffold
import com.ai.assistance.custard.ui.features.workflow.viewmodel.WorkflowViewModel
import com.ai.assistance.custard.ui.features.workflow.components.GridWorkflowCanvas
import com.ai.assistance.custard.ui.features.workflow.components.ConnectionMenuDialog
import com.ai.assistance.custard.ui.features.workflow.components.NodeActionMenuDialog
import com.ai.assistance.custard.ui.features.workflow.components.ScheduleConfigDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun ConditionOperator.toDisplayText(): String {
    return when (this) {
        ConditionOperator.EQ -> "="
        ConditionOperator.NE -> "!="
        ConditionOperator.GT -> ">"
        ConditionOperator.GTE -> ">="
        ConditionOperator.LT -> "<"
        ConditionOperator.LTE -> "<="
        ConditionOperator.CONTAINS -> stringResource(R.string.workflow_condition_contains)
        ConditionOperator.NOT_CONTAINS -> stringResource(R.string.workflow_condition_not_contains)
        ConditionOperator.IN -> "∈"
        ConditionOperator.NOT_IN -> "∉"
    }
}

@Composable
private fun LogicOperator.toDisplayText(): String {
    return when (this) {
        LogicOperator.AND -> "&&"
        LogicOperator.OR -> "||"
    }
}

@Composable
private fun ExtractMode.toDisplayText(): String {
    return when (this) {
        ExtractMode.REGEX -> stringResource(R.string.workflow_extract_mode_regex)
        ExtractMode.JSON -> "JSON"
        ExtractMode.SUB -> stringResource(R.string.workflow_extract_mode_sub)
        ExtractMode.CONCAT -> stringResource(R.string.workflow_extract_mode_concat)
        ExtractMode.RANDOM_INT -> stringResource(R.string.workflow_extract_mode_random_int)
        ExtractMode.RANDOM_STRING -> stringResource(R.string.workflow_extract_mode_random_string)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowDetailScreen(
    workflowId: String,
    onNavigateBack: () -> Unit,
    viewModel: WorkflowViewModel = viewModel()
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTriggerResult by remember { mutableStateOf<String?>(null) }
    var showAddNodeDialog by remember { mutableStateOf(false) }
    var showDeleteNodeDialog by remember { mutableStateOf<String?>(null) }
    var showNodeActionMenu by remember { mutableStateOf<String?>(null) }
    var showConnectionMenu by remember { mutableStateOf<String?>(null) }
    var showEditNodeDialog by remember { mutableStateOf<WorkflowNode?>(null) }
    var isFabMenuExpanded by remember { mutableStateOf(false) }
    var showExecutionLogDialog by remember { mutableStateOf(false) }
    var showExecutionLogsForNodeId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workflowId) {
        viewModel.loadWorkflow(workflowId)
    }

    val workflow = viewModel.currentWorkflow
    val nodeExecutionStates by viewModel.nodeExecutionStates.collectAsState()
    val latestExecutionRecord = viewModel.latestExecutionRecord

    CustomScaffold(
        floatingActionButton = {
            if (workflow != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Animated secondary actions
                    AnimatedVisibility(
                        visible = isFabMenuExpanded,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (workflow.enabled) {
                                SpeedDialAction(
                                    text = stringResource(R.string.workflow_action_trigger),
                                    icon = Icons.Default.PlayArrow,
                                    onClick = {
                                        viewModel.triggerWorkflow(workflowId) { result -> showTriggerResult = result }
                                        isFabMenuExpanded = false
                                    }
                                )
                            }
                            SpeedDialAction(
                                text = stringResource(R.string.workflow_view_logs),
                                icon = Icons.Default.Call,
                                onClick = {
                                    viewModel.loadLatestExecutionRecord(workflowId)
                                    showExecutionLogsForNodeId = null
                                    showExecutionLogDialog = true
                                    isFabMenuExpanded = false
                                }
                            )
                            SpeedDialAction(
                                text = stringResource(R.string.workflow_action_add_node),
                                icon = Icons.Default.Add,
                                onClick = {
                                    showAddNodeDialog = true
                                    isFabMenuExpanded = false
                                }
                            )
                            SpeedDialAction(
                                text = stringResource(R.string.workflow_action_edit_workflow),
                                icon = Icons.Default.Edit,
                                onClick = {
                                    showEditDialog = true
                                    isFabMenuExpanded = false
                                }
                            )
                            SpeedDialAction(
                                text = stringResource(R.string.workflow_delete),
                                icon = Icons.Default.Delete,
                                onClick = {
                                    showDeleteDialog = true
                                    isFabMenuExpanded = false
                                },
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    // Main FAB
                    FloatingActionButton(
                        onClick = { isFabMenuExpanded = !isFabMenuExpanded },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        val rotation by animateFloatAsState(targetValue = if (isFabMenuExpanded) 45f else 0f, label = "fab_icon_rotation")
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.workflow_open_action_menu),
                            modifier = Modifier.rotate(rotation)
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)) {
            when {
                viewModel.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                workflow == null -> {
                    Text(
                        text = stringResource(R.string.workflow_not_found),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 网格画布
                        if (workflow.nodes.isEmpty()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "📋",
                                        style = MaterialTheme.typography.displayMedium
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = stringResource(R.string.workflow_nodes_empty),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.workflow_nodes_empty_hint_add),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        } else {
                            GridWorkflowCanvas(
                                nodes = workflow.nodes,
                                connections = workflow.connections,
                                nodeExecutionStates = nodeExecutionStates,
                                onNodePositionChanged = { nodeId, x, y ->
                                    viewModel.updateNodePosition(workflowId, nodeId, x, y)
                                },
                                onNodeLongPress = { nodeId ->
                                    // 长按节点显示操作菜单
                                    showNodeActionMenu = nodeId
                                },
                                onNodeClick = { nodeId ->
                                    // 点击节点不做任何操作（避免拖动时误触发）
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // 编辑对话框
            if (showEditDialog && workflow != null) {
                EditWorkflowDialog(
                    workflow = workflow,
                    onDismiss = { showEditDialog = false },
                    onSave = { name, description, enabled ->
                        viewModel.updateWorkflow(
                            workflow.copy(
                                name = name,
                                description = description,
                                enabled = enabled
                            )
                        ) {
                            showEditDialog = false
                        }
                    }
                )
            }

            // 删除确认对话框
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text(stringResource(R.string.workflow_confirm_delete_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.workflow_confirm_delete_workflow_message,
                                workflow?.name.orEmpty()
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteWorkflow(workflowId) {
                                    showDeleteDialog = false
                                    onNavigateBack()
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.delete_action))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text(stringResource(R.string.cancel_action))
                        }
                    }
                )
            }

            // 触发结果提示
            showTriggerResult?.let { result ->
                AlertDialog(
                    onDismissRequest = { showTriggerResult = null },
                    title = { Text(stringResource(R.string.workflow_execution_result_title)) },
                    text = { Text(result) },
                    confirmButton = {
                        TextButton(onClick = { showTriggerResult = null }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                )
            }

            if (showExecutionLogDialog) {
                val targetNode = showExecutionLogsForNodeId?.let { targetNodeId ->
                    workflow?.nodes?.find { it.id == targetNodeId }
                }
                WorkflowExecutionLogDialog(
                    record = latestExecutionRecord,
                    nodeId = targetNode?.id,
                    nodeName = targetNode?.name,
                    onDismiss = {
                        showExecutionLogDialog = false
                        showExecutionLogsForNodeId = null
                    }
                )
            }

            // 错误提示
            viewModel.error?.let { error ->
                AlertDialog(
                    onDismissRequest = { viewModel.clearError() },
                    title = { Text(stringResource(R.string.error_title)) },
                    text = { Text(error) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                )
            }

            // 添加节点对话框
            if (showAddNodeDialog && workflow != null) {
                NodeDialog(
                    node = null, // 创建模式
                    workflow = workflow,
                    onDismiss = { showAddNodeDialog = false },
                    onConfirm = { node ->
                        viewModel.addNode(workflowId, node) {
                            showAddNodeDialog = false
                        }
                    }
                )
            }

            // 删除节点确认对话框
            showDeleteNodeDialog?.let { nodeId ->
                val node = workflow?.nodes?.find { it.id == nodeId }
                AlertDialog(
                    onDismissRequest = { showDeleteNodeDialog = null },
                    title = { Text(stringResource(R.string.workflow_confirm_delete_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.workflow_confirm_delete_node_message,
                                node?.name.orEmpty()
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteNode(workflowId, nodeId) {
                                    showDeleteNodeDialog = null
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.delete_action))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteNodeDialog = null }) {
                            Text(stringResource(R.string.cancel_action))
                        }
                    }
                )
            }

            // 节点操作菜单对话框
            showNodeActionMenu?.let { nodeId ->
                val node = workflow?.nodes?.find { it.id == nodeId }
                if (node != null) {
                    NodeActionMenuDialog(
                        nodeName = node.name,
                        onEdit = {
                            showEditNodeDialog = node
                            showNodeActionMenu = null
                        },
                        onViewLogs = {
                            viewModel.loadLatestExecutionRecord(workflowId)
                            showExecutionLogsForNodeId = nodeId
                            showExecutionLogDialog = true
                            showNodeActionMenu = null
                        },
                        onConnect = {
                            showConnectionMenu = nodeId
                            showNodeActionMenu = null
                        },
                        onDelete = {
                            showDeleteNodeDialog = nodeId
                            showNodeActionMenu = null
                        },
                        onDismiss = {
                            showNodeActionMenu = null
                        }
                    )
                }
            }

            // 节点编辑对话框
            if (workflow != null) {
                showEditNodeDialog?.let { node ->
                    NodeDialog(
                        node = node, // 编辑模式
                        workflow = workflow,
                        onDismiss = { showEditNodeDialog = null },
                        onConfirm = { updatedNode ->
                            viewModel.updateNode(workflowId, updatedNode) {
                                showEditNodeDialog = null
                            }
                        }
                    )
                }
            }

            // 连接菜单对话框
            showConnectionMenu?.let { sourceNodeId ->
                val sourceNode = workflow?.nodes?.find { it.id == sourceNodeId }
                if (sourceNode != null && workflow != null) {
                    ConnectionMenuDialog(
                        sourceNode = sourceNode,
                        allNodes = workflow.nodes,
                        existingConnections = workflow.connections,
                        onCreateConnection = { targetNodeId ->
                            viewModel.createConnection(workflowId, sourceNodeId, targetNodeId) {
                                // 连接创建成功，保持对话框打开以便继续操作
                            }
                        },
                        onDeleteConnection = { connectionId ->
                            viewModel.deleteConnection(workflowId, connectionId) {
                                // 连接删除成功
                            }
                        },
                        onUpdateConnectionCondition = { connectionId, condition ->
                            viewModel.updateConnectionCondition(workflowId, connectionId, condition) {
                                // 条件更新成功
                            }
                        },
                        onDismiss = { showConnectionMenu = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedDialAction(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = containerColor,
            contentColor = contentColor
        ) {
            Icon(icon, contentDescription = text)
        }
    }
}

@Composable
private fun WorkflowExecutionLogDialog(
    record: WorkflowExecutionRecord?,
    nodeId: String? = null,
    nodeName: String? = null,
    onDismiss: () -> Unit
) {
    val dateTimeFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val filteredLogs = remember(record, nodeId) {
        val allLogs = record?.logs.orEmpty()
        if (nodeId.isNullOrBlank()) {
            allLogs
        } else {
            allLogs.filter { it.nodeId == nodeId }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (nodeName.isNullOrBlank()) {
                    stringResource(R.string.workflow_execution_log_title)
                } else {
                    stringResource(R.string.workflow_execution_log_node_title, nodeName)
                }
            )
        },
        text = {
            if (record == null) {
                Text(stringResource(R.string.workflow_execution_log_empty))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${dateTimeFormatter.format(Date(record.startedAt))} - ${if (record.success) stringResource(R.string.operation_success) else stringResource(R.string.operation_failed)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (filteredLogs.isEmpty()) {
                        Text(
                            text = if (nodeName.isNullOrBlank()) {
                                stringResource(R.string.workflow_execution_log_empty)
                            } else {
                                stringResource(R.string.workflow_execution_log_empty_for_node, nodeName)
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(filteredLogs) { item ->
                                Text(
                                    text = "${timeFormatter.format(Date(item.timestamp))} [${item.level}] ${item.message}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

/**
 * 参数配置数据类
 */
data class ParameterConfig(
    val key: String,
    val isReference: Boolean, // true表示引用节点，false表示静态值
    val value: String // 静态值或节点ID
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDialog(
    node: WorkflowNode? = null, // null 表示创建新节点，非 null 表示编辑
    workflow: Workflow, // 用于获取前置节点信息
    onDismiss: () -> Unit,
    onConfirm: (WorkflowNode) -> Unit
) {
    // 判断是编辑还是创建模式
    val isEditMode = node != null
    
    // 初始化节点类型
    val initialNodeType = when (node) {
        is TriggerNode -> "trigger"
        is ExecuteNode -> "execute"
        is ConditionNode -> "condition"
        is LogicNode -> "logic"
        is ExtractNode -> "extract"
        else -> "trigger"
    }
    
    var nodeType by remember { mutableStateOf(initialNodeType) }
    var name by remember { mutableStateOf(node?.name ?: "") }
    var description by remember { mutableStateOf(node?.description ?: "") }
    var expanded by remember { mutableStateOf(false) }

    // 执行节点配置
    var actionType by remember {
        mutableStateOf(if (node is ExecuteNode) node.actionType else "")
    }
    var actionTypeExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val toolHandler = remember(context) { AIToolHandler.getInstance(context) }
    val packageManager = remember(context) { toolHandler.getOrCreatePackageManager() }
    val allToolNames = remember(context) {
        toolHandler.registerDefaultTools()
        toolHandler.getAllToolNames()
    }
    val filteredToolNames = remember(actionType, allToolNames) {
        val query = actionType.trim()
        val filtered =
            if (query.isBlank()) {
                allToolNames
            } else {
                allToolNames.filter { it.contains(query, ignoreCase = true) }
            }
        filtered.take(50)
    }
    
    // 将 actionConfig (Map<String, ParameterValue>) 转换为可变的参数配置列表
    val initialActionConfigPairs = if (node is ExecuteNode) {
        node.actionConfig.map { (key, paramValue) ->
            when (paramValue) {
                is com.ai.assistance.custard.data.model.ParameterValue.StaticValue -> 
                    ParameterConfig(key, false, paramValue.value)
                is com.ai.assistance.custard.data.model.ParameterValue.NodeReference -> 
                    ParameterConfig(key, true, paramValue.nodeId)
            }
        }
    } else {
        emptyList()
    }
    var actionConfigPairs by remember { mutableStateOf(initialActionConfigPairs) }

    var toolDescription by remember { mutableStateOf<String?>(null) }
    var toolParameterSchemas by remember { mutableStateOf<List<ToolParameterSchema>>(emptyList()) }
    val toolParameterSchemasByName = remember(toolParameterSchemas) {
        toolParameterSchemas.associateBy { it.name }
    }

    LaunchedEffect(actionType, nodeType) {
        if (nodeType != "execute") {
            toolDescription = null
            toolParameterSchemas = emptyList()
            return@LaunchedEffect
        }

        val toolName = actionType.trim()
        if (toolName.isBlank()) {
            toolDescription = null
            toolParameterSchemas = emptyList()
            return@LaunchedEffect
        }

        var schemas: List<ToolParameterSchema> = emptyList()
        var description: String? = null

        if (toolName.contains(":")) {
            val parts = toolName.split(":", limit = 2)
            if (parts.size == 2) {
                val packageName = parts[0].trim()
                val packageToolName = parts[1].trim()

                if (packageName.isNotBlank() && packageToolName.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        try {
                            if (!packageManager.isPackageImported(packageName)) {
                                packageManager.importPackage(packageName)
                            }
                            packageManager.usePackage(packageName)
                        } catch (_: Exception) {
                        }
                    }

                    val effectivePackage = try {
                        packageManager.getEffectivePackageTools(packageName)
                    } catch (_: Exception) {
                        null
                    }

                    val matchedTool = effectivePackage?.tools?.find { it.name == packageToolName }
                    description = matchedTool?.description?.resolve(context)
                    schemas =
                        matchedTool?.parameters?.map { param ->
                            ToolParameterSchema(
                                name = param.name,
                                type = param.type,
                                description = param.description.resolve(context),
                                required = param.required,
                                default = null
                            )
                        }
                            ?: emptyList()
                }
            }
        } else {
            val currentLocale: Locale =
                context.resources.configuration.locales.get(0) ?: Locale.getDefault()
            val isChinese = currentLocale.language.equals("zh", ignoreCase = true)

            val internalTool =
                (if (isChinese) {
                    SystemToolPrompts.getAllCategoriesCn()
                } else {
                    SystemToolPrompts.getAllCategoriesEn()
                })
                    .flatMap { it.tools }
                    .find { it.name == toolName }
            description = internalTool?.description
            schemas = internalTool?.parametersStructured ?: emptyList()
        }

        toolDescription = description
        toolParameterSchemas = schemas

        if (schemas.isNotEmpty()) {
            val existingParams = actionConfigPairs.toList()
            val existingByKey = existingParams.filter { it.key.isNotBlank() }.associateBy { it.key }
            val schemaKeys = schemas.map { it.name }.toSet()

            val merged = mutableListOf<ParameterConfig>()
            schemas.forEach { schema ->
                val existing = existingByKey[schema.name]
                val defaultValue =
                    schema.default
                        ?.trim()
                        ?.let { d ->
                            if (d.length >= 2 && d.startsWith("\"") && d.endsWith("\"")) {
                                d.substring(1, d.length - 1)
                            } else {
                                d
                            }
                        }
                        ?: ""

                merged.add(
                    ParameterConfig(
                        key = schema.name,
                        isReference = existing?.isReference ?: false,
                        value = existing?.value ?: defaultValue
                    )
                )
            }

            existingParams.filter { it.key.isNotBlank() && !schemaKeys.contains(it.key) }.forEach { merged.add(it) }
            existingParams.filter { it.key.isBlank() }.forEach { merged.add(it) }
            actionConfigPairs = merged
        }
    }

    val availableReferenceNodes = if (node != null) {
        workflow.nodes.filter { it.id != node.id }
    } else {
        workflow.nodes
    }

    // 触发节点配置
    var triggerType by remember {
        mutableStateOf(if (node is TriggerNode) node.triggerType else "manual")
    }
    var triggerTypeExpanded by remember { mutableStateOf(false) }
    var triggerConfig by remember {
        mutableStateOf(
            if (node is TriggerNode && node.triggerConfig.isNotEmpty()) {
                org.json.JSONObject(node.triggerConfig).toString(2)
            } else ""
        )
    }

    val initialConditionLeft = if (node is ConditionNode) node.left else ParameterValue.StaticValue("")
    val initialConditionRight = if (node is ConditionNode) node.right else ParameterValue.StaticValue("")
    var conditionLeftIsReference by remember { mutableStateOf(initialConditionLeft is ParameterValue.NodeReference) }
    var conditionLeftValue by remember {
        mutableStateOf(
            when (initialConditionLeft) {
                is ParameterValue.StaticValue -> initialConditionLeft.value
                is ParameterValue.NodeReference -> initialConditionLeft.nodeId
            }
        )
    }
    var conditionRightIsReference by remember { mutableStateOf(initialConditionRight is ParameterValue.NodeReference) }
    var conditionRightValue by remember {
        mutableStateOf(
            when (initialConditionRight) {
                is ParameterValue.StaticValue -> initialConditionRight.value
                is ParameterValue.NodeReference -> initialConditionRight.nodeId
            }
        )
    }
    var conditionOperator by remember {
        mutableStateOf(if (node is ConditionNode) node.operator else ConditionOperator.EQ)
    }
    var conditionOperatorExpanded by remember { mutableStateOf(false) }

    var logicOperator by remember {
        mutableStateOf(if (node is LogicNode) node.operator else LogicOperator.AND)
    }
    var logicOperatorExpanded by remember { mutableStateOf(false) }

    val initialExtractSource = if (node is ExtractNode) node.source else ParameterValue.StaticValue("")
    var extractSourceIsReference by remember { mutableStateOf(initialExtractSource is ParameterValue.NodeReference) }
    var extractSourceValue by remember {
        mutableStateOf(
            when (initialExtractSource) {
                is ParameterValue.StaticValue -> initialExtractSource.value
                is ParameterValue.NodeReference -> initialExtractSource.nodeId
            }
        )
    }

    val initialExtractOthers = if (node is ExtractNode) node.others else emptyList()
    var extractOthers by remember {
        mutableStateOf(
            initialExtractOthers.map { other ->
                when (other) {
                    is ParameterValue.StaticValue -> ParameterConfig("", false, other.value)
                    is ParameterValue.NodeReference -> ParameterConfig("", true, other.nodeId)
                }
            }
        )
    }

    var extractMode by remember { mutableStateOf(if (node is ExtractNode) node.mode else ExtractMode.REGEX) }
    var extractModeExpanded by remember { mutableStateOf(false) }
    var extractExpression by remember { mutableStateOf(if (node is ExtractNode) node.expression else "") }
    var extractGroupText by remember { mutableStateOf(if (node is ExtractNode) node.group.toString() else "0") }
    var extractDefaultValue by remember { mutableStateOf(if (node is ExtractNode) node.defaultValue else "") }

    var extractStartIndexText by remember { mutableStateOf(if (node is ExtractNode) node.startIndex.toString() else "0") }
    var extractLengthText by remember { mutableStateOf(if (node is ExtractNode) node.length.toString() else "-1") }
    var extractRandomMinText by remember { mutableStateOf(if (node is ExtractNode) node.randomMin.toString() else "0") }
    var extractRandomMaxText by remember { mutableStateOf(if (node is ExtractNode) node.randomMax.toString() else "100") }
    var extractRandomStringLengthText by remember { mutableStateOf(if (node is ExtractNode) node.randomStringLength.toString() else "8") }
    var extractRandomStringCharset by remember { mutableStateOf(if (node is ExtractNode) node.randomStringCharset else "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") }
    var extractUseFixed by remember { mutableStateOf(if (node is ExtractNode) node.useFixed else false) }
    var extractFixedValue by remember { mutableStateOf(if (node is ExtractNode) node.fixedValue else "") }
    
    // 定时配置对话框状态
    var showScheduleDialog by remember { mutableStateOf(false) }

    val nodeTypes = mapOf(
        "trigger" to stringResource(R.string.workflow_node_type_trigger),
        "execute" to stringResource(R.string.workflow_node_type_execute),
        "condition" to stringResource(R.string.workflow_node_type_condition),
        "logic" to stringResource(R.string.workflow_node_type_logic),
        "extract" to stringResource(R.string.workflow_node_type_extract)
    )

    val triggerTypes = mapOf(
        "manual" to stringResource(R.string.workflow_trigger_type_manual),
        "schedule" to stringResource(R.string.workflow_trigger_type_schedule),
        "tasker" to stringResource(R.string.workflow_trigger_type_tasker),
        "intent" to stringResource(R.string.workflow_trigger_type_intent),
        "speech" to stringResource(R.string.workflow_trigger_type_speech)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isEditMode) {
                    stringResource(R.string.workflow_node_dialog_title_edit)
                } else {
                    stringResource(R.string.workflow_node_dialog_title_add)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 节点类型选择（仅在创建模式下显示）
                if (!isEditMode) {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = nodeTypes[nodeType] ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.workflow_node_type_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            nodeTypes.forEach { (key, value) ->
                                DropdownMenuItem(
                                    text = { Text(value) },
                                    onClick = {
                                        nodeType = key
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workflow_node_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { 
                        Text(
                            when (nodeType) {
                                "trigger" -> stringResource(
                                    R.string.workflow_example_format,
                                    triggerTypes[triggerType] ?: stringResource(R.string.workflow_trigger_type_manual)
                                )
                                "execute" -> stringResource(
                                    R.string.workflow_example_format,
                                    actionType.takeIf { it.isNotBlank() } ?: stringResource(R.string.workflow_default_execute_action)
                                )
                                "condition" -> stringResource(
                                    R.string.workflow_example_format,
                                    stringResource(R.string.workflow_node_default_name_condition)
                                )
                                "logic" -> stringResource(
                                    R.string.workflow_example_format,
                                    stringResource(R.string.workflow_node_default_name_logic)
                                )
                                "extract" -> stringResource(
                                    R.string.workflow_example_format,
                                    stringResource(R.string.workflow_node_default_name_extract)
                                )
                                else -> nodeTypes[nodeType].orEmpty()
                            }
                        )
                    }
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.workflow_node_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                // 根据节点类型显示不同的配置选项
                when (nodeType) {
                    "execute" -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(R.string.workflow_execute_config_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // 工具名称输入
                        ExposedDropdownMenuBox(
                            expanded = actionTypeExpanded,
                            onExpandedChange = { actionTypeExpanded = !actionTypeExpanded }
                        ) {
                            OutlinedTextField(
                                value = actionType,
                                onValueChange = {
                                    actionType = it
                                    actionTypeExpanded = true
                                },
                                label = { Text(stringResource(R.string.workflow_tool_name_label)) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.workflow_tool_name_placeholder)) },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        expanded = actionTypeExpanded
                                    )
                                }
                            )
                            ExposedDropdownMenu(
                                expanded = actionTypeExpanded,
                                onDismissRequest = { actionTypeExpanded = false },
                                modifier = Modifier.heightIn(max = 320.dp)
                            ) {
                                filteredToolNames.forEach { toolName ->
                                    DropdownMenuItem(
                                        text = { Text(toolName) },
                                        onClick = {
                                            actionType = toolName
                                            actionTypeExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        toolDescription?.takeIf { it.isNotBlank() }?.let { desc ->
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 动态参数配置
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.workflow_tool_params_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        actionConfigPairs.forEachIndexed { index, param ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 参数名输入
                                    OutlinedTextField(
                                        value = param.key,
                                        onValueChange = { newKey ->
                                            val newList = actionConfigPairs.toMutableList()
                                            newList[index] = param.copy(key = newKey)
                                            actionConfigPairs = newList
                                        },
                                        label = { Text(stringResource(R.string.workflow_param_name_label)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    // 参数值输入框（如果是引用则显示节点名称）
                                    OutlinedTextField(
                                        value = if (param.isReference) {
                                            // 显示引用节点的名称
                                            workflow.nodes.find { it.id == param.value }?.name
                                                ?: stringResource(R.string.workflow_unknown_node)
                                        } else {
                                            param.value
                                        },
                                        onValueChange = { newValue ->
                                            if (!param.isReference) {
                                                val newList = actionConfigPairs.toMutableList()
                                                newList[index] = param.copy(value = newValue)
                                                actionConfigPairs = newList
                                            }
                                        },
                                        label = { Text(stringResource(R.string.workflow_param_value_label)) },
                                        modifier = Modifier.weight(1f),
                                        readOnly = param.isReference,
                                        colors = if (param.isReference) {
                                            OutlinedTextFieldDefaults.colors(
                                                disabledTextColor = MaterialTheme.colorScheme.primary,
                                                disabledBorderColor = MaterialTheme.colorScheme.primary,
                                                disabledLabelColor = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            OutlinedTextFieldDefaults.colors()
                                        },
                                        enabled = !param.isReference,
                                        prefix = if (param.isReference) {
                                            { Text("🔗 ", style = MaterialTheme.typography.bodyLarge) }
                                        } else null
                                    )
                                    
                                    // 连接选择器按钮
                                    var showNodeSelector by remember { mutableStateOf(false) }
                                    IconButton(
                                        onClick = { showNodeSelector = true },
                                        enabled = availableReferenceNodes.isNotEmpty()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Call,
                                            contentDescription = stringResource(R.string.workflow_select_predecessor_node),
                                            tint = if (param.isReference) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                        )
                                    }
                                    
                                    // 前置节点选择下拉菜单
                                    DropdownMenu(
                                        expanded = showNodeSelector,
                                        onDismissRequest = { showNodeSelector = false }
                                    ) {
                                        // 选项：切换回静态值
                                        if (param.isReference) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.workflow_use_static_value)) },
                                                onClick = {
                                                    val newList = actionConfigPairs.toMutableList()
                                                    newList[index] = param.copy(isReference = false, value = "")
                                                    actionConfigPairs = newList
                                                    showNodeSelector = false
                                                }
                                            )
                                            HorizontalDivider()
                                        }
                                        
                                        // 显示所有可用的前置节点
                                        availableReferenceNodes.forEach { predecessorNode ->
                                            DropdownMenuItem(
                                                text = { 
                                                    Column {
                                                        Text(
                                                            text = predecessorNode.name,
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                        Text(
                                                            text = predecessorNode.type,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    val newList = actionConfigPairs.toMutableList()
                                                    newList[index] = param.copy(isReference = true, value = predecessorNode.id)
                                                    actionConfigPairs = newList
                                                    showNodeSelector = false
                                                }
                                            )
                                        }
                                        
                                        if (availableReferenceNodes.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.workflow_no_available_predecessor_nodes)) },
                                                onClick = { showNodeSelector = false },
                                                enabled = false
                                            )
                                        }
                                    }
                                    
                                    // 删除按钮
                                    IconButton(onClick = {
                                        val newList = actionConfigPairs.toMutableList()
                                        newList.removeAt(index)
                                        actionConfigPairs = newList
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.workflow_delete_param))
                                    }
                                }

                                val schema = toolParameterSchemasByName[param.key.trim()]
                                if (schema != null) {
                                    val requiredText = if (schema.required) {
                                        stringResource(R.string.workflow_schema_required)
                                    } else {
                                        stringResource(R.string.workflow_schema_optional)
                                    }
                                    val defaultText = schema.default?.let {
                                        stringResource(R.string.workflow_schema_default_format, it)
                                    } ?: ""
                                    Text(
                                        text = stringResource(
                                            R.string.workflow_schema_hint_format,
                                            schema.type,
                                            requiredText,
                                            schema.description,
                                            defaultText
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                actionConfigPairs = actionConfigPairs + ParameterConfig("", false, "")
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.workflow_add_param))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.workflow_add_param))
                        }
                    }
                    "condition" -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(R.string.workflow_condition_config_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        ExposedDropdownMenuBox(
                            expanded = conditionOperatorExpanded,
                            onExpandedChange = { conditionOperatorExpanded = !conditionOperatorExpanded }
                        ) {
                            OutlinedTextField(
                                value = conditionOperator.toDisplayText(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.workflow_operator_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = conditionOperatorExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = conditionOperatorExpanded,
                                onDismissRequest = { conditionOperatorExpanded = false }
                            ) {
                                ConditionOperator.values().forEach { op ->
                                    DropdownMenuItem(
                                        text = { Text(op.toDisplayText()) },
                                        onClick = {
                                            conditionOperator = op
                                            conditionOperatorExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.workflow_left_value_label),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = if (conditionLeftIsReference) {
                                    workflow.nodes.find { it.id == conditionLeftValue }?.name
                                        ?: stringResource(R.string.workflow_unknown_node)
                                } else {
                                    conditionLeftValue
                                },
                                onValueChange = { v ->
                                    if (!conditionLeftIsReference) conditionLeftValue = v
                                },
                                label = { Text(stringResource(R.string.workflow_left_value_label)) },
                                modifier = Modifier.weight(1f),
                                readOnly = conditionLeftIsReference,
                                enabled = !conditionLeftIsReference
                            )

                            var showLeftSelector by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showLeftSelector = true },
                                enabled = availableReferenceNodes.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = stringResource(R.string.workflow_select_predecessor_node)
                                )
                            }
                            DropdownMenu(
                                expanded = showLeftSelector,
                                onDismissRequest = { showLeftSelector = false }
                            ) {
                                if (conditionLeftIsReference) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.workflow_use_static_value)) },
                                        onClick = {
                                            conditionLeftIsReference = false
                                            conditionLeftValue = ""
                                            showLeftSelector = false
                                        }
                                    )
                                    HorizontalDivider()
                                }
                                availableReferenceNodes.forEach { predecessorNode ->
                                    DropdownMenuItem(
                                        text = { Text(predecessorNode.name) },
                                        onClick = {
                                            conditionLeftIsReference = true
                                            conditionLeftValue = predecessorNode.id
                                            showLeftSelector = false
                                        }
                                    )
                                }
                                if (availableReferenceNodes.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.workflow_no_available_predecessor_nodes)) },
                                        onClick = { showLeftSelector = false },
                                        enabled = false
                                    )
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.workflow_right_value_label),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = if (conditionRightIsReference) {
                                    workflow.nodes.find { it.id == conditionRightValue }?.name
                                        ?: stringResource(R.string.workflow_unknown_node)
                                } else {
                                    conditionRightValue
                                },
                                onValueChange = { v ->
                                    if (!conditionRightIsReference) conditionRightValue = v
                                },
                                label = { Text(stringResource(R.string.workflow_right_value_label)) },
                                modifier = Modifier.weight(1f),
                                readOnly = conditionRightIsReference,
                                enabled = !conditionRightIsReference
                            )

                            var showRightSelector by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showRightSelector = true },
                                enabled = availableReferenceNodes.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = stringResource(R.string.workflow_select_predecessor_node)
                                )
                            }
                            DropdownMenu(
                                expanded = showRightSelector,
                                onDismissRequest = { showRightSelector = false }
                            ) {
                                if (conditionRightIsReference) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.workflow_use_static_value)) },
                                        onClick = {
                                            conditionRightIsReference = false
                                            conditionRightValue = ""
                                            showRightSelector = false
                                        }
                                    )
                                    HorizontalDivider()
                                }
                                availableReferenceNodes.forEach { predecessorNode ->
                                    DropdownMenuItem(
                                        text = { Text(predecessorNode.name) },
                                        onClick = {
                                            conditionRightIsReference = true
                                            conditionRightValue = predecessorNode.id
                                            showRightSelector = false
                                        }
                                    )
                                }
                                if (availableReferenceNodes.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.workflow_no_available_predecessor_nodes)) },
                                        onClick = { showRightSelector = false },
                                        enabled = false
                                    )
                                }
                            }
                        }
                    }
                    "logic" -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(R.string.workflow_logic_config_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        ExposedDropdownMenuBox(
                            expanded = logicOperatorExpanded,
                            onExpandedChange = { logicOperatorExpanded = !logicOperatorExpanded }
                        ) {
                            OutlinedTextField(
                                value = logicOperator.toDisplayText(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.workflow_logic_operator_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = logicOperatorExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = logicOperatorExpanded,
                                onDismissRequest = { logicOperatorExpanded = false }
                            ) {
                                LogicOperator.values().forEach { op ->
                                    DropdownMenuItem(
                                        text = { Text(op.toDisplayText()) },
                                        onClick = {
                                            logicOperator = op
                                            logicOperatorExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    "extract" -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(R.string.workflow_extract_config_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        ExposedDropdownMenuBox(
                            expanded = extractModeExpanded,
                            onExpandedChange = { extractModeExpanded = !extractModeExpanded }
                        ) {
                            OutlinedTextField(
                                value = extractMode.toDisplayText(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.workflow_mode_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = extractModeExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = extractModeExpanded,
                                onDismissRequest = { extractModeExpanded = false }
                            ) {
                                ExtractMode.values().forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.toDisplayText()) },
                                        onClick = {
                                            extractMode = mode
                                            extractModeExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        when (extractMode) {
                            ExtractMode.REGEX, ExtractMode.JSON -> {
                                OutlinedTextField(
                                    value = extractExpression,
                                    onValueChange = { extractExpression = it },
                                    label = {
                                        Text(
                                            if (extractMode == ExtractMode.REGEX) {
                                                stringResource(R.string.workflow_regex_expression_label)
                                            } else {
                                                stringResource(R.string.workflow_json_path_label)
                                            }
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                if (extractMode == ExtractMode.REGEX) {
                                    OutlinedTextField(
                                        value = extractGroupText,
                                        onValueChange = { extractGroupText = it },
                                        label = { Text(stringResource(R.string.workflow_group_index_label)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                }

                                OutlinedTextField(
                                    value = extractDefaultValue,
                                    onValueChange = { extractDefaultValue = it },
                                    label = { Text(stringResource(R.string.workflow_default_value_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }

                            ExtractMode.SUB -> {
                                OutlinedTextField(
                                    value = extractStartIndexText,
                                    onValueChange = { extractStartIndexText = it },
                                    label = { Text(stringResource(R.string.workflow_start_index_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = extractLengthText,
                                    onValueChange = { extractLengthText = it },
                                    label = { Text(stringResource(R.string.workflow_length_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = extractDefaultValue,
                                    onValueChange = { extractDefaultValue = it },
                                    label = { Text(stringResource(R.string.workflow_default_value_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }

                            ExtractMode.CONCAT -> {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    extractOthers.forEachIndexed { index, other ->
                                        key(index) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = if (other.isReference) {
                                                        workflow.nodes.find { it.id == other.value }?.name ?: stringResource(R.string.workflow_unknown_node)
                                                    } else {
                                                        other.value
                                                    },
                                                    onValueChange = { v ->
                                                        if (!other.isReference) {
                                                            val newList = extractOthers.toMutableList()
                                                            newList[index] = other.copy(value = v)
                                                            extractOthers = newList
                                                        }
                                                    },
                                                    label = { Text(stringResource(R.string.workflow_concat_item_label)) },
                                                    modifier = Modifier.weight(1f),
                                                    readOnly = other.isReference,
                                                    enabled = !other.isReference
                                                )

                                                var showOtherSelector by remember { mutableStateOf(false) }
                                                IconButton(
                                                    onClick = { showOtherSelector = true },
                                                    enabled = availableReferenceNodes.isNotEmpty()
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Call,
                                                        contentDescription = stringResource(R.string.workflow_select_predecessor_node)
                                                    )
                                                }
                                                DropdownMenu(
                                                    expanded = showOtherSelector,
                                                    onDismissRequest = { showOtherSelector = false }
                                                ) {
                                                    if (other.isReference) {
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.workflow_use_static_value)) },
                                                            onClick = {
                                                                val newList = extractOthers.toMutableList()
                                                                newList[index] = other.copy(isReference = false, value = "")
                                                                extractOthers = newList
                                                                showOtherSelector = false
                                                            }
                                                        )
                                                        HorizontalDivider()
                                                    }
                                                    availableReferenceNodes.forEach { predecessorNode ->
                                                        DropdownMenuItem(
                                                            text = { Text(predecessorNode.name) },
                                                            onClick = {
                                                                val newList = extractOthers.toMutableList()
                                                                newList[index] = other.copy(isReference = true, value = predecessorNode.id)
                                                                extractOthers = newList
                                                                showOtherSelector = false
                                                            }
                                                        )
                                                    }
                                                    if (availableReferenceNodes.isEmpty()) {
                                                        DropdownMenuItem(
                                                            text = { Text(stringResource(R.string.workflow_no_available_predecessor_nodes)) },
                                                            onClick = { showOtherSelector = false },
                                                            enabled = false
                                                        )
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        val newList = extractOthers.toMutableList()
                                                        if (index in newList.indices) {
                                                            newList.removeAt(index)
                                                            extractOthers = newList
                                                        }
                                                    }
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.workflow_delete_concat_item))
                                                }
                                            }
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            extractOthers = extractOthers + ParameterConfig("", false, "")
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.workflow_add_concat_item))
                                    }
                                }
                            }

                            ExtractMode.RANDOM_INT -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Switch(
                                        checked = extractUseFixed,
                                        onCheckedChange = { extractUseFixed = it }
                                    )
                                    Text(
                                        if (extractUseFixed) {
                                            stringResource(R.string.workflow_use_fixed_value)
                                        } else {
                                            stringResource(R.string.workflow_use_random_value)
                                        }
                                    )
                                }

                                if (extractUseFixed) {
                                    OutlinedTextField(
                                        value = extractFixedValue,
                                        onValueChange = { extractFixedValue = it },
                                        label = { Text(stringResource(R.string.workflow_fixed_integer_label)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                }

                                OutlinedTextField(
                                    value = extractRandomMinText,
                                    onValueChange = { extractRandomMinText = it },
                                    label = { Text(stringResource(R.string.workflow_min_value_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = extractRandomMaxText,
                                    onValueChange = { extractRandomMaxText = it },
                                    label = { Text(stringResource(R.string.workflow_max_value_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }

                            ExtractMode.RANDOM_STRING -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Switch(
                                        checked = extractUseFixed,
                                        onCheckedChange = { extractUseFixed = it }
                                    )
                                    Text(
                                        if (extractUseFixed) {
                                            stringResource(R.string.workflow_use_fixed_value)
                                        } else {
                                            stringResource(R.string.workflow_use_random_value)
                                        }
                                    )
                                }

                                if (extractUseFixed) {
                                    OutlinedTextField(
                                        value = extractFixedValue,
                                        onValueChange = { extractFixedValue = it },
                                        label = { Text(stringResource(R.string.workflow_fixed_string_label)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                }

                                OutlinedTextField(
                                    value = extractRandomStringLengthText,
                                    onValueChange = { extractRandomStringLengthText = it },
                                    label = { Text(stringResource(R.string.workflow_string_length_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = extractRandomStringCharset,
                                    onValueChange = { extractRandomStringCharset = it },
                                    label = { Text(stringResource(R.string.workflow_charset_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2,
                                    maxLines = 4
                                )
                            }
                        }

                        if (extractMode != ExtractMode.RANDOM_INT && extractMode != ExtractMode.RANDOM_STRING) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = if (extractSourceIsReference) {
                                        workflow.nodes.find { it.id == extractSourceValue }?.name
                                            ?: stringResource(R.string.workflow_unknown_node)
                                    } else {
                                        extractSourceValue
                                    },
                                    onValueChange = { v ->
                                        if (!extractSourceIsReference) extractSourceValue = v
                                    },
                                    label = { Text(stringResource(R.string.workflow_source_label)) },
                                    modifier = Modifier.weight(1f),
                                    readOnly = extractSourceIsReference,
                                    enabled = !extractSourceIsReference
                                )

                                var showSourceSelector by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = { showSourceSelector = true },
                                    enabled = availableReferenceNodes.isNotEmpty()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Call,
                                        contentDescription = stringResource(R.string.workflow_select_predecessor_node)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSourceSelector,
                                    onDismissRequest = { showSourceSelector = false }
                                ) {
                                    if (extractSourceIsReference) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.workflow_use_static_value)) },
                                            onClick = {
                                                extractSourceIsReference = false
                                                extractSourceValue = ""
                                                showSourceSelector = false
                                            }
                                        )
                                        HorizontalDivider()
                                    }
                                    availableReferenceNodes.forEach { predecessorNode ->
                                        DropdownMenuItem(
                                            text = { Text(predecessorNode.name) },
                                            onClick = {
                                                extractSourceIsReference = true
                                                extractSourceValue = predecessorNode.id
                                                showSourceSelector = false
                                            }
                                        )
                                    }
                                    if (availableReferenceNodes.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.workflow_no_available_predecessor_nodes)) },
                                            onClick = { showSourceSelector = false },
                                            enabled = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "trigger" -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(R.string.workflow_trigger_config_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // 触发类型选择
                        ExposedDropdownMenuBox(
                            expanded = triggerTypeExpanded,
                            onExpandedChange = { triggerTypeExpanded = !triggerTypeExpanded }
                        ) {
                            OutlinedTextField(
                                value = triggerTypes[triggerType] ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.workflow_trigger_type_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = triggerTypeExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = triggerTypeExpanded,
                                onDismissRequest = { triggerTypeExpanded = false }
                            ) {
                                triggerTypes.forEach { (key, value) ->
                                    DropdownMenuItem(
                                        text = { Text(value) },
                                        onClick = {
                                            triggerType = key
                                            triggerTypeExpanded = false
                                            // 设置默认配置示例
                                            triggerConfig = when (key) {
                                                "schedule" -> """{"schedule_type":"interval","interval_ms":"900000","repeat":"true","enabled":"true"}"""
                                                "tasker" -> """{"variable_name": "%evtprm()"}"""
                                                "intent" -> """{"action": "com.example.MY_ACTION"}"""
                                                "speech" -> """{"pattern": "(?i)\\bhello\\b", "ignore_case": "true", "require_final": "true", "cooldown_ms": "3000"}"""
                                                else -> "{}"
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        if (triggerType == "schedule") {
                            Button(
                                onClick = { showScheduleDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.workflow_configure_schedule_trigger))
                            }
                            
                            if (triggerConfig.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.workflow_schedule_configured),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        } else if (triggerType != "manual") {
                            OutlinedTextField(
                                value = triggerConfig,
                                onValueChange = { triggerConfig = it },
                                label = { Text(stringResource(R.string.workflow_trigger_config_json_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4,
                                placeholder = { Text(stringResource(R.string.workflow_trigger_config_json_placeholder)) }
                            )

                            if (triggerType == "speech") {
                                Text(
                                    text = stringResource(R.string.workflow_speech_trigger_help),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 自动生成节点名称
                    val nodeName = if (name.isBlank()) {
                        when (nodeType) {
                            "trigger" -> {
                                // 根据触发类型生成名称
                                when (triggerType) {
                                    "manual" -> triggerTypes["manual"].orEmpty()
                                    "schedule" -> triggerTypes["schedule"].orEmpty()
                                    "tasker" -> triggerTypes["tasker"].orEmpty()
                                    "intent" -> triggerTypes["intent"].orEmpty()
                                    "speech" -> triggerTypes["speech"].orEmpty()
                                    else -> context.getString(R.string.workflow_trigger_fallback)
                                }
                            }
                            "execute" -> {
                                // 根据动作类型生成名称
                                actionType.takeIf { it.isNotBlank() } ?: context.getString(R.string.workflow_default_execute_action)
                            }
                            "condition" -> context.getString(R.string.workflow_node_default_name_condition)
                            "logic" -> context.getString(R.string.workflow_node_default_name_logic)
                            "extract" -> context.getString(R.string.workflow_node_default_name_extract)
                            else -> nodeTypes[nodeType] ?: context.getString(R.string.workflow_node_fallback)
                        }
                    } else {
                        name
                    }
                    
                    val resultNode: WorkflowNode = if (isEditMode && node != null) {
                        // 编辑模式：更新现有节点
                        when (node) {
                            is TriggerNode -> node.copy(
                                name = nodeName,
                                description = description,
                                triggerType = triggerType,
                                triggerConfig = if (triggerConfig.isNotBlank()) {
                                    try {
                                        org.json.JSONObject(triggerConfig).let { json ->
                                            json.keys().asSequence().associateWith { json.getString(it) }
                                        }
                                    } catch (e: Exception) {
                                        emptyMap()
                                    }
                                } else emptyMap()
                            )
                            is ExecuteNode -> node.copy(
                                name = nodeName,
                                description = description,
                                actionType = actionType,
                                actionConfig = actionConfigPairs
                                    .filter { it.key.isNotBlank() } // 过滤掉空的参数名
                                    .associate { param ->
                                        param.key to if (param.isReference) {
                                            ParameterValue.NodeReference(param.value)
                                        } else {
                                            ParameterValue.StaticValue(param.value)
                                        }
                                    }
                            )
                            is ConditionNode -> node.copy(
                                name = nodeName,
                                description = description,
                                left = if (conditionLeftIsReference) {
                                    ParameterValue.NodeReference(conditionLeftValue)
                                } else {
                                    ParameterValue.StaticValue(conditionLeftValue)
                                },
                                operator = conditionOperator,
                                right = if (conditionRightIsReference) {
                                    ParameterValue.NodeReference(conditionRightValue)
                                } else {
                                    ParameterValue.StaticValue(conditionRightValue)
                                }
                            )
                            is LogicNode -> node.copy(
                                name = nodeName,
                                description = description,
                                operator = logicOperator
                            )
                            is ExtractNode -> node.copy(
                                name = nodeName,
                                description = description,
                                source = if (extractSourceIsReference) {
                                    ParameterValue.NodeReference(extractSourceValue)
                                } else {
                                    ParameterValue.StaticValue(extractSourceValue)
                                },
                                mode = extractMode,
                                expression = extractExpression,
                                group = extractGroupText.toIntOrNull() ?: 0,
                                defaultValue = extractDefaultValue,
                                others = extractOthers
                                    .filter { it.value.isNotBlank() }
                                    .map { other ->
                                        if (other.isReference) {
                                            ParameterValue.NodeReference(other.value)
                                        } else {
                                            ParameterValue.StaticValue(other.value)
                                        }
                                    },
                                startIndex = extractStartIndexText.toIntOrNull() ?: 0,
                                length = extractLengthText.toIntOrNull() ?: -1,
                                randomMin = extractRandomMinText.toIntOrNull() ?: 0,
                                randomMax = extractRandomMaxText.toIntOrNull() ?: 100,
                                randomStringLength = extractRandomStringLengthText.toIntOrNull() ?: 8,
                                randomStringCharset = extractRandomStringCharset,
                                useFixed = extractUseFixed,
                                fixedValue = extractFixedValue
                            )
                            else -> node
                        }
                    } else {
                        // 创建模式：创建新节点
                        when (nodeType) {
                            "trigger" -> TriggerNode(
                                name = nodeName,
                                description = description,
                                triggerType = triggerType,
                                triggerConfig = if (triggerConfig.isNotBlank()) {
                                    try {
                                        org.json.JSONObject(triggerConfig).let { json ->
                                            json.keys().asSequence().associateWith { json.getString(it) }
                                        }
                                    } catch (e: Exception) {
                                        emptyMap()
                                    }
                                } else emptyMap()
                            )
                            "execute" -> ExecuteNode(
                                name = nodeName,
                                description = description,
                                actionType = actionType,
                                actionConfig = actionConfigPairs
                                    .filter { it.key.isNotBlank() } // 过滤掉空的参数名
                                    .associate { param ->
                                        param.key to if (param.isReference) {
                                            ParameterValue.NodeReference(param.value)
                                        } else {
                                            ParameterValue.StaticValue(param.value)
                                        }
                                    }
                            )
                            "condition" -> ConditionNode(
                                name = nodeName,
                                description = description,
                                left = if (conditionLeftIsReference) {
                                    ParameterValue.NodeReference(conditionLeftValue)
                                } else {
                                    ParameterValue.StaticValue(conditionLeftValue)
                                },
                                operator = conditionOperator,
                                right = if (conditionRightIsReference) {
                                    ParameterValue.NodeReference(conditionRightValue)
                                } else {
                                    ParameterValue.StaticValue(conditionRightValue)
                                }
                            )
                            "logic" -> LogicNode(
                                name = nodeName,
                                description = description,
                                operator = logicOperator
                            )
                            "extract" -> ExtractNode(
                                name = nodeName,
                                description = description,
                                source = if (extractSourceIsReference) {
                                    ParameterValue.NodeReference(extractSourceValue)
                                } else {
                                    ParameterValue.StaticValue(extractSourceValue)
                                },
                                mode = extractMode,
                                expression = extractExpression,
                                group = extractGroupText.toIntOrNull() ?: 0,
                                defaultValue = extractDefaultValue,
                                others = extractOthers
                                    .filter { it.value.isNotBlank() }
                                    .map { other ->
                                        if (other.isReference) {
                                            ParameterValue.NodeReference(other.value)
                                        } else {
                                            ParameterValue.StaticValue(other.value)
                                        }
                                    },
                                startIndex = extractStartIndexText.toIntOrNull() ?: 0,
                                length = extractLengthText.toIntOrNull() ?: -1,
                                randomMin = extractRandomMinText.toIntOrNull() ?: 0,
                                randomMax = extractRandomMaxText.toIntOrNull() ?: 100,
                                randomStringLength = extractRandomStringLengthText.toIntOrNull() ?: 8,
                                randomStringCharset = extractRandomStringCharset,
                                useFixed = extractUseFixed,
                                fixedValue = extractFixedValue
                            )
                            else -> TriggerNode(name = nodeName, description = description)
                        }
                    }
                    onConfirm(resultNode)
                }
            ) {
                Text(if (isEditMode) stringResource(R.string.settings_save) else stringResource(R.string.add_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_action))
            }
        }
    )
    
    // 定时配置对话框
    if (showScheduleDialog) {
        // 解析现有配置
        val parsedConfig = if (triggerConfig.isNotBlank()) {
            try {
                val json = org.json.JSONObject(triggerConfig)
                val map = mutableMapOf<String, String>()
                json.keys().forEach { key ->
                    map[key] = json.getString(key)
                }
                map
                                } catch (e: Exception) {
                                    emptyMap()
                                }
        } else {
                                    emptyMap()
                                }
        
        ScheduleConfigDialog(
            initialScheduleType = parsedConfig["schedule_type"] ?: "interval",
            initialConfig = parsedConfig,
            onDismiss = { showScheduleDialog = false },
            onConfirm = { scheduleType, config ->
                // 将 Map 转换为 JSON 字符串
                val json = org.json.JSONObject()
                json.put("schedule_type", scheduleType)
                config.forEach { (key, value) ->
                    json.put(key, value)
                }
                triggerConfig = json.toString(2)
                showScheduleDialog = false
            }
        )
    }
}


@Composable
fun EditWorkflowDialog(
    workflow: Workflow,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf(workflow.name) }
    var description by remember { mutableStateOf(workflow.description) }
    var enabled by remember { mutableStateOf(workflow.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workflow_edit_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workflow_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.workflow_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.workflow_enabled_label))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description, enabled) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_action))
            }
        }
    )
}

