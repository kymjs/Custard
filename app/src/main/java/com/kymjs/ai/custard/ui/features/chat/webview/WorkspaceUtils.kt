package com.kymjs.ai.custard.ui.features.chat.webview

import android.content.Context
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.util.CustardPaths
import java.io.File
import java.io.IOException

fun createAndGetDefaultWorkspace(context: Context, chatId: String): File {
    return createAndGetDefaultWorkspace(context, chatId, null)
}

fun createAndGetDefaultWorkspace(context: Context, chatId: String, projectType: String?): File {
    // 创建内部存储工作区
    val workspacePath = getWorkspacePath(context, chatId)
    ensureWorkspaceDirExists(workspacePath)

    val webContentDir = File(workspacePath)

    // 根据项目类型复制模板文件并创建配置
    when (projectType) {
        "android" -> {
            copyTemplateFiles(context, webContentDir, "android")
            createProjectConfigIfNeeded(context, webContentDir, ProjectType.ANDROID)
        }
        "node" -> {
            copyTemplateFiles(context, webContentDir, "node")
            createProjectConfigIfNeeded(context, webContentDir, ProjectType.NODE)
        }
        "typescript" -> {
            copyTemplateFiles(context, webContentDir, "typescript")
            createProjectConfigIfNeeded(context, webContentDir, ProjectType.TYPESCRIPT)
        }
        "python" -> {
            copyTemplateFiles(context, webContentDir, "python")
            createProjectConfigIfNeeded(context, webContentDir, ProjectType.PYTHON)
        }
        "java" -> {
            copyTemplateFiles(context, webContentDir, "java")
            createProjectConfigIfNeeded(context, webContentDir, ProjectType.JAVA)
        }
        "go" -> {
            copyTemplateFiles(context, webContentDir, "go")
            createProjectConfigIfNeeded(context, webContentDir, ProjectType.GO)
        }
        "office" -> {
            copyTemplateFiles(context, webContentDir, "office")
            createProjectConfigIfNeeded(context, webContentDir, ProjectType.OFFICE)
        }
        "blank" -> {
            createProjectConfigIfNeeded(context, webContentDir, ProjectType.BLANK)
        }
        else -> {
            copyTemplateFiles(context, webContentDir, "web")
            createProjectConfigIfNeeded(context, webContentDir, ProjectType.WEB)
        }
    }

    return webContentDir
}

/**
 * 获取工作区路径（新位置：内部存储）
 * 路径: /data/data/com.kymjs.ai.custard/files/workspace/{chatId}
 */
fun getWorkspacePath(context: Context, chatId: String): String {
    return File(context.filesDir, "workspace/$chatId").absolutePath
}

/**
 * 获取旧的工作区路径（外部存储）
 * 路径: /sdcard/Download/Custard/workspace/{chatId}
 */
fun getLegacyWorkspacePath(chatId: String): String {
    return CustardPaths.workspacePathSdcard(chatId)
}

fun ensureWorkspaceDirExists(path: String): File {
    val workspaceDir = File(path)
    if (!workspaceDir.exists()) {
        workspaceDir.mkdirs()
    }
    return workspaceDir
}

private enum class ProjectType {
    WEB, ANDROID, NODE, TYPESCRIPT, PYTHON, JAVA, GO, OFFICE, BLANK
}

/**
 * 生成空白项目配置JSON
 */
private fun generateBlankProjectConfig(context: Context): String {
    return """
{
    "projectType": "blank",
    "title": "${context.getString(R.string.workspace_project_blank_title)}",
    "description": "${context.getString(R.string.workspace_project_blank_description)}",
    "server": {
        "enabled": false,
        "port": 8080,
        "autoStart": false
    },
    "preview": {
        "type": "terminal",
        "url": "",
        "showPreviewButton": false,
        "previewButtonLabel": ""
    },
    "commands": [],
    "export": {
        "enabled": false
    }
}
""".trimIndent()
}

/**
 * 生成Android项目配置JSON
 */
