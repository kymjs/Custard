package com.kymjs.ai.custard.core.config

import com.kymjs.ai.custard.data.model.SystemToolPromptCategory
import com.kymjs.ai.custard.data.model.ToolPrompt
import com.kymjs.ai.custard.data.model.ToolParameterSchema

/**
 * 系统工具提示词管理器
 * 包含所有工具的结构化定义
 */
object SystemToolPrompts {

    private fun buildSafBookmarksSectionEn(safBookmarkNames: List<String>): String {
        val names = safBookmarkNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return ""
        val listed = names.joinToString(", ") { "repo:$it" }
        return """

**Attached Local Storage Repository:**
- environment (optional): you can also use `environment="repo:<repositoryName>"` to operate in an attached local storage repository.
- Paths are absolute (e.g., `/`, `/work/index.html`).
- Available repositories: $listed
""".trimEnd()
    }

    private fun buildSafBookmarksSectionCn(safBookmarkNames: List<String>): String {
        val names = safBookmarkNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return ""
        val listed = names.joinToString("、") { "repo:$it" }
        return """

**附加本地储存仓库：**
- environment（可选）：也可以使用 `environment="repo:<仓库名>"` 在附加本地储存仓库中操作。
- 路径使用绝对路径（例如 `/`、`/work/index.html`）。
- 当前可用仓库：$listed
""".trimEnd()
    }
    
