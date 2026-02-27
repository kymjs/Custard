package com.ai.assistance.custard.core.config

import com.ai.assistance.custard.data.model.SystemToolPromptCategory
import com.ai.assistance.custard.data.model.ToolPrompt
import com.ai.assistance.custard.data.model.ToolParameterSchema

object SystemToolPromptsInternal {

    val internalToolCategoriesEn: List<SystemToolPromptCategory> =
        listOf(
            SystemToolPromptCategory(
                categoryName = "Internal Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "execute_shell",
                            description = "Execute a device shell command.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "command",
                                        type = "string",
                                        description = "shell command to execute",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "create_terminal_session",
                            description = "Create or get a terminal session.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_name",
                                        type = "string",
                                        description = "terminal session name",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "execute_in_terminal_session",
                            description = "Execute a command in a terminal session and collect full output.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "terminal session id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "command",
                                        type = "string",
                                        description = "command to execute",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "optional, command timeout in milliseconds",
                                        required = false,
                                        default = "1800000"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "input_in_terminal_session",
                            description = "Write input to a terminal session. At least one of input or control is required. Typical usage is sending input first, then control=enter to submit.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "terminal session id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "input",
                                        type = "string",
                                        description = "text to write to the terminal (can include newlines)",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "control",
                                        type = "string",
                                        description = "control key or modifier (e.g. enter/tab/esc/up/down/left/right/home/end/pageup/pagedown, or ctrl with input=c for Ctrl+C)",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "close_terminal_session",
                            description = "Close a terminal session.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "terminal session id",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_terminal_session_screen",
                            description = "Get only the current visible PTY screen content for a terminal session (single screen, no scrollback/history).",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "terminal session id",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "start_web",
                            description = "Start a persistent web session and open a floating browser window.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "url",
                                        type = "string",
                                        description = "optional, initial URL to open",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "headers",
                                        type = "string",
                                        description = "optional, JSON object string for request headers",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "user_agent",
                                        type = "string",
                                        description = "optional, custom user agent",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "session_name",
                                        type = "string",
                                        description = "optional, label shown in the floating window title",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "stop_web",
                            description = "Stop one web session or all web sessions.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "optional when close_all=true, web session id",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "close_all",
                                        type = "boolean",
                                        description = "optional, stop all sessions when true",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_navigate",
                            description = "Navigate a web session to a URL.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "web session id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "url",
                                        type = "string",
                                        description = "target URL",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "headers",
                                        type = "string",
                                        description = "optional, JSON object string for request headers",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_eval",
                            description = "Run JavaScript in a web session.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "web session id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "script",
                                        type = "string",
                                        description = "JavaScript source code",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "optional, script timeout in milliseconds",
                                        required = false,
                                        default = "10000"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_click",
                            description = "Click an element by snapshot ref.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "optional, web session id",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "ref",
                                        type = "string",
                                        description = "required, exact target element ref from web_snapshot output",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "element",
                                        type = "string",
                                        description = "optional, human-readable element description",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "button",
                                        type = "string",
                                        description = "optional, left/right/middle",
                                        required = false,
                                        default = "left"
                                    ),
                                    ToolParameterSchema(
                                        name = "modifiers",
                                        type = "string",
                                        description = "optional JSON array, only Alt/Control/ControlOrMeta/Meta/Shift",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "doubleClick",
                                        type = "boolean",
                                        description = "optional, perform double click",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_fill",
                            description = "Fill an input element by CSS selector.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "web session id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "selector",
                                        type = "string",
                                        description = "CSS selector",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "value",
                                        type = "string",
                                        description = "value to set",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_file_upload",
                            description = "Upload one or multiple files to an active file chooser in a web session. `paths` is optional; omit it to cancel the file chooser.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "optional, web session id",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "paths",
                                        type = "string",
                                        description = "optional JSON array of absolute file paths, e.g. [\"/sdcard/Download/a.txt\"]",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_wait_for",
                            description = "Wait for page ready or selector appearance in a web session.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "web session id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "selector",
                                        type = "string",
                                        description = "optional, CSS selector to wait for",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "optional, wait timeout in milliseconds",
                                        required = false,
                                        default = "10000"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_snapshot",
                            description = "Capture current page snapshot text from a web session.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "web session id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "include_links",
                                        type = "boolean",
                                        description = "optional, include link list",
                                        required = false,
                                        default = "true"
                                    ),
                                    ToolParameterSchema(
                                        name = "include_images",
                                        type = "boolean",
                                        description = "optional, include image list",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "calculate",
                            description = "Evaluate a math expression.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "expression",
                                        type = "string",
                                        description = "math expression, e.g. \"(1+2)*3\"",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "execute_intent",
                            description = "Execute an Android Intent (activity/broadcast/service).",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "action",
                                        type = "string",
                                        description = "optional, intent action",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "uri",
                                        type = "string",
                                        description = "optional, data URI",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "package",
                                        type = "string",
                                        description = "optional, package name",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "component",
                                        type = "string",
                                        description = "optional, component in \"package/class\" format",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "type",
                                        type = "string",
                                        description = "optional, one of activity/broadcast/service",
                                        required = false,
                                        default = "activity"
                                    ),
                                    ToolParameterSchema(
                                        name = "flags",
                                        type = "string",
                                        description = "optional, JSON array string of int flags (or a single int)",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extras",
                                        type = "string",
                                        description = "optional, JSON object string for extras",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "send_broadcast",
                            description = "Send a broadcast intent.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "action",
                                        type = "string",
                                        description = "required, broadcast action",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "uri",
                                        type = "string",
                                        description = "optional, data URI",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "package",
                                        type = "string",
                                        description = "optional, package name",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "component",
                                        type = "string",
                                        description = "optional, component in \"package/class\" format",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extras",
                                        type = "string",
                                        description = "optional, JSON object string for extras",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_key",
                                        type = "string",
                                        description = "optional, a single string extra key",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_value",
                                        type = "string",
                                        description = "optional, a single string extra value",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_key2",
                                        type = "string",
                                        description = "optional, second string extra key",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_value2",
                                        type = "string",
                                        description = "optional, second string extra value",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "device_info",
                            description = "Get device information.",
                            parametersStructured = listOf()
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Extended Memory Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "create_memory",
                            description = "Creates a new memory node in the library. Use this when you want to save important information for future reference.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "title", type = "string", description = "required, string", required = true),
                                ToolParameterSchema(name = "content", type = "string", description = "required, string", required = true),
                                ToolParameterSchema(name = "content_type", type = "string", description = "optional", required = false, default = "\"text/plain\""),
                                ToolParameterSchema(name = "source", type = "string", description = "optional", required = false, default = "\"ai_created\""),
                                ToolParameterSchema(name = "folder_path", type = "string", description = "optional", required = false, default = "\"\"")
                            )
                        ),
                        ToolPrompt(
                            name = "update_memory",
                            description = "Updates an existing memory node by title. Use this to modify an existing memory's content or metadata.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "old_title", type = "string", description = "required, string to identify the memory", required = true),
                                ToolParameterSchema(name = "new_title", type = "string", description = "optional, string, new title if renaming", required = false),
                                ToolParameterSchema(name = "content", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "content_type", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "source", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "credibility", type = "number", description = "optional, float 0-1", required = false),
                                ToolParameterSchema(name = "importance", type = "number", description = "optional, float 0-1", required = false),
                                ToolParameterSchema(name = "folder_path", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "tags", type = "string", description = "optional, comma-separated string", required = false)
                            )
                        ),
                        ToolPrompt(
                            name = "delete_memory",
                            description = "Deletes a memory node from the library by title. Use with caution as this operation is irreversible.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "title", type = "string", description = "required, string to identify the memory", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "link_memories",
                            description = "Creates a semantic link between two memories in the library. Use this to establish relationships between related concepts, facts, or pieces of information. This helps build a knowledge graph structure for better memory retrieval and understanding.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source_title", type = "string", description = "required, string, the title of the source memory", required = true),
                                ToolParameterSchema(name = "target_title", type = "string", description = "required, string, the title of the target memory", required = true),
                                ToolParameterSchema(name = "link_type", type = "string", description = "optional, string, the type of relationship such as \"related\", \"causes\", \"explains\", \"part_of\", \"contradicts\", etc.", required = false, default = "\"related\""),
                                ToolParameterSchema(name = "weight", type = "number", description = "optional, float 0.0-1.0, the strength of the link with 1.0 being strongest", required = false, default = "0.7"),
                                ToolParameterSchema(name = "description", type = "string", description = "optional, string, additional context about the relationship", required = false, default = "\"\"")
                            )
                        ),
                        ToolPrompt(
                            name = "query_memory_links",
                            description = "Queries links in the memory graph. Supports filtering by link_id, source_title, target_title, and link_type. Use this before updating/deleting links to precisely identify targets.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "link_id", type = "integer", description = "optional, exact link id", required = false),
                                ToolParameterSchema(name = "source_title", type = "string", description = "optional, exact source memory title", required = false),
                                ToolParameterSchema(name = "target_title", type = "string", description = "optional, exact target memory title", required = false),
                                ToolParameterSchema(name = "link_type", type = "string", description = "optional, relation type filter", required = false),
                                ToolParameterSchema(name = "limit", type = "integer", description = "optional, int 1-200, maximum links to return", required = false, default = "20")
                            )
                        ),
                        ToolPrompt(
                            name = "update_user_preferences",
                            description = "Updates user preference information directly. Use this when you learn new information about the user that should be remembered (e.g., their birthday, gender, personality traits, identity, occupation, or preferred AI interaction style). This allows immediate updates without waiting for the automatic system.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "birth_date", type = "integer", description = "optional, Unix timestamp in milliseconds", required = false),
                                ToolParameterSchema(name = "gender", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "personality", type = "string", description = "optional, string describing personality traits", required = false),
                                ToolParameterSchema(name = "identity", type = "string", description = "optional, string describing identity/role", required = false),
                                ToolParameterSchema(name = "occupation", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "ai_style", type = "string", description = "optional, string describing preferred AI interaction style. At least one parameter must be provided", required = false)
                            )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Extended HTTP Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "http_request",
                            description = "Send HTTP request.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "url", type = "string", description = "url", required = true),
                                ToolParameterSchema(name = "method", type = "string", description = "GET/POST/PUT/DELETE", required = true),
                                ToolParameterSchema(name = "headers", type = "string", description = "headers", required = false),
                                ToolParameterSchema(name = "body", type = "string", description = "body", required = false),
                                ToolParameterSchema(name = "body_type", type = "string", description = "json/form/text/xml", required = false)
                            )
                        ),
                        ToolPrompt(
                            name = "multipart_request",
                            description = "Upload files.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "url", type = "string", description = "url", required = true),
                                ToolParameterSchema(name = "method", type = "string", description = "POST/PUT", required = true),
                                ToolParameterSchema(name = "headers", type = "string", description = "headers", required = false),
                                ToolParameterSchema(name = "form_data", type = "string", description = "form_data", required = false),
                                ToolParameterSchema(name = "files", type = "string", description = "JSON array string. Each item is an object: {\"field_name\": string, \"file_path\": string, \"content_type\"?: string, \"file_name\"?: string}", required = false)
                            )
                        ),
                        ToolPrompt(
                            name = "manage_cookies",
                            description = "Manage cookies.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "action", type = "string", description = "get/set/clear", required = true),
                                ToolParameterSchema(name = "domain", type = "string", description = "domain", required = false),
                                ToolParameterSchema(name = "cookies", type = "string", description = "cookies", required = false)
                            )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Extended File Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "file_exists",
                            description = "Check if a file or directory exists.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "target path", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "move_file",
                            description = "Move or rename a file or directory.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "source path", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "destination path", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "copy_file",
                            description = "Copy a file or directory. Supports cross-environment copying between Android and Linux.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "source path", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "destination path", required = true),
                                ToolParameterSchema(name = "recursive", type = "boolean", description = "boolean", required = false, default = "false"),
                                ToolParameterSchema(name = "source_environment", type = "string", description = "optional, \"android\" or \"linux\"", required = false, default = "\"android\""),
                                ToolParameterSchema(name = "dest_environment", type = "string", description = "optional, \"android\" or \"linux\". For cross-environment copy (e.g., Android → Linux or Linux → Android), specify both source_environment and dest_environment", required = false, default = "\"android\"")
                            )
                        ),
                        ToolPrompt(
                            name = "file_info",
                            description = "Get detailed information about a file or directory including type, size, permissions, owner, group, and last modified time.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "target path", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "zip_files",
                            description = "Compress files or directories.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "path to compress", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "output zip file", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "unzip_files",
                            description = "Extract a zip file.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "zip file path", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "extract path", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "open_file",
                            description = "Open a file using the system's default application.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "file path", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "share_file",
                            description = "Share a file with other applications.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                                ToolParameterSchema(name = "title", type = "string", description = "optional share title", required = false, default = "\"Share File\"")
                            )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Tasker Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "trigger_tasker_event",
                            description = "Trigger a Tasker event.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "task_type",
                                        type = "string",
                                        description = "Tasker event type",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "arg1",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg2",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg3",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg4",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg5",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "args_json",
                                        type = "string",
                                        description = "optional, JSON object string",
                                        required = false
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Workflow Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "get_all_workflows",
                            description = "Get all workflows.",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "create_workflow",
                            description = "Create a workflow.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "name",
                                        type = "string",
                                        description = "workflow name",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "description",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "nodes",
                                        type = "string",
                                        description = "optional, nodes JSON array string",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "connections",
                                        type = "string",
                                        description = "optional, connections JSON array string",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "enabled",
                                        type = "boolean",
                                        description = "optional",
                                        required = false,
                                        default = "true"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_workflow",
                            description = "Get workflow detail.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "workflow_id",
                                        type = "string",
                                        description = "workflow id",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "update_workflow",
                            description = "Update a workflow.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "workflow_id",
                                        type = "string",
                                        description = "workflow id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "name",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "description",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "nodes",
                                        type = "string",
                                        description = "optional, nodes JSON array string",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "connections",
                                        type = "string",
                                        description = "optional, connections JSON array string",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "enabled",
                                        type = "boolean",
                                        description = "optional",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "delete_workflow",
                            description = "Delete a workflow.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "workflow_id",
                                        type = "string",
                                        description = "workflow id",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "trigger_workflow",
                            description = "Trigger a workflow execution.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "workflow_id",
                                        type = "string",
                                        description = "workflow id",
                                        required = true
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Chat Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "start_chat_service",
                            description = "Start the floating chat service.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "initial_mode",
                                        type = "string",
                                        description = "optional, initial floating mode: WINDOW, BALL, VOICE_BALL, FULLSCREEN, RESULT_DISPLAY, SCREEN_OCR",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "auto_enter_voice_chat",
                                        type = "boolean",
                                        description = "optional, if true then enter voice mode automatically when opening FULLSCREEN",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "wake_launched",
                                        type = "boolean",
                                        description = "optional, true if launched by wake word so UI can adjust behavior",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "optional, auto close the floating window after this timeout (milliseconds). <=0 disables auto-exit.",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "keep_if_exists",
                                        type = "boolean",
                                        description = "optional, if true and service already running, do not force floating window mode change",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "stop_chat_service",
                            description = "Stop the floating chat service.",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "create_new_chat",
                            description = "Create a new chat.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "group",
                                        type = "string",
                                        description = "optional group name for the new chat",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "set_as_current_chat",
                                        type = "boolean",
                                        description = "optional, whether to switch to the new chat (default true)",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "character_card_id",
                                        type = "string",
                                        description = "optional, character card id to bind for the new chat",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "list_chats",
                            description = "List chats (supports filtering and sorting).",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "query",
                                        type = "string",
                                        description = "optional, title keyword filter",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "match",
                                        type = "string",
                                        description = "optional, contains | exact | regex (default contains)",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "limit",
                                        type = "integer",
                                        description = "optional, max results (default 50)",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "sort_by",
                                        type = "string",
                                        description = "optional, updatedAt | createdAt | messageCount (default updatedAt)",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "sort_order",
                                        type = "string",
                                        description = "optional, asc | desc (default desc)",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "find_chat",
                            description = "Find a chat by title and return its info.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "query",
                                        type = "string",
                                        description = "title keyword/regex",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "match",
                                        type = "string",
                                        description = "optional, contains | exact | regex (default contains)",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "index",
                                        type = "integer",
                                        description = "optional, pick Nth match (default 0)",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "agent_status",
                            description = "Check a chat's input processing status.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "chat_id",
                                        type = "string",
                                        description = "target chat id",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "switch_chat",
                            description = "Switch to a chat.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "chat_id",
                                        type = "string",
                                        description = "target chat id",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "send_message_to_ai",
                            description = "Send a user message to AI.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "message",
                                        type = "string",
                                        description = "message content",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "chat_id",
                                        type = "string",
                                        description = "optional, target chat id",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "role_card_id",
                                        type = "string",
                                        description = "optional, role card id to use for this send",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "sender_name",
                                        type = "string",
                                        description = "optional, display name of the sender when AI sends as user",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "list_character_cards",
                            description = "List all role cards.",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "get_chat_messages",
                            description = "Get messages from a specific chat (cross-chat history read).",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "chat_id",
                                        type = "string",
                                        description = "target chat id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "order",
                                        type = "string",
                                        description = "optional, asc/desc (default desc)",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "limit",
                                        type = "integer",
                                        description = "optional, number of messages to return (default 20, max 200)",
                                        required = false
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Internal File Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "read_file_full",
                            description = "Read the full content of a file without enforcing size limit.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "path",
                                        type = "string",
                                        description = "file path",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "environment",
                                        type = "string",
                                        description = "optional, \"android\" (default) or \"linux\"",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "text_only",
                                        type = "boolean",
                                        description = "optional",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "read_file_binary",
                            description = "Read binary file and return base64 content.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "path",
                                        type = "string",
                                        description = "file path",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "environment",
                                        type = "string",
                                        description = "optional, \"android\" (default) or \"linux\"",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "write_file",
                            description = "Write content to a file.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "path",
                                        type = "string",
                                        description = "file path",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "content",
                                        type = "string",
                                        description = "file content",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "append",
                                        type = "boolean",
                                        description = "optional",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "environment",
                                        type = "string",
                                        description = "optional, \"android\" (default) or \"linux\"",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "write_file_binary",
                            description = "Write base64 content to a binary file.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "path",
                                        type = "string",
                                        description = "file path",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "base64Content",
                                        type = "string",
                                        description = "base64 encoded content",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "environment",
                                        type = "string",
                                        description = "optional, \"android\" (default) or \"linux\"",
                                        required = false
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Internal UI Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "get_page_info",
                            description = "Get current page/window UI information.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "format",
                                        type = "string",
                                        description = "optional, xml/json",
                                        required = false,
                                        default = "xml"
                                    ),
                                    ToolParameterSchema(
                                        name = "detail",
                                        type = "string",
                                        description = "optional",
                                        required = false,
                                        default = "summary"
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "optional, display id for multi-display",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "tap",
                            description = "Tap at screen coordinates.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "x",
                                        type = "integer",
                                        description = "x coordinate",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "y",
                                        type = "integer",
                                        description = "y coordinate",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "optional, display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "long_press",
                            description = "Long press at screen coordinates.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "x",
                                        type = "integer",
                                        description = "x coordinate",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "y",
                                        type = "integer",
                                        description = "y coordinate",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "optional, display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "swipe",
                            description = "Swipe from start to end coordinates.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "start_x",
                                        type = "integer",
                                        description = "start x",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "start_y",
                                        type = "integer",
                                        description = "start y",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "end_x",
                                        type = "integer",
                                        description = "end x",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "end_y",
                                        type = "integer",
                                        description = "end y",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "duration",
                                        type = "integer",
                                        description = "optional, duration in ms",
                                        required = false,
                                        default = "300"
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "optional, display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "click_element",
                            description = "Click a UI element by resource id / class name / content description / bounds.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "resourceId",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "className",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "contentDesc",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "bounds",
                                        type = "string",
                                        description = "optional, format: [left,top][right,bottom]",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "partialMatch",
                                        type = "boolean",
                                        description = "optional, enable partial match for selectors",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "index",
                                        type = "integer",
                                        description = "optional",
                                        required = false,
                                        default = "0"
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "optional, display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "set_input_text",
                            description = "Set input text in focused field.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "text",
                                        type = "string",
                                        description = "text to input (can be empty to clear)",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "optional, display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "press_key",
                            description = "Press a key via keyevent.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "key_code",
                                        type = "string",
                                        description = "key code, e.g. KEYCODE_HOME",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "optional, display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "capture_screenshot",
                            description = "Capture a screenshot and return a file path.",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "run_ui_subagent",
                            description = "Run a lightweight UI automation subagent.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "intent",
                                        type = "string",
                                        description = "task description",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "max_steps",
                                        type = "integer",
                                        description = "optional",
                                        required = false,
                                        default = "20"
                                    ),
                                    ToolParameterSchema(
                                        name = "agent_id",
                                        type = "string",
                                        description = "optional, reuse agent session id. If omitted or 'default', uses the main screen. If provided and not 'default', the requested virtual screen session must be active/available; otherwise the run fails.",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "target_app",
                                        type = "string",
                                        description = "optional, target app package name",
                                        required = false
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Internal System Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "close_all_virtual_displays",
                            description = "Close all virtual display overlays.",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "modify_system_setting",
                            description = "Modify a system setting.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "setting",
                                        type = "string",
                                        description = "setting key (alias: key)",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "value",
                                        type = "string",
                                        description = "setting value",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "namespace",
                                        type = "string",
                                        description = "optional, system/secure/global",
                                        required = false,
                                        default = "system"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_system_setting",
                            description = "Get a system setting.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "setting",
                                        type = "string",
                                        description = "setting key (alias: key)",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "namespace",
                                        type = "string",
                                        description = "optional, system/secure/global",
                                        required = false,
                                        default = "system"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "install_app",
                            description = "Request installing an APK.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "path",
                                        type = "string",
                                        description = "APK file path (alias: path)",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "uninstall_app",
                            description = "Request uninstalling an app.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "package_name",
                                        type = "string",
                                        description = "app package name",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "list_installed_apps",
                            description = "List installed apps.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "include_system_apps",
                                        type = "boolean",
                                        description = "optional",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "start_app",
                            description = "Start an app.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "package_name",
                                        type = "string",
                                        description = "app package name",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "activity",
                                        type = "string",
                                        description = "optional, activity class name",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "stop_app",
                            description = "Stop an app background process.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "package_name",
                                        type = "string",
                                        description = "app package name",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_notifications",
                            description = "Get device notifications.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "limit",
                                        type = "integer",
                                        description = "optional",
                                        required = false,
                                        default = "10"
                                    ),
                                    ToolParameterSchema(
                                        name = "include_ongoing",
                                        type = "boolean",
                                        description = "optional",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "toast",
                            description = "Show a short toast message on the device.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "message",
                                        type = "string",
                                        description = "toast text",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "send_notification",
                            description = "Send a notification using the AI reply completion notification channel.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "title",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "message",
                                        type = "string",
                                        description = "notification body",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_device_location",
                            description = "Get device location.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "timeout",
                                        type = "integer",
                                        description = "optional, seconds",
                                        required = false,
                                        default = "10"
                                    ),
                                    ToolParameterSchema(
                                        name = "high_accuracy",
                                        type = "boolean",
                                        description = "optional",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "include_address",
                                        type = "boolean",
                                        description = "optional",
                                        required = false,
                                        default = "true"
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "FFmpeg Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "ffmpeg_execute",
                            description = "Execute an FFmpeg command (arguments only; do not include the leading ffmpeg).",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "command",
                                        type = "string",
                                        description = "FFmpeg command arguments only, without the leading ffmpeg",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "ffmpeg_info",
                            description = "Get FFmpeg information.",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "ffmpeg_convert",
                            description = "Convert a video file using FFmpeg.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "input_path",
                                        type = "string",
                                        description = "input file path",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "output_path",
                                        type = "string",
                                        description = "output file path",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "format",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "resolution",
                                        type = "string",
                                        description = "optional, e.g. 1280x720",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "bitrate",
                                        type = "string",
                                        description = "optional, e.g. 1000k",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "audio_codec",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "video_codec",
                                        type = "string",
                                        description = "optional, use h264 for H.264 encoding",
                                        required = false
                                    )
                                )
                        )
                    )
            )
        )

    val internalToolCategoriesCn: List<SystemToolPromptCategory> =
        listOf(
            SystemToolPromptCategory(
                categoryName = "内部工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "execute_shell",
                            description = "执行设备 Shell 命令。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "command",
                                        type = "string",
                                        description = "要执行的命令",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "create_terminal_session",
                            description = "创建或获取终端会话。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_name",
                                        type = "string",
                                        description = "终端会话名称",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "execute_in_terminal_session",
                            description = "在终端会话中执行命令，并一次性返回完整输出。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "终端会话 ID",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "command",
                                        type = "string",
                                        description = "要执行的命令",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "可选，超时时间（毫秒）",
                                        required = false,
                                        default = "1800000"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "input_in_terminal_session",
                            description = "向终端会话写入输入。input 与 control 至少传一个。通常先发送 input，再发送 control=enter 提交内容。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "终端会话 ID",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "input",
                                        type = "string",
                                        description = "要写入终端的文本（可包含换行）",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "control",
                                        type = "string",
                                        description = "控制键或修饰键（如 enter/tab/esc/up/down/left/right/home/end/pageup/pagedown，或 control=ctrl 且 input=c 表示 Ctrl+C）",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "close_terminal_session",
                            description = "关闭终端会话。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "终端会话 ID",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_terminal_session_screen",
                            description = "获取终端会话当前可见 PTY 屏幕内容（仅一屏，不包含历史滚动缓冲）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "终端会话 ID",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "start_web",
                            description = "启动持久化网页会话并打开悬浮浏览窗口。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "url",
                                        type = "string",
                                        description = "可选，初始打开的 URL",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "headers",
                                        type = "string",
                                        description = "可选，请求头 JSON 对象字符串",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "user_agent",
                                        type = "string",
                                        description = "可选，自定义 User-Agent",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "session_name",
                                        type = "string",
                                        description = "可选，会话显示名称",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "stop_web",
                            description = "停止一个网页会话或全部网页会话。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "可选，close_all=true 时可省略",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "close_all",
                                        type = "boolean",
                                        description = "可选，为 true 时停止全部会话",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_navigate",
                            description = "让网页会话跳转到指定 URL。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "网页会话 ID",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "url",
                                        type = "string",
                                        description = "目标 URL",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "headers",
                                        type = "string",
                                        description = "可选，请求头 JSON 对象字符串",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_eval",
                            description = "在网页会话中执行 JavaScript。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "网页会话 ID",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "script",
                                        type = "string",
                                        description = "JavaScript 脚本",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "可选，超时时间（毫秒）",
                                        required = false,
                                        default = "10000"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_click",
                            description = "按快照 ref 或 CSS 选择器点击元素。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "可选，网页会话 ID",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "ref",
                                        type = "string",
                                        description = "可选，来自 web_snapshot 输出的元素引用",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "element",
                                        type = "string",
                                        description = "可选，人类可读元素描述",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "selector",
                                        type = "string",
                                        description = "可选，CSS 选择器（提供 ref 时可省略）",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "index",
                                        type = "integer",
                                        description = "可选，匹配元素中的 0 基索引",
                                        required = false,
                                        default = "0"
                                    ),
                                    ToolParameterSchema(
                                        name = "button",
                                        type = "string",
                                        description = "可选，left/right/middle",
                                        required = false,
                                        default = "left"
                                    ),
                                    ToolParameterSchema(
                                        name = "modifiers",
                                        type = "string",
                                        description = "可选，修饰键 JSON 数组：Alt/Control/ControlOrMeta/Meta/Shift",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "double_click",
                                        type = "boolean",
                                        description = "可选，是否双击",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "可选，动作可交互检查超时（毫秒）",
                                        required = false,
                                        default = "10000"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_fill",
                            description = "按 CSS 选择器填写输入框。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "网页会话 ID",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "selector",
                                        type = "string",
                                        description = "CSS 选择器",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "value",
                                        type = "string",
                                        description = "要写入的值",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_file_upload",
                            description = "向网页会话中的文件选择器上传一个或多个文件。`paths` 为可选，不传时会取消 file chooser。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "可选，网页会话 ID",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "paths",
                                        type = "string",
                                        description = "可选，绝对路径数组的 JSON 字符串，例如 [\"/sdcard/Download/a.txt\"]",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_wait_for",
                            description = "等待页面就绪或等待元素出现。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "网页会话 ID",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "selector",
                                        type = "string",
                                        description = "可选，等待出现的 CSS 选择器",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "可选，超时时间（毫秒）",
                                        required = false,
                                        default = "10000"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "web_snapshot",
                            description = "抓取当前网页快照文本。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "网页会话 ID",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "include_links",
                                        type = "boolean",
                                        description = "可选，是否包含链接列表",
                                        required = false,
                                        default = "true"
                                    ),
                                    ToolParameterSchema(
                                        name = "include_images",
                                        type = "boolean",
                                        description = "可选，是否包含图片列表",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "calculate",
                            description = "计算数学表达式。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "expression",
                                        type = "string",
                                        description = "数学表达式，例如 \"(1+2)*3\"",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "execute_intent",
                            description = "执行 Android Intent（activity/broadcast/service）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "action",
                                        type = "string",
                                        description = "可选，Intent action",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "uri",
                                        type = "string",
                                        description = "可选，data URI",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "package",
                                        type = "string",
                                        description = "可选，包名",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "component",
                                        type = "string",
                                        description = "可选，\"package/class\" 格式",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "type",
                                        type = "string",
                                        description = "可选，activity/broadcast/service",
                                        required = false,
                                        default = "activity"
                                    ),
                                    ToolParameterSchema(
                                        name = "flags",
                                        type = "string",
                                        description = "可选，flag 整数数组 JSON 字符串（或单个整数）",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extras",
                                        type = "string",
                                        description = "可选，extras 的 JSON 对象字符串",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "send_broadcast",
                            description = "发送广播 Intent。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "action",
                                        type = "string",
                                        description = "必填，广播 action",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "uri",
                                        type = "string",
                                        description = "可选，data URI",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "package",
                                        type = "string",
                                        description = "可选，包名",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "component",
                                        type = "string",
                                        description = "可选，\"package/class\" 格式",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extras",
                                        type = "string",
                                        description = "可选，extras 的 JSON 对象字符串",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_key",
                                        type = "string",
                                        description = "可选，单个字符串 extra 的 key",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_value",
                                        type = "string",
                                        description = "可选，单个字符串 extra 的 value",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_key2",
                                        type = "string",
                                        description = "可选，第二个字符串 extra 的 key",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_value2",
                                        type = "string",
                                        description = "可选，第二个字符串 extra 的 value",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "device_info",
                            description = "获取设备信息。",
                            parametersStructured = listOf()
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "拓展记忆工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "create_memory",
                            description = "在记忆库中创建新的记忆节点。当你想保存重要信息供将来参考时使用。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "title", type = "string", description = "必需, 字符串", required = true),
                                ToolParameterSchema(name = "content", type = "string", description = "必需, 字符串", required = true),
                                ToolParameterSchema(name = "content_type", type = "string", description = "可选", required = false, default = "\"text/plain\""),
                                ToolParameterSchema(name = "source", type = "string", description = "可选", required = false, default = "\"ai_created\""),
                                ToolParameterSchema(name = "folder_path", type = "string", description = "可选", required = false, default = "\"\"")
                            )
                        ),
                        ToolPrompt(
                            name = "update_memory",
                            description = "通过标题更新现有的记忆节点。用于修改现有记忆的内容或元数据。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "old_title", type = "string", description = "必需, 字符串，用于识别记忆", required = true),
                                ToolParameterSchema(name = "new_title", type = "string", description = "可选, 字符串, 重命名时的新标题", required = false),
                                ToolParameterSchema(name = "content", type = "string", description = "可选, 字符串", required = false),
                                ToolParameterSchema(name = "content_type", type = "string", description = "可选, 字符串", required = false),
                                ToolParameterSchema(name = "source", type = "string", description = "可选, 字符串", required = false),
                                ToolParameterSchema(name = "credibility", type = "number", description = "可选, 浮点数 0-1", required = false),
                                ToolParameterSchema(name = "importance", type = "number", description = "可选, 浮点数 0-1", required = false),
                                ToolParameterSchema(name = "folder_path", type = "string", description = "可选, 字符串", required = false),
                                ToolParameterSchema(name = "tags", type = "string", description = "可选, 逗号分隔的字符串", required = false)
                            )
                        ),
                        ToolPrompt(
                            name = "delete_memory",
                            description = "通过标题从记忆库中删除记忆节点。谨慎使用，此操作不可逆。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "title", type = "string", description = "必需, 字符串，用于识别记忆", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "link_memories",
                            description = "在记忆库中的两个记忆之间创建语义链接。用于建立相关概念、事实或信息片段之间的关系。这有助于构建知识图谱结构，以便更好地检索和理解记忆。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source_title", type = "string", description = "必需, 字符串, 源记忆的标题", required = true),
                                ToolParameterSchema(name = "target_title", type = "string", description = "必需, 字符串, 目标记忆的标题", required = true),
                                ToolParameterSchema(name = "link_type", type = "string", description = "可选, 字符串, 关系类型，如\"related\"（相关）、\"causes\"（导致）、\"explains\"（解释）、\"part_of\"（部分）、\"contradicts\"（矛盾）等", required = false, default = "\"related\""),
                                ToolParameterSchema(name = "weight", type = "number", description = "可选, 浮点数 0.0-1.0, 链接强度，1.0表示最强", required = false, default = "0.7"),
                                ToolParameterSchema(name = "description", type = "string", description = "可选, 字符串, 关于关系的额外上下文", required = false, default = "\"\"")
                            )
                        ),
                        ToolPrompt(
                            name = "query_memory_links",
                            description = "查询记忆图谱中的链接。支持按 link_id、source_title、target_title、link_type 过滤。适合在更新/删除链接前先精确定位目标。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "link_id", type = "integer", description = "可选, 精确链接ID", required = false),
                                ToolParameterSchema(name = "source_title", type = "string", description = "可选, 源记忆精确标题", required = false),
                                ToolParameterSchema(name = "target_title", type = "string", description = "可选, 目标记忆精确标题", required = false),
                                ToolParameterSchema(name = "link_type", type = "string", description = "可选, 关系类型过滤", required = false),
                                ToolParameterSchema(name = "limit", type = "integer", description = "可选, 整数 1-200, 返回链接数量上限", required = false, default = "20")
                            )
                        ),
                        ToolPrompt(
                            name = "update_user_preferences",
                            description = "直接更新用户偏好信息。当你了解到用户的新信息时使用（例如生日、性别、性格特征、身份、职业或首选AI交互风格）。这允许立即更新而无需等待自动系统。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "birth_date", type = "integer", description = "可选, Unix时间戳，毫秒", required = false),
                                ToolParameterSchema(name = "gender", type = "string", description = "可选, 字符串", required = false),
                                ToolParameterSchema(name = "personality", type = "string", description = "可选, 描述性格特征的字符串", required = false),
                                ToolParameterSchema(name = "identity", type = "string", description = "可选, 描述身份/角色的字符串", required = false),
                                ToolParameterSchema(name = "occupation", type = "string", description = "可选, 字符串", required = false),
                                ToolParameterSchema(name = "ai_style", type = "string", description = "可选, 描述首选AI交互风格的字符串. 必须提供至少一个参数", required = false)
                            )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "拓展 HTTP 工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "http_request",
                            description = "发送HTTP请求。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "url", type = "string", description = "url", required = true),
                                ToolParameterSchema(name = "method", type = "string", description = "GET/POST/PUT/DELETE", required = true),
                                ToolParameterSchema(name = "headers", type = "string", description = "headers", required = false),
                                ToolParameterSchema(name = "body", type = "string", description = "body", required = false),
                                ToolParameterSchema(name = "body_type", type = "string", description = "json/form/text/xml", required = false)
                            )
                        ),
                        ToolPrompt(
                            name = "multipart_request",
                            description = "上传文件。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "url", type = "string", description = "url", required = true),
                                ToolParameterSchema(name = "method", type = "string", description = "POST/PUT", required = true),
                                ToolParameterSchema(name = "headers", type = "string", description = "headers", required = false),
                                ToolParameterSchema(name = "form_data", type = "string", description = "form_data", required = false),
                                ToolParameterSchema(name = "files", type = "string", description = "JSON数组字符串。每个元素是对象: {\"field_name\": 字符串, \"file_path\": 字符串, 可选 \"content_type\": 字符串, 可选 \"file_name\": 字符串}", required = false)
                            )
                        ),
                        ToolPrompt(
                            name = "manage_cookies",
                            description = "管理cookies。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "action", type = "string", description = "get/set/clear", required = true),
                                ToolParameterSchema(name = "domain", type = "string", description = "domain", required = false),
                                ToolParameterSchema(name = "cookies", type = "string", description = "cookies", required = false)
                            )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "拓展文件工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "file_exists",
                            description = "检查文件或目录是否存在。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "目标路径", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "move_file",
                            description = "移动或重命名文件或目录。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "源路径", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "目标路径", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "copy_file",
                            description = "复制文件或目录。支持Android和Linux之间的跨环境复制。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "源路径", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "目标路径", required = true),
                                ToolParameterSchema(name = "recursive", type = "boolean", description = "布尔值", required = false, default = "false"),
                                ToolParameterSchema(name = "source_environment", type = "string", description = "可选，\"android\"或\"linux\"", required = false, default = "\"android\""),
                                ToolParameterSchema(name = "dest_environment", type = "string", description = "可选，\"android\"或\"linux\"。跨环境复制（如Android → Linux或Linux → Android）时，需指定source_environment和dest_environment", required = false, default = "\"android\"")
                            )
                        ),
                        ToolPrompt(
                            name = "file_info",
                            description = "获取文件或目录的详细信息，包括类型、大小、权限、所有者、组和最后修改时间。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "目标路径", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "zip_files",
                            description = "压缩文件或目录。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "要压缩的路径", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "输出zip文件", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "unzip_files",
                            description = "解压zip文件。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "zip文件路径", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "解压路径", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "open_file",
                            description = "使用系统默认应用程序打开文件。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "share_file",
                            description = "与其他应用程序共享文件。",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                                ToolParameterSchema(name = "title", type = "string", description = "可选的共享标题", required = false, default = "\"Share File\"")
                            )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Tasker 工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "trigger_tasker_event",
                            description = "触发 Tasker 事件。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "task_type",
                                        type = "string",
                                        description = "Tasker 事件类型",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "arg1",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg2",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg3",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg4",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg5",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "args_json",
                                        type = "string",
                                        description = "可选，JSON 对象字符串",
                                        required = false
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "工作流工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "get_all_workflows",
                            description = "获取所有工作流列表。",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "create_workflow",
                            description = "创建工作流。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "name",
                                        type = "string",
                                        description = "工作流名称",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "description",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "nodes",
                                        type = "string",
                                        description = "可选，节点 JSON 数组字符串",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "connections",
                                        type = "string",
                                        description = "可选，连线 JSON 数组字符串",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "enabled",
                                        type = "boolean",
                                        description = "可选",
                                        required = false,
                                        default = "true"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_workflow",
                            description = "获取工作流详情。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "workflow_id",
                                        type = "string",
                                        description = "工作流 ID",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "update_workflow",
                            description = "更新工作流。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "workflow_id",
                                        type = "string",
                                        description = "工作流 ID",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "name",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "description",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "nodes",
                                        type = "string",
                                        description = "可选，节点 JSON 数组字符串",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "connections",
                                        type = "string",
                                        description = "可选，连线 JSON 数组字符串",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "enabled",
                                        type = "boolean",
                                        description = "可选",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "delete_workflow",
                            description = "删除工作流。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "workflow_id",
                                        type = "string",
                                        description = "工作流 ID",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "trigger_workflow",
                            description = "触发工作流执行。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "workflow_id",
                                        type = "string",
                                        description = "工作流 ID",
                                        required = true
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "对话工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "start_chat_service",
                            description = "启动对话服务（悬浮窗）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "initial_mode",
                                        type = "string",
                                        description = "可选，初始悬浮模式：WINDOW, BALL, VOICE_BALL, FULLSCREEN, RESULT_DISPLAY, SCREEN_OCR",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "auto_enter_voice_chat",
                                        type = "boolean",
                                        description = "可选，为 true 时在打开 FULLSCREEN 时自动进入语音模式",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "wake_launched",
                                        type = "boolean",
                                        description = "可选，若由唤醒词启动则为 true，以便 UI 调整行为",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "可选，超时后自动关闭悬浮窗（毫秒），<=0 禁用自动关闭",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "keep_if_exists",
                                        type = "boolean",
                                        description = "可选，若服务已在运行则不强制切换悬浮窗模式",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "stop_chat_service",
                            description = "停止对话服务（悬浮窗）。",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "create_new_chat",
                            description = "创建新的对话。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "group",
                                        type = "string",
                                        description = "新对话分组名（可选）",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "set_as_current_chat",
                                        type = "boolean",
                                        description = "可选，是否切换到新对话（默认 true）",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "character_card_id",
                                        type = "string",
                                        description = "可选，创建对话时绑定的角色卡 ID",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "list_chats",
                            description = "列出所有对话（支持筛选与排序）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "query",
                                        type = "string",
                                        description = "可选，标题关键字筛选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "match",
                                        type = "string",
                                        description = "可选，contains | exact | regex（默认 contains）",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "limit",
                                        type = "integer",
                                        description = "可选，最多返回条数（默认 50）",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "sort_by",
                                        type = "string",
                                        description = "可选，updatedAt | createdAt | messageCount（默认 updatedAt）",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "sort_order",
                                        type = "string",
                                        description = "可选，asc | desc（默认 desc）",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "find_chat",
                            description = "按标题查找对话并返回其信息。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "query",
                                        type = "string",
                                        description = "标题关键字/正则",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "match",
                                        type = "string",
                                        description = "可选，contains | exact | regex（默认 contains）",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "index",
                                        type = "integer",
                                        description = "可选，选择第 N 个匹配（默认 0）",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "agent_status",
                            description = "查询对话的输入处理状态。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "chat_id",
                                        type = "string",
                                        description = "目标对话 ID",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "switch_chat",
                            description = "切换到指定对话。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "chat_id",
                                        type = "string",
                                        description = "目标对话 ID",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "send_message_to_ai",
                            description = "向 AI 发送消息。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "message",
                                        type = "string",
                                        description = "消息内容",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "chat_id",
                                        type = "string",
                                        description = "可选，目标对话 ID",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "role_card_id",
                                        type = "string",
                                        description = "可选，本次发送使用的角色卡 ID",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "sender_name",
                                        type = "string",
                                        description = "可选，当以用户身份发送时的显示名称",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "list_character_cards",
                            description = "列出所有角色卡。",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "get_chat_messages",
                            description = "读取指定对话的消息内容（跨话题读取）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "chat_id",
                                        type = "string",
                                        description = "目标对话 ID",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "order",
                                        type = "string",
                                        description = "可选，asc/desc（默认 desc）",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "limit",
                                        type = "integer",
                                        description = "可选，返回消息条数（默认20，最大200）",
                                        required = false
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "内部文件工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "read_file_full",
                            description = "读取完整文件内容（不限制大小）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "path",
                                        type = "string",
                                        description = "文件路径",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "environment",
                                        type = "string",
                                        description = "可选，\"android\"（默认）或 \"linux\"",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "text_only",
                                        type = "boolean",
                                        description = "可选",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "read_file_binary",
                            description = "读取二进制文件并返回 Base64 内容。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "path",
                                        type = "string",
                                        description = "文件路径",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "environment",
                                        type = "string",
                                        description = "可选，\"android\"（默认）或 \"linux\"",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "write_file",
                            description = "写入文件内容。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "path",
                                        type = "string",
                                        description = "文件路径",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "content",
                                        type = "string",
                                        description = "文件内容",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "append",
                                        type = "boolean",
                                        description = "可选",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "environment",
                                        type = "string",
                                        description = "可选，\"android\"（默认）或 \"linux\"",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "write_file_binary",
                            description = "将 Base64 内容写入二进制文件。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "path",
                                        type = "string",
                                        description = "文件路径",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "base64Content",
                                        type = "string",
                                        description = "Base64 编码内容",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "environment",
                                        type = "string",
                                        description = "可选，\"android\"（默认）或 \"linux\"",
                                        required = false
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "内部 UI 工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "get_page_info",
                            description = "获取当前页面/窗口 UI 信息。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "format",
                                        type = "string",
                                        description = "可选，xml/json",
                                        required = false,
                                        default = "xml"
                                    ),
                                    ToolParameterSchema(
                                        name = "detail",
                                        type = "string",
                                        description = "可选",
                                        required = false,
                                        default = "summary"
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "可选，多屏 display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "tap",
                            description = "点击屏幕坐标。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "x",
                                        type = "integer",
                                        description = "x 坐标",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "y",
                                        type = "integer",
                                        description = "y 坐标",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "可选，多屏 display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "long_press",
                            description = "长按屏幕坐标。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "x",
                                        type = "integer",
                                        description = "x 坐标",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "y",
                                        type = "integer",
                                        description = "y 坐标",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "可选，多屏 display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "swipe",
                            description = "执行滑动手势。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "start_x",
                                        type = "integer",
                                        description = "起始 x",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "start_y",
                                        type = "integer",
                                        description = "起始 y",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "end_x",
                                        type = "integer",
                                        description = "结束 x",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "end_y",
                                        type = "integer",
                                        description = "结束 y",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "duration",
                                        type = "integer",
                                        description = "可选，持续时间（毫秒）",
                                        required = false,
                                        default = "300"
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "可选，多屏 display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "click_element",
                            description = "点击 UI 元素（resourceId / className / contentDesc / bounds）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "resourceId",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "className",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "contentDesc",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "bounds",
                                        type = "string",
                                        description = "可选，格式：[left,top][right,bottom]",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "partialMatch",
                                        type = "boolean",
                                        description = "可选，是否启用部分匹配",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "index",
                                        type = "integer",
                                        description = "可选",
                                        required = false,
                                        default = "0"
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "可选，多屏 display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "set_input_text",
                            description = "设置输入框文本（可传空字符串以清空）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "text",
                                        type = "string",
                                        description = "要输入的文本",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "可选，多屏 display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "press_key",
                            description = "按下按键（keyevent）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "key_code",
                                        type = "string",
                                        description = "按键码，例如 KEYCODE_HOME",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "display",
                                        type = "string",
                                        description = "可选，多屏 display id",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "capture_screenshot",
                            description = "截取屏幕截图并返回文件路径。",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "run_ui_subagent",
                            description = "运行轻量 UI 自动化子代理。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "intent",
                                        type = "string",
                                        description = "任务描述",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "max_steps",
                                        type = "integer",
                                        description = "可选",
                                        required = false,
                                        default = "20"
                                    ),
                                    ToolParameterSchema(
                                        name = "agent_id",
                                        type = "string",
                                        description = "可选，可复用的 agent 会话 ID。不传或传 'default' 时使用主屏幕；传入且不为 'default' 时表示请求使用对应的虚拟屏幕会话，虚拟屏幕必须处于可用状态，否则本次运行将失败。",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "target_app",
                                        type = "string",
                                        description = "可选，目标应用包名",
                                        required = false
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "内部系统工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "close_all_virtual_displays",
                            description = "关闭所有虚拟屏幕。",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "modify_system_setting",
                            description = "修改系统设置。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "setting",
                                        type = "string",
                                        description = "设置项 key（别名：key）",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "value",
                                        type = "string",
                                        description = "设置值",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "namespace",
                                        type = "string",
                                        description = "可选，system/secure/global",
                                        required = false,
                                        default = "system"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_system_setting",
                            description = "获取系统设置。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "setting",
                                        type = "string",
                                        description = "设置项 key（别名：key）",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "namespace",
                                        type = "string",
                                        description = "可选，system/secure/global",
                                        required = false,
                                        default = "system"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "install_app",
                            description = "请求安装 APK（需要用户确认）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "path",
                                        type = "string",
                                        description = "APK 文件路径（别名：path）",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "uninstall_app",
                            description = "请求卸载应用（需要用户确认）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "package_name",
                                        type = "string",
                                        description = "应用包名",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "list_installed_apps",
                            description = "列出已安装应用。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "include_system_apps",
                                        type = "boolean",
                                        description = "可选",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "start_app",
                            description = "启动应用。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "package_name",
                                        type = "string",
                                        description = "应用包名",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "activity",
                                        type = "string",
                                        description = "可选，Activity 类名",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "stop_app",
                            description = "停止应用后台进程。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "package_name",
                                        type = "string",
                                        description = "应用包名",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_notifications",
                            description = "获取设备通知。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "limit",
                                        type = "integer",
                                        description = "可选",
                                        required = false,
                                        default = "10"
                                    ),
                                    ToolParameterSchema(
                                        name = "include_ongoing",
                                        type = "boolean",
                                        description = "可选",
                                        required = false,
                                        default = "false"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "toast",
                            description = "在设备上显示 Toast 提示。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "message",
                                        type = "string",
                                        description = "Toast 文本",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "send_notification",
                            description = "使用 AI 回复完成的通知通道发送通知。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "title",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "message",
                                        type = "string",
                                        description = "通知内容",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_device_location",
                            description = "获取设备位置信息。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "timeout",
                                        type = "integer",
                                        description = "可选，超时（秒）",
                                        required = false,
                                        default = "10"
                                    ),
                                    ToolParameterSchema(
                                        name = "high_accuracy",
                                        type = "boolean",
                                        description = "可选",
                                        required = false,
                                        default = "false"
                                    ),
                                    ToolParameterSchema(
                                        name = "include_address",
                                        type = "boolean",
                                        description = "可选",
                                        required = false,
                                        default = "true"
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "FFmpeg 工具",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "ffmpeg_execute",
                            description = "执行 FFmpeg 命令（仅填写参数，不要包含前缀 ffmpeg）。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "command",
                                        type = "string",
                                        description = "仅填写 FFmpeg 命令参数，不要包含前缀 ffmpeg",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "ffmpeg_info",
                            description = "获取 FFmpeg 信息。",
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "ffmpeg_convert",
                            description = "使用 FFmpeg 转换视频文件。",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "input_path",
                                        type = "string",
                                        description = "输入文件路径",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "output_path",
                                        type = "string",
                                        description = "输出文件路径",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "format",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "resolution",
                                        type = "string",
                                        description = "可选，例如 1280x720",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "bitrate",
                                        type = "string",
                                        description = "可选，例如 1000k",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "audio_codec",
                                        type = "string",
                                        description = "可选",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "video_codec",
                                        type = "string",
                                        description = "可选，H.264 编码请使用 h264",
                                        required = false
                                    )
                                )
                        )
                    )
            )
        )
}