private fun generateAndroidProjectConfig(context: Context): String {
    return """
{
    "projectType": "android",
    "title": "${context.getString(R.string.workspace_project_android_title)}",
    "description": "${context.getString(R.string.workspace_project_android_description)}",
    "server": {
        "enabled": false,
        "port": 8080,
        "autoStart": false
    },
    "preview": {
        "type": "terminal",
        "url": "",
        "showPreviewButton": false,
        "previewButtonLabel": ""
    },
    "commands": [
        {
            "id": "android_setup_env",
            "label": "${context.getString(R.string.workspace_cmd_android_setup_env)}",
            "command": "bash setup_android_env.sh",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "gradle_assemble_debug",
            "label": "${context.getString(R.string.workspace_cmd_android_assemble_debug)}",
            "command": "./gradlew assembleDebug",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "gradle_install_debug",
            "label": "${context.getString(R.string.workspace_cmd_android_install_debug)}",
            "command": "./gradlew installDebug",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "gradle_lint",
            "label": "${context.getString(R.string.workspace_cmd_android_lint)}",
            "command": "./gradlew lint",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "gradle_test",
            "label": "${context.getString(R.string.workspace_cmd_android_test)}",
            "command": "./gradlew test",
            "workingDir": ".",
            "shell": true
        }
    ],
    "export": {
        "enabled": false
    }
}
""".trimIndent()
}

/**
 * 生成Web项目配置JSON
 */
private fun generateWebProjectConfig(context: Context): String {
    return """
{
    "projectType": "web",
    "title": "${context.getString(R.string.workspace_project_web_title)}",
    "description": "${context.getString(R.string.workspace_project_web_description)}",
    "server": {
        "enabled": true,
        "port": 8093,
        "autoStart": true
    },
    "preview": {
        "type": "browser",
        "url": "http://localhost:8093"
    },
    "commands": [],
    "export": {
        "enabled": true
    }
}
""".trimIndent()
}

/**
 * 生成Node.js项目配置JSON
 */
private fun generateNodeProjectConfig(context: Context): String {
    return """
{
    "projectType": "node",
    "title": "${context.getString(R.string.workspace_project_node_title)}",
    "description": "${context.getString(R.string.workspace_project_node_description)}",
    "server": {
        "enabled": false,
        "port": 3000,
        "autoStart": false
    },
    "preview": {
        "type": "terminal",
        "url": "http://localhost:3000",
        "showPreviewButton": true,
        "previewButtonLabel": "${context.getString(R.string.workspace_preview_button_label_browser)}"
    },
    "commands": [
        {
            "id": "npm_init",
            "label": "npm init -y",
            "command": "npm init -y",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "npm_install",
            "label": "npm install",
            "command": "npm install",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "npm_start",
            "label": "npm start",
            "command": "npm start",
            "workingDir": ".",
            "shell": true,
            "usesDedicatedSession": true,
            "sessionTitle": "npm start"
        },
        {
            "id": "npm_test",
            "label": "npm test",
            "command": "npm test",
            "workingDir": ".",
            "shell": true
        }
    ],
    "export": {
        "enabled": false
    }
}
""".trimIndent()
}

/**
 * 生成TypeScript项目配置JSON
 */
private fun generateTypeScriptProjectConfig(context: Context): String {
    return """
{
    "projectType": "typescript",
    "title": "${context.getString(R.string.workspace_project_typescript_title)}",
    "description": "${context.getString(R.string.workspace_project_typescript_description)}",
    "server": {
        "enabled": false,
        "port": 3000,
        "autoStart": false
    },
    "preview": {
        "type": "terminal",
        "url": "",
        "showPreviewButton": false
    },
    "commands": [
        {
            "id": "pnpm_install",
            "label": "pnpm install",
            "command": "pnpm install",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "pnpm_build",
            "label": "pnpm build",
            "command": "pnpm build",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "tsc_watch",
            "label": "tsc watch",
            "command": "pnpm exec tsc --watch",
            "workingDir": ".",
            "shell": true,
            "usesDedicatedSession": true,
            "sessionTitle": "TypeScript Watch"
        },
        {
            "id": "pnpm_start",
            "label": "pnpm start",
            "command": "pnpm start",
            "workingDir": ".",
            "shell": true,
            "usesDedicatedSession": true,
            "sessionTitle": "pnpm start"
        },
        {
            "id": "pnpm_list",
            "label": "pnpm list",
            "command": "pnpm list",
            "workingDir": ".",
            "shell": true
        }
    ],
    "export": {
        "enabled": false
    }
}
""".trimIndent()
}