    // ==================== 基础工具 ====================
    val basicTools = SystemToolPromptCategory(
        categoryName = "Available tools",
        tools = listOf(
            ToolPrompt(
                name = "sleep",
                description = "Demonstration tool that pauses briefly.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "duration_ms", type = "integer", description = "milliseconds, default 1000, >= 0", required = false, default = "1000")
                )
            ),
            ToolPrompt(
                name = "use_package",
                description = "Activate a package for use in the current session.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "package_name",
                        type = "string",
                        description = "name of the package to activate",
                        required = true
                    )
                )
            )
        )
    )
    
    val basicToolsCn = SystemToolPromptCategory(
        categoryName = "可用工具",
        tools = listOf(
            ToolPrompt(
                name = "sleep",
                description = "演示工具，短暂暂停。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "duration_ms", type = "integer", description = "毫秒，默认1000，>= 0", required = false, default = "1000")
                )
            ),
            ToolPrompt(
                name = "use_package",
                description = "在当前会话中激活包。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "package_name", type = "string", description = "要激活的包名", required = true)
                )
            )
        )
    )
    
    // ==================== 文件系统工具 ====================
    val fileSystemTools = SystemToolPromptCategory(
        categoryName = "File System Tools",
        tools = listOf(
            ToolPrompt(
                name = "ssh_login",
                description = "Login to a remote SSH server. After logging in, all file tools with environment=\"linux\" will use this SSH connection instead of the local Ubuntu 24 terminal.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "host", type = "string", description = "SSH server address", required = true),
                    ToolParameterSchema(name = "port", type = "integer", description = "optional", required = false, default = "22"),
                    ToolParameterSchema(name = "username", type = "string", description = "required", required = true),
                    ToolParameterSchema(name = "password", type = "string", description = "required", required = true),
                    ToolParameterSchema(name = "enable_reverse_mount", type = "boolean", description = "optional, enables reverse mounting of local storage to remote server", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "ssh_exit",
                description = "Logout from the SSH connection. After logout, file tools will resume using the local Ubuntu 24 terminal.",
                parametersStructured = listOf()
            ),
            ToolPrompt(
                name = "list_files",
                description = "List files in a directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "e.g. \"/sdcard/Download\"", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false)
                )
            ),
            ToolPrompt(
                name = "read_file",
                description = "Read the content of a file. For image files (jpg, jpeg, png, gif, bmp), it automatically extracts text using OCR.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "path",
                        type = "string",
                        description = "file path",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "environment",
                        type = "string",
                        description = "optional, execution environment. Values: \"android\" (default, Android file system) | \"linux\" (local Ubuntu 24 terminal environment via proot; Linux paths like /home/... /etc/hosts) | \"repo:<repositoryName>\" (attached local storage repository)",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "intent",
                        type = "string",
                        description = "optional, your question about the media/file (used for backend recognition)",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_image",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"image\"> tag for models that support vision",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_audio",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"audio\"> tag for models that support audio",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_video",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"video\"> tag for models that support video",
                        required = false
                    )
                )
            ),
            ToolPrompt(
                name = "read_file_part",
                description = "Read file content by line range.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "start_line", type = "integer", description = "starting line number, 1-indexed", required = false, default = "1"),
                    ToolParameterSchema(name = "end_line", type = "integer", description = "ending line number, 1-indexed, inclusive, optional", required = false, default = "start_line + 99")
                )
            ),
            ToolPrompt(
                name = "apply_file",
                description = "Applies edits to a file by finding and replacing/deleting a matched content block.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "type", type = "string", description = "operation type: replace | delete | create", required = true),
                    ToolParameterSchema(name = "old", type = "string", description = "the exact content to be matched and replaced/deleted (required for replace/delete)", required = false),
                    ToolParameterSchema(name = "new", type = "string", description = "the new content to insert (required for replace/create)", required = false)
                ),
                details = """
  - **How it works**:
    - The tool finds the best fuzzy match of `old` in the current file content (not by line numbers) and applies the requested operation.
    - You can call this tool multiple times to apply multiple independent edits.

  - **Parameters**:
    - `type`:
      - `replace`: replace the matched `old` content with `new`
      - `delete`: delete the matched `old` content
      - `create`: create the file when it does not exist (write `new` as full file content)
    - `old`: required for `replace` / `delete`
    - `new`: required for `replace` / `create`

  - **CRITICAL RULES**:
    1. **If you need to rewrite a whole existing file**: do **NOT** use apply_file to overwrite it. Instead, call `delete_file` first, then `write_file`.
    2. **If you need to modify an existing file**: you **MUST** use `type=replace` (or `type=delete`) and provide `old` / `new`. Do **NOT** delete the whole file and rewrite it.
"""
            ),
            ToolPrompt(
                name = "delete_file",
                description = "Delete a file or directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "target path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "recursive", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "make_directory",
                description = "Create a directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "directory path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "create_parents", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "find_files",
                description = "Search for files matching a pattern.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "search path, for Android use /sdcard/..., for Linux use /home/... or /etc/...", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "search pattern, e.g. \"*.jpg\"", required = true),
                    ToolParameterSchema(name = "max_depth", type = "integer", description = "optional, controls depth of subdirectory search, -1=unlimited", required = false),
                    ToolParameterSchema(name = "use_path_pattern", type = "boolean", description = "boolean", required = false, default = "false"),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "grep_code",
                description = "Search code content matching a regex pattern in files. Returns matches with surrounding context lines.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "search path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "regex pattern", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "file filter", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "boolean", required = false, default = "false"),
                    ToolParameterSchema(name = "context_lines", type = "integer", description = "lines of context before/after match", required = false, default = "3"),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "max matches", required = false, default = "100")
                )
            ),
            ToolPrompt(
                name = "grep_context",
                description = "Search for relevant content based on intent/context understanding. Supports two modes: 1) Directory mode: when path is a directory, finds most relevant files. 2) File mode: when path is a file, finds most relevant code segments within that file. Uses semantic relevance scoring.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "directory or file path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "intent", type = "string", description = "intent or context description string", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "file filter for directory mode", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "maximum items to return", required = false, default = "10")
                )
            ),
            ToolPrompt(
                name = "download_file",
                description = "Download a file from the internet. Two modes: (1) Provide `url` + `destination`. (2) Provide `visit_key` + (`link_number` or `image_number`) + `destination` to download an item by index from a previous `visit_web` result.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "optional, file URL. If omitted, use visit_key + link_number/image_number to download from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "optional, visitKey from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "optional, 1-based link index from Results (use with visit_key)", required = false),
                    ToolParameterSchema(name = "image_number", type = "integer", description = "optional, 1-based image index from Images (use with visit_key)", required = false),
                    ToolParameterSchema(name = "destination", type = "string", description = "save path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "optional HTTP headers as JSON object string, e.g. {\"Referer\":\"...\"}", required = false)
                )
            )
        )
    )
    
    val fileSystemToolsCn = SystemToolPromptCategory(
        categoryName = "文件系统工具",
        tools = listOf(
            ToolPrompt(
                name = "ssh_login",
                description = "登录远程SSH服务器。登录后，所有environment=\"linux\"的文件工具都将使用此SSH连接，而不是本地Ubuntu 24终端。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "host", type = "string", description = "SSH服务器地址", required = true),
                    ToolParameterSchema(name = "port", type = "integer", description = "可选", required = false, default = "22"),
                    ToolParameterSchema(name = "username", type = "string", description = "必填", required = true),
                    ToolParameterSchema(name = "password", type = "string", description = "必填", required = true),
                    ToolParameterSchema(name = "enable_reverse_mount", type = "boolean", description = "可选，布尔值，启用后将本地存储反向挂载到远程服务器", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "ssh_exit",
                description = "退出SSH连接。退出后，文件工具将恢复使用本地Ubuntu 24终端。",
                parametersStructured = listOf()
            ),
            ToolPrompt(
                name = "list_files",
                description = "列出目录中的文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "例如\"/sdcard/Download\"", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false)
                )
            ),
            ToolPrompt(
                name = "read_file",
                description = "读取文件内容。对于图片文件(jpg, jpeg, png, gif, bmp)，自动使用OCR提取文本。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(
                        name = "environment",
                        type = "string",
                        description = "可选，执行环境。取值：\"android\"（默认，Android文件系统）| \"linux\"（本地Ubuntu 24终端环境，通过proot实现；路径用Linux格式，如/home/...、/etc/hosts）| \"repo:<仓库名>\"（附加本地储存仓库）",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "intent",
                        type = "string",
                        description = "可选，用户对媒体/文件的问题（用于后端识别模型）",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_image",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"image\">标签供支持识图的模型直接查看",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_audio",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"audio\">标签供支持音频的模型直接处理",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_video",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"video\">标签供支持视频的模型直接处理",
                        required = false
                    )
                )
            ),
            ToolPrompt(
                name = "read_file_part",
                description = "按行号范围读取文件内容。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "start_line", type = "integer", description = "起始行号，从1开始", required = false, default = "1"),
                    ToolParameterSchema(name = "end_line", type = "integer", description = "结束行号，从1开始，包括该行，可选", required = false, default = "start_line + 99")
                )
            ),
            ToolPrompt(
                name = "apply_file",
                description = "通过查找并替换/删除匹配的内容块来编辑文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "type", type = "string", description = "操作类型：replace | delete | create", required = true),
                    ToolParameterSchema(name = "old", type = "string", description = "用于匹配/替换/删除的原始内容（replace/delete必填）", required = false),
                    ToolParameterSchema(name = "new", type = "string", description = "要插入的新内容（replace/create必填）", required = false)
                ),
                details = """
  - **工作原理**:
    - 工具会在文件当前内容中对 `old` 做最佳的模糊匹配（不依赖行号），然后执行指定操作。
    - 你可以多次调用本工具，对同一个文件做多处独立修改。

  - **参数**:
    - `type`:
      - `replace`: 用 `new` 替换匹配到的 `old`
      - `delete`: 删除匹配到的 `old`
      - `create`: 当文件不存在时创建文件（用 `new` 作为完整文件内容）
    - `old`: `replace` / `delete` 必填
    - `new`: `replace` / `create` 必填

  - **关键规则**:
    1. **如果需要重写整个已存在文件**：不要用 apply_file 直接覆盖。请先 `delete_file`，再 `write_file`。
    2. **如果需要修改已存在文件**：必须用 `type=replace`（或 `type=delete`）并提供 `old/new`（或 `old`）。不要删除整个文件再重写。
"""
            ),
            ToolPrompt(
                name = "delete_file",
                description = "删除文件或目录。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目标路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "recursive", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "make_directory",
                description = "创建目录。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目录路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "create_parents", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "find_files",
                description = "搜索匹配模式的文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "搜索路径，Android用/sdcard/...，Linux用/home/...或/etc/...", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "搜索模式，例如\"*.jpg\"", required = true),
                    ToolParameterSchema(name = "max_depth", type = "integer", description = "可选，控制子目录搜索深度，-1=无限", required = false),
                    ToolParameterSchema(name = "use_path_pattern", type = "boolean", description = "布尔值", required = false, default = "false"),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "grep_code",
                description = "在文件中搜索匹配正则表达式的代码内容，返回带上下文的匹配结果。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "搜索路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "正则表达式模式", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "文件过滤", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "布尔值", required = false, default = "false"),
                    ToolParameterSchema(name = "context_lines", type = "integer", description = "匹配行前后的上下文行数", required = false, default = "3"),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "最大匹配数", required = false, default = "100")
                )
            ),
            ToolPrompt(
                name = "grep_context",
                description = "基于意图/上下文理解搜索相关内容。支持两种模式：1) 目录模式：当path是目录时，找出最相关的文件。2) 文件模式：当path是文件时，找出该文件内最相关的代码段。使用语义相关性评分。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目录或文件路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "intent", type = "string", description = "意图或上下文描述字符串", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "目录模式下的文件过滤", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "返回的最大项数", required = false, default = "10")
                )
            ),
            ToolPrompt(
                name = "download_file",
                description = "从互联网下载文件。有两种用法：1）提供 `url` + `destination` 直接下载。2）提供 `visit_key` +（`link_number` 或 `image_number`）+ `destination`，从上一次 `visit_web` 的 Results/Images 编号中按序号下载。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "可选, 文件URL。不传时可使用 visit_key + link_number/image_number 从上一次 visit_web 结果按编号下载", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "可选, 上一次 visit_web 返回的 visitKey", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "可选, 整数, Results 中的链接编号（从1开始，需要配合 visit_key）", required = false),
                    ToolParameterSchema(name = "image_number", type = "integer", description = "可选, 整数, Images 中的图片编号（从1开始，需要配合 visit_key）", required = false),
                    ToolParameterSchema(name = "destination", type = "string", description = "保存路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "可选：HTTP请求头，JSON对象字符串，例如{\"Referer\":\"...\"}", required = false)
                )
            )
        )
    )
    
    // ==================== HTTP工具 ====================
    val httpTools = SystemToolPromptCategory(
        categoryName = "HTTP Tools",
        tools = listOf(
            ToolPrompt(
                name = "visit_web",
                description = "Visit a webpage and extract information (including optional image links). Two modes: (1) Provide `url` to visit a new page. (2) Follow a link from a previous visit by providing `visit_key` + `link_number`. The returned text often includes a `Results:` section like `[1] ...`, `[2] ...` — those bracketed numbers are 1-based indices. Use that exact number as `link_number` (range: 1..links.length). If you need images, set `include_image_links=true` and the tool will return an `Images:` section with 1-based indices. IMPORTANT: do NOT use `link_number` to download images; instead use `download_file` with `visit_key` + `image_number`. NOTE: this tool is browsing-only/read-only and does not perform interactive actions such as login, click, fill, submit, or workflow automation.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "optional, webpage URL", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "optional, string, the visitKey from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "optional, int, 1-based index of the link to follow (matches the `[n]` in Results; range 1..links.length)", required = false),
                    ToolParameterSchema(name = "include_image_links", type = "boolean", description = "optional, boolean, when true include extracted image links in the result (imageLinks)", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "optional HTTP headers as JSON object string, e.g. {\"Referer\":\"...\"}", required = false),
                    ToolParameterSchema(name = "user_agent_preset", type = "string", description = "optional, quick select user agent: desktop/android", required = false),
                    ToolParameterSchema(name = "user_agent", type = "string", description = "optional, full custom user agent override", required = false)
                )
            )
        )
    )
    
    val httpToolsCn = SystemToolPromptCategory(
        categoryName = "HTTP工具",
        tools = listOf(
            ToolPrompt(
                name = "visit_web",
                description = "访问网页并提取信息（可选包含图片链接）。有两种用法：1）提供 `url` 访问新页面。2）提供上一次 visit_web 返回的 `visit_key` + `link_number`，用来继续访问结果里的某个链接。返回文本通常会包含 `Results:` 段落，形如 `[1] ...`、`[2] ...` —— 中括号里的数字是从 1 开始的编号，请把该编号原样作为 `link_number`（范围：1..links.length），不要按 0 起始。若需要图片，请设置 `include_image_links=true`，工具会额外返回 `Images:` 段落以及从 1 开始的图片编号。重要：下载图片不要用 `link_number` 乱点页面链接；请使用 `download_file` 的 `visit_key` + `image_number` 按图片编号下载。注意：该工具仅支持浏览/读取操作，不执行登录、点击、填写、提交等交互自动化。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "可选, 网页URL", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "可选, 字符串, 上一次 visit_web 返回的 visitKey", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "可选, 整数, 要继续访问的链接编号（从1开始，对应 Results 里的 `[n]`；范围 1..links.length）", required = false),
                    ToolParameterSchema(name = "include_image_links", type = "boolean", description = "可选, boolean, 为 true 时在结果中额外包含提取到的图片链接列表（imageLinks）", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "可选：HTTP请求头，JSON对象字符串，例如{\"Referer\":\"...\"}", required = false),
                    ToolParameterSchema(name = "user_agent_preset", type = "string", description = "可选：UA预设，快速选择：desktop/android", required = false),
                    ToolParameterSchema(name = "user_agent", type = "string", description = "可选：完整自定义UA（优先级高于预设）", required = false)
                )
            )
        )
    )
    
    // ==================== 记忆库工具 ====================
    val memoryTools = SystemToolPromptCategory(
        categoryName = "Memory and Memory Library Tools",
        tools = listOf(
            ToolPrompt(
                name = "query_memory",
                description = "Searches the memory library for relevant memories using hybrid search (keyword matching + semantic understanding). Use this when you need to recall past knowledge, look up specific information, or require context. Keywords can be separated by '|' or spaces - each keyword will be independently matched semantically and the results will be combined with weighted scoring. You can use \"*\" as the query to return all memories (optionally filtered by folder_path). You can also filter by creation time using `start_time` / `end_time` (Unix milliseconds). When the user attaches a memory folder, a `<memory_context>` will be provided in the prompt. You MUST use the `folder_path` parameter to restrict the search to that folder. **IMPORTANT**: For document nodes (uploaded files), this tool uses vector search to return ONLY the most relevant chunks matching your query, NOT the entire document. Results show \"Document: [name], Chunk X/Y: [content]\" format. To read the complete document or specific parts, use `get_memory_by_title` instead. **NOTE**: When limit > 20, results will only show titles and truncated content to save tokens.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "string, the keyword or question to search for, or \"*\" to return all memories", required = true),
                    ToolParameterSchema(name = "folder_path", type = "string", description = "optional, string, the specific folder path to search within", required = false),
                    ToolParameterSchema(name = "start_time", type = "integer", description = "optional, Unix timestamp in milliseconds. Filters memories by createdAt >= start_time", required = false),
                    ToolParameterSchema(name = "end_time", type = "integer", description = "optional, Unix timestamp in milliseconds. Filters memories by createdAt <= end_time", required = false),
                    ToolParameterSchema(name = "threshold", type = "number", description = "optional, float 0.0-1.0, semantic similarity threshold, lower values return more results", required = false, default = "0.25"),
                    ToolParameterSchema(name = "limit", type = "integer", description = "optional, int >= 1, maximum number of results to return. When > 20, only titles and truncated content are returned", required = false, default = "5")
                )
            ),
            ToolPrompt(
                name = "get_memory_by_title",
                description = "Retrieves a memory by exact title. For regular memories, returns full content. For document nodes (uploaded files), you can: 1) Read entire document (no parameters), 2) Read specific chunk(s) via `chunk_index` (e.g., \"3\") or `chunk_range` (e.g., \"3-7\"), 3) Search within document via `query`. Use this when query_memory returns partial results and you need more complete content.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "title", type = "string", description = "required, string, the exact title of the memory", required = true),
                    ToolParameterSchema(name = "chunk_index", type = "integer", description = "optional, int, read a specific chunk by its number, e.g., 3 for the 3rd chunk", required = false),
                    ToolParameterSchema(name = "chunk_range", type = "string", description = "optional, string, read a range of chunks in \"start-end\" format, e.g., \"3-7\" for chunks 3 through 7", required = false),
                    ToolParameterSchema(name = "query", type = "string", description = "optional, string, search for matching chunks within the document using keywords or semantic search", required = false)
                )
            )
        ),
        categoryFooter = "\nNote: The memory library and user personality profile are automatically updated by a separate system after you output the task completion marker. However, if you need to manage memories immediately or update user preferences, use the appropriate tools directly."
    )
    
    val memoryToolsCn = SystemToolPromptCategory(
        categoryName = "记忆与记忆库工具",
        tools = listOf(
            ToolPrompt(
                name = "query_memory",
                description = "使用混合搜索（关键词匹配 + 语义理解）从记忆库中搜索相关记忆。当需要回忆过去的知识、查找特定信息或需要上下文时使用。关键词可以使用\"|\"或空格分隔 - 每个关键词都会独立进行语义匹配，结果将通过加权评分合并。可以使用 \"*\" 作为查询来返回所有记忆（可通过 folder_path 过滤）。也可以使用 `start_time` / `end_time`（Unix 毫秒时间戳）按创建时间过滤。当用户附加记忆文件夹时，提示中会提供`<memory_context>`。你必须使用 `folder_path` 参数将搜索限制在该文件夹内。**重要**：对于文档节点（上传的文件），此工具使用向量搜索只返回与查询最相关的分块，而不是整个文档。结果显示\"Document: [文档名], Chunk X/Y: [内容]\"格式。如需阅读完整文档或特定部分，请改用 `get_memory_by_title` 工具。**注意**：当 limit > 20 时，结果将只显示标题和截断内容以节省令牌。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "string, 搜索的关键词或问题, 或使用 \"*\" 返回所有记忆", required = true),
                    ToolParameterSchema(name = "folder_path", type = "string", description = "可选, string, 要搜索的特定文件夹路径", required = false),
                    ToolParameterSchema(name = "start_time", type = "integer", description = "可选, Unix时间戳（毫秒）。按创建时间过滤 createdAt >= start_time", required = false),
                    ToolParameterSchema(name = "end_time", type = "integer", description = "可选, Unix时间戳（毫秒）。按创建时间过滤 createdAt <= end_time", required = false),
                    ToolParameterSchema(name = "threshold", type = "number", description = "可选, float 0.0-1.0, 语义相似度阈值, 较低的值返回更多结果", required = false, default = "0.25"),
                    ToolParameterSchema(name = "limit", type = "integer", description = "可选, int >= 1, 返回结果的最大数量. 当 > 20 时，只返回标题和截断内容", required = false, default = "5")
                )
            ),
            ToolPrompt(
                name = "get_memory_by_title",
                description = "通过精确标题检索记忆。对于普通记忆，返回完整内容。对于文档节点（上传的文件），可以：1) 读取整个文档（不提供参数），2) 通过 `chunk_index`（如\"3\"）或 `chunk_range`（如\"3-7\"）读取特定分块，3) 通过 `query` 在文档内搜索。当 query_memory 返回部分结果而你需要更完整内容时使用。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "title", type = "string", description = "必需, 字符串, 记忆的精确标题", required = true),
                    ToolParameterSchema(name = "chunk_index", type = "integer", description = "可选, 整数, 读取特定编号的分块, 例如3表示第3块", required = false),
                    ToolParameterSchema(name = "chunk_range", type = "string", description = "可选, 字符串, 读取分块范围，格式为\"起始-结束\"，例如\"3-7\"表示第3到第7块", required = false),
                    ToolParameterSchema(name = "query", type = "string", description = "可选, 字符串, 使用关键词或语义搜索在文档内查找匹配的分块", required = false)
                )
            )
        ),
        categoryFooter = "\n注意：记忆库和用户性格档案会在你输出任务完成标志后由独立的系统自动更新。但是，如果需要立即管理记忆或更新用户偏好，请直接使用相应的工具。"
    )

    private val internalToolCategoriesEn: List<SystemToolPromptCategory> = SystemToolPromptsInternal.internalToolCategoriesEn
    private val internalToolCategoriesCn: List<SystemToolPromptCategory> = SystemToolPromptsInternal.internalToolCategoriesCn
    
    /**
     * 获取所有英文工具分类
     * @param hasBackendImageRecognition 是否配置了后端识图服务（IMAGE_RECOGNITION功能）
     * @param chatModelHasDirectImage 当前聊天模型是否自带识图能力（可直接看图片）
     */
    fun getAIAllCategoriesEn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        val shouldExposeIntent =
            (hasBackendImageRecognition && !chatModelHasDirectImage) ||
                (hasBackendAudioRecognition && !chatModelHasDirectAudio) ||
                (hasBackendVideoRecognition && !chatModelHasDirectVideo)

        val adjustedFileSystemTools = fileSystemTools.copy(
            tools = fileSystemTools.tools.map { tool ->
                if (tool.name != "read_file") return@map tool

                val filteredParams = (tool.parametersStructured ?: emptyList()).filter { param ->
                    when (param.name) {
                        "direct_image" -> false
                        "direct_audio" -> false
                        "direct_video" -> false
                        "intent" -> shouldExposeIntent
                        else -> true
                    }
                }

                val adjustedDescription =
                    if (shouldExposeIntent) {
                        "Read the content of a file. For media files, you can also provide an 'intent' parameter to use a backend recognition model for analysis."
                    } else {
                        tool.description
                    }

                tool.copy(
                    description = adjustedDescription + buildSafBookmarksSectionEn(safBookmarkNames),
                    parametersStructured = filteredParams
                )
            }
        )

        return listOf(
            basicTools,
            adjustedFileSystemTools,
            httpTools,
            memoryTools
        )
    }

    fun getAllCategoriesEn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        return getAIAllCategoriesEn(
            hasBackendImageRecognition = hasBackendImageRecognition,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasBackendAudioRecognition,
            hasBackendVideoRecognition = hasBackendVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames
        ) + internalToolCategoriesEn
    }
    
    /**
     * 获取所有中文工具分类
     * @param hasBackendImageRecognition 是否配置了后端识图服务（IMAGE_RECOGNITION功能）
     * @param chatModelHasDirectImage 当前聊天模型是否自带识图能力（可直接看图片）
     */
    fun getAIAllCategoriesCn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        val shouldExposeIntent =
            (hasBackendImageRecognition && !chatModelHasDirectImage) ||
                (hasBackendAudioRecognition && !chatModelHasDirectAudio) ||
                (hasBackendVideoRecognition && !chatModelHasDirectVideo)

        val adjustedFileSystemTools = fileSystemToolsCn.copy(
            tools = fileSystemToolsCn.tools.map { tool ->
                if (tool.name != "read_file") return@map tool

                val filteredParams = (tool.parametersStructured ?: emptyList()).filter { param ->
                    when (param.name) {
                        "direct_image" -> false
                        "direct_audio" -> false
                        "direct_video" -> false
                        "intent" -> shouldExposeIntent
                        else -> true
                    }
                }

                val adjustedDescription =
                    if (shouldExposeIntent) {
                        "读取文件内容。对于媒体文件，你也可以提供 intent 参数，使用后端识别模型进行分析。"
                    } else {
                        tool.description
                    }

                tool.copy(
                    description = adjustedDescription + buildSafBookmarksSectionCn(safBookmarkNames),
                    parametersStructured = filteredParams
                )
            }
        )

        return listOf(
            basicToolsCn,
            adjustedFileSystemTools,
            httpToolsCn,
            memoryToolsCn
        )
    }

    fun getAllCategoriesCn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        return getAIAllCategoriesCn(
            hasBackendImageRecognition = hasBackendImageRecognition,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasBackendAudioRecognition,
            hasBackendVideoRecognition = hasBackendVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames
        ) + internalToolCategoriesCn
    }

    data class ManageableToolPrompt(
        val categoryName: String,
        val name: String,
        val description: String
    )

    private fun applyToolVisibility(
        categories: List<SystemToolPromptCategory>,
        toolVisibility: Map<String, Boolean>
    ): List<SystemToolPromptCategory> {
        if (toolVisibility.isEmpty()) return categories
        return categories.mapNotNull { category ->
            val visibleTools = category.tools.filter { tool ->
                toolVisibility[tool.name] ?: true
            }
            if (visibleTools.isEmpty()) {
                null
            } else {
                category.copy(tools = visibleTools)
            }
        }
    }

    fun getManageableToolPrompts(useEnglish: Boolean): List<ManageableToolPrompt> {
        val baseCategories = if (useEnglish) {
            listOf(basicTools, fileSystemTools, httpTools, memoryTools)
        } else {
            listOf(basicToolsCn, fileSystemToolsCn, httpToolsCn, memoryToolsCn)
        }

        return baseCategories
            .flatMap { category ->
                category.tools.map { tool ->
                    ManageableToolPrompt(
                        categoryName = category.categoryName,
                        name = tool.name,
                        description = tool.description
                    )
                }
            }
            .distinctBy { it.name }
    }

    fun generateMemoryToolsPromptEn(
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        return applyToolVisibility(listOf(memoryTools), toolVisibility)
            .firstOrNull()
            ?.toString()
            .orEmpty()
    }

    fun generateMemoryToolsPromptCn(
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        return applyToolVisibility(listOf(memoryToolsCn), toolVisibility)
            .firstOrNull()
            ?.toString()
            .orEmpty()
    }
    
    /**
     * 生成完整的工具提示词文本（英文）
     */
    fun generateToolsPromptEn(
        hasBackendImageRecognition: Boolean = false,
        includeMemoryTools: Boolean = true,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        val categories = if (includeMemoryTools) {
            getAIAllCategoriesEn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
        } else {
            getAIAllCategoriesEn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
                .filter { it.categoryName != "Memory and Memory Library Tools" }
        }

        return applyToolVisibility(categories, toolVisibility).joinToString("\n\n") { it.toString() }
    }
    
    /**
     * 生成完整的工具提示词文本（中文）
     */
    fun generateToolsPromptCn(
        hasBackendImageRecognition: Boolean = false,
        includeMemoryTools: Boolean = true,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        val categories = if (includeMemoryTools) {
            getAIAllCategoriesCn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
        } else {
            getAIAllCategoriesCn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
                .filter { it.categoryName != "记忆与记忆库工具" }
        }

        return applyToolVisibility(categories, toolVisibility).joinToString("\n\n") { it.toString() }
    }
}
