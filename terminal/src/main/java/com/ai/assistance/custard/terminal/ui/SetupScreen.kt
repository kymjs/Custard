package com.ai.assistance.custard.terminal.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.custard.terminal.TerminalManager
import com.ai.assistance.custard.terminal.data.PackageManagerType
import com.ai.assistance.custard.terminal.utils.SourceManager
import com.ai.assistance.custard.terminal.utils.SSHConfigManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collect

enum class InstallStatus {
    CHECKING,
    INSTALLED,
    NOT_INSTALLED
}

data class PackageItem(
    val id: String,
    val name: String,
    val command: String,
    val description: String = ""
)

data class PackageCategory(
    val id: String,
    val name: String,
    val description: String,
    val packages: List<PackageItem>
)

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    onSetup: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val sourceManager = remember { SourceManager(context) }
    val sshConfigManager = remember { SSHConfigManager(context) }
    
    // 检查SSH是否启用
    var isSSHEnabled by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        isSSHEnabled = sshConfigManager.isEnabled()
    }
    
    val packageCategories by remember {
        derivedStateOf {
            listOf(
                PackageCategory(
                    id = "nodejs",
                    name = context.getString(com.ai.assistance.custard.terminal.R.string.category_nodejs_name),
                    description = context.getString(com.ai.assistance.custard.terminal.R.string.category_nodejs_desc),
                    packages = listOf(
                        PackageItem("nodejs", context.getString(com.ai.assistance.custard.terminal.R.string.package_nodejs_name), "curl -fsSL https://deb.nodesource.com/setup_24.x | bash - && apt install -y nodejs", context.getString(com.ai.assistance.custard.terminal.R.string.package_nodejs_desc)),
                        PackageItem("pnpm", context.getString(com.ai.assistance.custard.terminal.R.string.package_pnpm_name), "typescript", context.getString(com.ai.assistance.custard.terminal.R.string.package_pnpm_desc))
                    )
                ),
                PackageCategory(
                    id = "python",
                    name = context.getString(com.ai.assistance.custard.terminal.R.string.category_python_name),
                    description = context.getString(com.ai.assistance.custard.terminal.R.string.category_python_desc),
                    packages = listOf(
                        PackageItem("python-is-python3", context.getString(com.ai.assistance.custard.terminal.R.string.package_python_link_name), "python-is-python3", context.getString(com.ai.assistance.custard.terminal.R.string.package_python_link_desc)),
                        PackageItem("python3-venv", context.getString(com.ai.assistance.custard.terminal.R.string.package_python_venv_name), "python3-venv", context.getString(com.ai.assistance.custard.terminal.R.string.package_python_venv_desc)),
                        PackageItem("python3-pip", context.getString(com.ai.assistance.custard.terminal.R.string.package_python_pip_name), "python3-pip", context.getString(com.ai.assistance.custard.terminal.R.string.package_python_pip_desc)),
                        PackageItem("uv", context.getString(com.ai.assistance.custard.terminal.R.string.package_uv_name), "pipx install uv", context.getString(com.ai.assistance.custard.terminal.R.string.package_uv_desc))
                    )
                ),
                PackageCategory(
                    id = "ssh",
                    name = context.getString(com.ai.assistance.custard.terminal.R.string.category_ssh_name),
                    description = context.getString(com.ai.assistance.custard.terminal.R.string.category_ssh_desc),
                    packages = listOf(
                        PackageItem("ssh", context.getString(com.ai.assistance.custard.terminal.R.string.package_ssh_client_name), "ssh", context.getString(com.ai.assistance.custard.terminal.R.string.package_ssh_client_desc)),
                        PackageItem("sshpass", context.getString(com.ai.assistance.custard.terminal.R.string.package_sshpass_name), "sshpass", context.getString(com.ai.assistance.custard.terminal.R.string.package_sshpass_desc)),
                        PackageItem("openssh-server", "OpenSSH 服务器", "openssh-server", "用于反向隧道挂载本地文件系统")
                    )
                ),
                PackageCategory(
                    id = "java", 
                    name = context.getString(com.ai.assistance.custard.terminal.R.string.category_java_name),
                    description = context.getString(com.ai.assistance.custard.terminal.R.string.category_java_desc),
                    packages = listOf(
                        PackageItem("openjdk-17", context.getString(com.ai.assistance.custard.terminal.R.string.package_openjdk_name), "openjdk-17-jdk", context.getString(com.ai.assistance.custard.terminal.R.string.package_openjdk_desc)),
                        PackageItem("gradle", context.getString(com.ai.assistance.custard.terminal.R.string.package_gradle_name), "gradle", context.getString(com.ai.assistance.custard.terminal.R.string.package_gradle_desc))
                    )
                ),
                PackageCategory(
                    id = "rust",
                    name = context.getString(com.ai.assistance.custard.terminal.R.string.category_rust_name),
                    description = context.getString(com.ai.assistance.custard.terminal.R.string.category_rust_desc),
                    packages = listOf(
                        PackageItem("rust", context.getString(com.ai.assistance.custard.terminal.R.string.package_rust_name), "RUST_INSTALL_COMMAND", context.getString(com.ai.assistance.custard.terminal.R.string.package_rust_desc))
                    )
                ),
                PackageCategory(
                    id = "go",
                    name = context.getString(com.ai.assistance.custard.terminal.R.string.category_go_name),
                    description = context.getString(com.ai.assistance.custard.terminal.R.string.category_go_desc),
                    packages = listOf(
                        PackageItem("go", context.getString(com.ai.assistance.custard.terminal.R.string.package_go_name), "golang-go", context.getString(com.ai.assistance.custard.terminal.R.string.package_go_desc))
                    )
                )
            )
        }
    }

    // 跟踪每个分类的展开状态
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }
    
    // 跟踪选中的包
    val selectedPackages = remember { mutableStateMapOf<String, Boolean>() }
    
    // 跟踪每个分类的全选状态
    val categorySelectAll = remember { mutableStateMapOf<String, Boolean>() }
    
    // 新增：跟踪包的安装状态
    val packageStatus = remember { mutableStateMapOf<String, InstallStatus>() }
    val terminalManager = remember(context) { TerminalManager.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()
    var checkSessionId by remember { mutableStateOf<String?>(null) }

    // 创建一个用于检查的会话，并在Composable销毁时关闭它
    DisposableEffect(terminalManager) {
        val job = coroutineScope.launch {
            try {
                val session = terminalManager.createNewSession("setup-check")
                checkSessionId = session.id
                Log.d("SetupScreen", "Created check session: ${session.id}")
            } catch (e: Exception) {
                Log.e("SetupScreen", "Failed to create check session", e)
            }
        }

        onDispose {
            job.cancel()
            checkSessionId?.let {
                Log.d("SetupScreen", "Closing check session $it")
                terminalManager.closeSession(it)
            }
        }
    }

    // 当会话准备好后，开始检查包状态
    LaunchedEffect(checkSessionId) {
        val sessionId = checkSessionId ?: return@LaunchedEffect

        // 初始化所有包为检查中状态
        val allPackages = packageCategories.flatMap { it.packages }
        allPackages.forEach { pkg ->
            packageStatus[pkg.id] = InstallStatus.CHECKING
        }

        // 并发检查所有包
        allPackages.forEach { pkg ->
            launch {
                val isInstalled = checkPackageInstalled(terminalManager, sessionId, pkg, this)
                if (isInstalled) {
                    packageStatus[pkg.id] = InstallStatus.INSTALLED
                    selectedPackages[pkg.id] = true
                } else {
                    packageStatus[pkg.id] = InstallStatus.NOT_INSTALLED
                }

                // 检查是否需要更新分类的全选状态
                val category = packageCategories.find { c -> c.packages.any { it.id == pkg.id } }
                category?.let { cat ->
                    val allInCategoryFinishedChecking = cat.packages.all { p -> packageStatus[p.id] != InstallStatus.CHECKING }
                    if (allInCategoryFinishedChecking) {
                        val allInCategorySelected = cat.packages.all { p -> selectedPackages[p.id] == true }
                        categorySelectAll[cat.id] = allInCategorySelected
                    }
                }
            }
        }
    }

    var showSetupDialog by remember { mutableStateOf(false) }
    val commandsToRun = remember { mutableStateOf<List<String>>(emptyList()) }

    if (showSetupDialog) {
        AlertDialog(
            onDismissRequest = { showSetupDialog = false },
            title = { Text(context.getString(com.ai.assistance.custard.terminal.R.string.setup_dialog_title)) },
            text = { Text(context.getString(com.ai.assistance.custard.terminal.R.string.setup_dialog_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showSetupDialog = false
                        // 在开始设置前，显式关闭检查会话
                        checkSessionId?.let { sid ->
                            Log.d("SetupScreen", "Closing check session $sid before starting setup.")
                            terminalManager.closeSession(sid)
                            checkSessionId = null // 防止 onDispose 重复关闭
                        }
                        onSetup(commandsToRun.value)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
                ) {
                    Text(context.getString(com.ai.assistance.custard.terminal.R.string.dialog_confirm), color = Color.White)
                }
            },
            dismissButton = {
                Button(
                    onClick = { showSetupDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A))
                ) {
                    Text(context.getString(com.ai.assistance.custard.terminal.R.string.dialog_cancel), color = Color.White)
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = context.getString(com.ai.assistance.custard.terminal.R.string.setup_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = context.getString(com.ai.assistance.custard.terminal.R.string.setup_subtitle),
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // SSH模式警告横幅
        if (isSSHEnabled) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFA500).copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️",
                        fontSize = 20.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column {
                        Text(
                            text = "SSH 模式警告",
                            color = Color(0xFFFFA500),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "本页面在 SSH 模式下检测不准确，请自行手动配置 pnpm 和 python。",
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // 包分类列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(packageCategories) { category ->
                CategoryCard(
                    category = category,
                    isExpanded = expandedCategories[category.id] ?: false,
                    onExpandToggle = { expandedCategories[category.id] = !expandedCategories.getOrDefault(category.id, false) },
                    selectedPackages = selectedPackages,
                    categorySelectAll = categorySelectAll,
                    packageStatus = packageStatus
                )
            }
        }

        // 底部按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A))
            ) {
                Text(context.getString(com.ai.assistance.custard.terminal.R.string.skip), color = Color.White)
            }
            
            Button(
                onClick = {
                    val commands = mutableListOf<String>()
                    
                    // 系统修复（串行）
                    commands.add("dpkg --configure -a")
                    commands.add("apt install -f -y")

                    // 更新软件源
                    commands.add("apt update -y")

                    // 系统升级
                    commands.add("apt upgrade -y")
                    
                    // 为 pip/pipx 设置国内镜像（永久配置）
                    commands.add("mkdir -p ~/.config/pip")
                    commands.add("echo '[global]' > ~/.config/pip/pip.conf")
                    commands.add("echo 'index-url = https://pypi.tuna.tsinghua.edu.cn/simple' >> ~/.config/pip/pip.conf")
                    
                    // 为 uv/uvx 设置国内镜像（永久配置）
                    commands.add("mkdir -p ~/.config/uv")
                    commands.add("echo 'index-url = \"https://pypi.tuna.tsinghua.edu.cn/simple\"' > ~/.config/uv/uv.toml")
                    
                    // 收集选中的包
                    val selectedAptPackages = mutableListOf<String>()
                    val selectedNpmPackages = mutableListOf<String>()
                    val selectedCustomCommands = mutableListOf<String>()
                    
                    packageCategories.forEach { category ->
                        category.packages.forEach { pkg ->
                            if (selectedPackages[pkg.id] == true && packageStatus[pkg.id] != InstallStatus.INSTALLED) {
                                // 根据分类和包ID判断包管理器
                                if (pkg.id == "rust") {
                                    // 获取当前选择的Rust镜像源
                                    val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)
                                    val rustEnvCommand = sourceManager.getRustSourceEnvCommand(rustSource)
                                    // 添加环境变量设置和安装命令
                                    selectedCustomCommands.add("$rustEnvCommand && curl -v --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y")
                                } else if (pkg.id == "uv" || pkg.id == "nodejs") {
                                    selectedCustomCommands.add(pkg.command)
                                } else if (category.id == "nodejs" && pkg.id != "nodejs") {
                                    selectedNpmPackages.add(pkg.command)
                                } else {
                                    selectedAptPackages.add(pkg.command)
                                }
                            }
                        }
                    }

                    // 添加 pipx 作为 uv 的依赖
                    if (selectedPackages.getOrDefault("uv", false) && packageStatus["uv"] != InstallStatus.INSTALLED) {
                        selectedAptPackages.add("pipx")
                    }

                    // 首先安装所有依赖包
                    val allAptDeps = mutableSetOf<String>()
                    
                    // 添加自定义命令的依赖
                    if (selectedCustomCommands.isNotEmpty()) {
                        if (selectedPackages.getOrDefault("rust", false)) {
                            allAptDeps.add("curl")
                            allAptDeps.add("build-essential")
                        }
                        if (selectedPackages.getOrDefault("nodejs", false)) {
                            allAptDeps.add("curl")
                        }
                    }
                    
                    // 添加选中的 apt 包
                    allAptDeps.addAll(selectedAptPackages)
                    
                    // 使用 apt 安装所有 apt 包和依赖
                    if (allAptDeps.isNotEmpty()) {
                        commands.add("apt install -y ${allAptDeps.joinToString(" ")}")
                    }
                    
                    // 然后运行自定义命令（如安装 rust, uv, nodejs 等）
                    if (selectedCustomCommands.isNotEmpty()) {
                        commands.addAll(selectedCustomCommands)

                        // 如果安装了 uv，则需要确保 pipx 路径可用
                        if (selectedPackages.getOrDefault("uv", false)) {
                            commands.add("pipx ensurepath")
                            commands.add("source ~/.profile")
                        }
                    }
                    
                    // 安装 NPM 包（如果 nodejs 已经安装或被选中）
                    if (selectedNpmPackages.isNotEmpty()) {
                        // 更换为淘宝源
                        commands.add("npm config set registry https://registry.npmmirror.com/")
                        // 清理 npm 缓存
                        commands.add("npm cache clean --force")
                        // 安装pnpm
                        commands.add("npm install -g pnpm")
                        // 使用 pnpm 安装其他包
                        commands.add("pnpm add -g ${selectedNpmPackages.joinToString(" ")}")
                    }
                    
                    commandsToRun.value = commands
                    showSetupDialog = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
            ) {
                Text(context.getString(com.ai.assistance.custard.terminal.R.string.start_setup), color = Color.White)
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: PackageCategory,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    selectedPackages: MutableMap<String, Boolean>,
    categorySelectAll: MutableMap<String, Boolean>,
    packageStatus: Map<String, InstallStatus>
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 分类标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 标题 - 第一行
                    Text(
                        text = category.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // Custard必须标签 - 第二行
                    if (category.id == "nodejs" || category.id == "python") {
                        Text(
                            text = "(${context.getString(com.ai.assistance.custard.terminal.R.string.custard_required)})",
                            color = Color(0xFFFFA500), // Orange color
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    // 描述 - 第三行
                    Text(
                        text = category.description,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                
                // 全选按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Checkbox(
                        checked = categorySelectAll[category.id] ?: false,
                        onCheckedChange = { selectAll ->
                            categorySelectAll[category.id] = selectAll
                            category.packages.forEach { pkg ->
                                if (packageStatus[pkg.id] != InstallStatus.INSTALLED) {
                                    selectedPackages[pkg.id] = selectAll
                                }
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF006400),
                            uncheckedColor = Color.Gray
                        )
                    )
                    Text(
                        text = context.getString(com.ai.assistance.custard.terminal.R.string.select_all),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                
                // 展开/收起图标
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) context.getString(com.ai.assistance.custard.terminal.R.string.collapse) else context.getString(com.ai.assistance.custard.terminal.R.string.expand),
                    tint = Color.White
                )
            }
            
            // 包列表（可展开）
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                
                category.packages.forEach { pkg ->
                    PackageItem(
                        packageItem = pkg,
                        isSelected = selectedPackages[pkg.id] ?: false,
                        onSelectionChange = { selected ->
                            selectedPackages[pkg.id] = selected
                            // 检查是否需要更新全选状态
                            val allSelectedAfterChange = category.packages.all { p ->
                                selectedPackages[p.id] == true
                            }
                            categorySelectAll[category.id] = allSelectedAfterChange
                        },
                        status = packageStatus[pkg.id] ?: InstallStatus.NOT_INSTALLED
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageItem(
    packageItem: PackageItem,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    status: InstallStatus
) {
    val context = LocalContext.current
    val isInstalled = status == InstallStatus.INSTALLED
    val isChecking = status == InstallStatus.CHECKING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isInstalled) { onSelectionChange(!isSelected) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Checkbox(
                checked = isSelected || isInstalled,
                onCheckedChange = onSelectionChange,
                enabled = !isInstalled,
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF006400),
                    uncheckedColor = Color.Gray,
                    disabledCheckedColor = Color(0xFF006400).copy(alpha = 0.5f)
                )
            )
        }
        
        Column(
            modifier = Modifier.padding(start = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = packageItem.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                if (isInstalled) {
                    Text(
                        text = " (${context.getString(com.ai.assistance.custard.terminal.R.string.installed)})",
                        color = Color.Green.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            if (packageItem.description.isNotEmpty()) {
                Text(
                    text = packageItem.description,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun checkPackageInstalled(
    terminalManager: TerminalManager,
    sessionId: String,
    pkg: PackageItem,
    scope: CoroutineScope
): Boolean {
    val command: String = when (pkg.id) {
        "rust" -> "command -v rustc"
        "uv" -> "command -v uv"
        "nodejs" -> "node -v 2>/dev/null"
        "pnpm" -> "test -f \"\$(npm prefix -g)/bin/pnpm\" && echo FOUND_PNPM"
        "go" -> "command -v go"
        "ssh" -> "command -v ssh"
        "sshpass" -> "command -v sshpass"
        "openssh-server" -> "command -v sshd"
        "gradle" -> "command -v gradle"
        else -> "dpkg -s ${pkg.command.split(" ").first()}"
    }

    val output = executeCommandAndGetOutput(terminalManager, sessionId, command, scope)
    if (output == null) return false // 超时或错误

    return when (pkg.id) {
        "nodejs" -> {
            // 检查 Node.js 版本是否 >= 24
            if (output.isBlank() || output.contains("not found")) return false
            val versionMatch = Regex("""v(\d+)\..*""").find(output.trim())
            val majorVersion = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            majorVersion >= 24
        }
        "rust", "uv", "go", "ssh", "sshpass", "openssh-server", "gradle" -> output.isNotBlank() && !output.contains("not found")
        "pnpm" -> output.contains("FOUND_PNPM")
        else -> output.contains("Status: install ok installed")
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun executeCommandAndGetOutput(
    terminalManager: TerminalManager,
    sessionId: String,
    command: String,
    scope: CoroutineScope
): String? {
    val deferred = CompletableDeferred<String>()
    val output = StringBuilder()
    val commandId = UUID.randomUUID().toString()
    val collectorReady = CompletableDeferred<Unit>()

    val job = scope.launch {
        terminalManager.commandExecutionEvents
            .filter { it.sessionId == sessionId && it.commandId == commandId }
            .onStart { collectorReady.complete(Unit) }
            .collect { event ->
                output.append(event.outputChunk)
                if (event.isCompleted) {
                    if (!deferred.isCompleted) {
                        deferred.complete(output.toString())
                    }
                }
            }
    }

    collectorReady.await()
    terminalManager.switchToSession(sessionId)
    terminalManager.sendCommand(command, commandId)

    val result = withTimeoutOrNull(15000L) { // 15s timeout
        deferred.await()
    }
    
    job.cancel()
    return result
} 