/**
 * 生成Python项目配置JSON
 */
private fun generatePythonProjectConfig(context: Context): String {
    return """
{
    "projectType": "python",
    "title": "${context.getString(R.string.workspace_project_python_title)}",
    "description": "${context.getString(R.string.workspace_project_python_description)}",
    "server": {
        "enabled": false,
        "port": 8000,
        "autoStart": false
    },
    "preview": {
        "type": "terminal",
        "url": "",
        "showPreviewButton": false
    },
    "commands": [
        {
            "id": "venv_create",
            "label": "${context.getString(R.string.workspace_cmd_python_venv_create)}",
            "command": "python -m venv venv",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "venv_activate",
            "label": "${context.getString(R.string.workspace_cmd_python_venv_activate)}",
            "command": "source venv/bin/activate || venv\\Scripts\\activate",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "pip_install",
            "label": "${context.getString(R.string.workspace_cmd_python_pip_install)}",
            "command": "pip install -r requirements.txt",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "pip_list",
            "label": "${context.getString(R.string.workspace_cmd_python_pip_list)}",
            "command": "pip list",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "python_run",
            "label": "${context.getString(R.string.workspace_cmd_python_run)}",
            "command": "python main.py",
            "workingDir": ".",
            "shell": true
        }
    ],
    "export": {
        "enabled": false
    }
}
""".trimIndent()
}

/**
 * 生成Java项目配置JSON
 */
private fun generateJavaProjectConfig(context: Context): String {
    return """
{
    "projectType": "java",
    "title": "${context.getString(R.string.workspace_project_java_title)}",
    "description": "${context.getString(R.string.workspace_project_java_description)}",
    "server": {
        "enabled": false,
        "port": 8080,
        "autoStart": false
    },
    "preview": {
        "type": "terminal",
        "url": "",
        "showPreviewButton": false
    },
    "commands": [
        {
            "id": "gradle_init",
            "label": "${context.getString(R.string.workspace_cmd_java_gradle_init)}",
            "command": "gradle wrapper --gradle-version 8.5",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "gradle_build",
            "label": "${context.getString(R.string.workspace_cmd_java_gradle_build)}",
            "command": "./gradlew build || gradle build",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "gradle_run",
            "label": "${context.getString(R.string.workspace_cmd_java_gradle_run)}",
            "command": "./gradlew run || gradle run",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "gradle_test",
            "label": "${context.getString(R.string.workspace_cmd_java_gradle_test)}",
            "command": "./gradlew test || gradle test",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "gradle_jar",
            "label": "${context.getString(R.string.workspace_cmd_java_gradle_jar)}",
            "command": "./gradlew jar || gradle jar",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "gradle_clean",
            "label": "${context.getString(R.string.workspace_cmd_java_gradle_clean)}",
            "command": "./gradlew clean || gradle clean",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "gradle_tasks",
            "label": "${context.getString(R.string.workspace_cmd_java_gradle_tasks)}",
            "command": "./gradlew tasks || gradle tasks",
            "workingDir": ".",
            "shell": true
        }
    ],
    "export": {
        "enabled": false
    }
}
""".trimIndent()
}

/**
 * 生成Go项目配置JSON
 */
private fun generateGoProjectConfig(context: Context): String {
    return """
{
    "projectType": "go",
    "title": "${context.getString(R.string.workspace_project_go_title)}",
    "description": "${context.getString(R.string.workspace_project_go_description)}",
    "server": {
        "enabled": false,
        "port": 8080,
        "autoStart": false
    },
    "preview": {
        "type": "terminal",
        "url": "",
        "showPreviewButton": false
    },
    "commands": [
        {
            "id": "go_mod_init",
            "label": "go mod init",
            "command": "go mod init myapp",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "go_mod_tidy",
            "label": "go mod tidy",
            "command": "go mod tidy",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "go_run",
            "label": "go run main.go",
            "command": "go run main.go",
            "workingDir": ".",
            "shell": true
        },
        {
            "id": "go_build",
            "label": "go build",
            "command": "go build",
            "workingDir": ".",
            "shell": true
        }
    ],
    "export": {
        "enabled": false
    }
}
""".trimIndent()
}

/**
 * 生成办公文档项目配置JSON
 */
private fun generateOfficeProjectConfig(context: Context): String {
    return """
{
    "projectType": "office",
    "title": "${context.getString(R.string.workspace_project_office_title)}",
    "description": "${context.getString(R.string.workspace_project_office_description)}",
    "server": {
        "enabled": false,
        "port": 8080,
        "autoStart": false
    },
    "preview": {
        "type": "terminal",
        "url": "",
        "showPreviewButton": false,
        "previewButtonLabel": ""
    },
    "commands": [],
    "export": {
        "enabled": false
    }
}
""".trimIndent()
}

/**
 * 从 assets 复制项目模板文件到工作区
 */
private fun copyTemplateFiles(context: Context, workspaceDir: File, templateName: String) {
    val assetManager = context.assets
    val templatePath = "templates/$templateName"

    try {
        val files = assetManager.list(templatePath) ?: return

        for (filename in files) {
            val sourcePath = "$templatePath/$filename"
            // 特殊处理：gitignore (无点) -> .gitignore (有点)
            // 因为 Android 构建工具会排除 assets 中的 .gitignore 文件
            val destFileName = if (filename == "gitignore") ".gitignore" else filename
            val destFile = File(workspaceDir, destFileName)

            // 检查是否是目录
            val isDirectory = try {
                assetManager.list(sourcePath)?.isNotEmpty() == true
            } catch (e: IOException) {
                false
            }

            if (isDirectory) {
                // 递归复制子目录
                destFile.mkdirs()
                copyTemplateFilesRecursive(assetManager, sourcePath, destFile)
            } else {
                // 复制文件
                assetManager.open(sourcePath).use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        }
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

/**
 * 递归复制模板文件
 */
private fun copyTemplateFilesRecursive(assetManager: android.content.res.AssetManager, sourcePath: String, destDir: File) {
    try {
        val files = assetManager.list(sourcePath) ?: return

        for (filename in files) {
            val currentSourcePath = "$sourcePath/$filename"
            val destFile = File(destDir, filename)

            val isDirectory = try {
                assetManager.list(currentSourcePath)?.isNotEmpty() == true
            } catch (e: IOException) {
                false
            }

            if (isDirectory) {
                destFile.mkdirs()
                copyTemplateFilesRecursive(assetManager, currentSourcePath, destFile)
            } else {
                assetManager.open(currentSourcePath).use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        }
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

private fun createProjectConfigIfNeeded(context: Context, workspaceDir: File, projectType: ProjectType) {
    // 创建 .custard 目录和 config.json
    val custardDir = File(workspaceDir, ".custard")
    if (!custardDir.exists()) {
        custardDir.mkdirs()
    }

    val configFile = File(custardDir, "config.json")
    if (!configFile.exists()) {
        val configContent = when (projectType) {
            ProjectType.WEB -> generateWebProjectConfig(context)
            ProjectType.ANDROID -> generateAndroidProjectConfig(context)
            ProjectType.NODE -> generateNodeProjectConfig(context)
            ProjectType.TYPESCRIPT -> generateTypeScriptProjectConfig(context)
            ProjectType.PYTHON -> generatePythonProjectConfig(context)
            ProjectType.JAVA -> generateJavaProjectConfig(context)
            ProjectType.GO -> generateGoProjectConfig(context)
            ProjectType.OFFICE -> generateOfficeProjectConfig(context)
            ProjectType.BLANK -> generateBlankProjectConfig(context)
        }

        try {
            configFile.writeText(configContent.trimIndent())
        } catch (_: IOException) {
            // Ignore write errors for now
        }
    }
}
