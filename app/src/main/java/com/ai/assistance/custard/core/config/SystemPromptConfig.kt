package com.ai.assistance.custard.core.config

import com.ai.assistance.custard.core.tools.packTool.PackageManager
import com.ai.assistance.custard.data.preferences.ApiPreferences
import com.ai.assistance.custard.data.skill.SkillRepository
import com.ai.assistance.custard.util.LocaleUtils
import java.io.File

object SystemPromptConfig {

    private const val BEHAVIOR_GUIDELINES_EN = """
BEHAVIOR GUIDELINES:
- Parallel Tool Calling: For any information-gathering task (e.g., reading files, searching, getting comments, page operations), you **MUST** call all necessary tools in a single turn. **Do not call them sequentially.** This is a strict efficiency requirement. The system is designed to handle the sequence and integrate the results. For data modification (e.g., writing files), you must still only call one tool at a time.
- Keep responses concise and clear. Avoid lengthy explanations unless requested.
- Don't repeat previous conversation steps. Maintain context naturally.
- Acknowledge your limitations honestly. If you don't know something, say so.
- End every response in exactly ONE of the following ways:
  1. Tool Call: To perform an action. A tool call must be the absolute last thing in your response. Nothing can follow it.
  2. Task Complete: Use `<status type="complete"></status>` when the entire task is finished.
  3. Wait for User: Use `<status type="wait_for_user_need"></status>` if you need user input or are unsure how to proceed.
- Critical Rule: The three ending methods are mutually exclusive. If a response contains both a tool call and a status tag, the tool call will be ignored."""
    private const val BEHAVIOR_GUIDELINES_CN = """
行为准则：
- 并行工具调用: 对于任何信息搜集任务（例如，读取文件、搜索、获取评论、页面操作），你**必须**在单次回合中调用所有需要的工具。**严禁分开串行调用**。这是一条严格的效率指令。系统已设计好先后顺序并整合结果。写入工具依旧要保证每次只调用一次。
- 回答应简洁明了，除非用户要求，否则避免冗长的解释。
- 不要重复之前的对话步骤，自然地保持上下文。
- 坦诚承认自己的局限性，如果不知道某事，就直接说明。
- 每次响应都必须以以下三种方式之一结束：
  1. 工具调用：用于执行操作。工具调用必须是响应的最后一部分，后面不能有任何内容。
  2. 任务完成：当整个任务完成时，使用 `<status type="complete"></status>`。
  3. 等待用户：当你需要用户输入或不确定如何继续时，使用 `<status type="wait_for_user_need"></status>`。
- 关键规则：以上三种结束方式互斥。如果响应中同时包含工具调用和状态标签，工具调用将被忽略。"""

    private const val TOOL_USAGE_GUIDELINES_EN = """
When calling a tool, the user will see your response, and then will automatically send the tool results back to you in a follow-up message.

Before calling a tool, briefly describe what you are about to do.

To use a tool, use this format in your response:

<tool name="tool_name">
<param name="parameter_name">parameter_value</param>
</tool>

When outputting XML (e.g., <tool>, <status>), insert a newline before it and ensure the opening tag starts at the beginning of a line.

Based on user needs, proactively select the most appropriate tool or combination of tools. For complex tasks, you can break down the problem and use different tools step by step to solve it. After using each tool, clearly explain the execution results and suggest the next steps."""
    private const val TOOL_USAGE_GUIDELINES_CN = """
调用工具时，用户会看到你的响应，然后会自动将工具结果发送回给你。

调用工具前，请简要说明你要做什么。

使用工具时，请使用以下格式：

<tool name="tool_name">
<param name="parameter_name">parameter_value</param>
</tool>

输出XML（如 <tool>、<status>）时，必须在XML前换行，并确保起始标签位于行首。

根据用户需求，主动选择最合适的工具或工具组合。对于复杂任务，你可以分解问题并使用不同的工具逐步解决。使用每个工具后，清楚地解释执行结果并建议下一步。"""

    private const val PACKAGE_SYSTEM_GUIDELINES_EN = """
PACKAGE SYSTEM
- Some additional functionality is available through packages
- To use a package, simply activate it with:
  <tool name="use_package">
  <param name="package_name">package_name_here</param>
  </tool>
- This will show you all the tools in the package and how to use them
- Only after activating a package, you can use its tools directly"""
    private const val PACKAGE_SYSTEM_GUIDELINES_CN = """
包系统：
- 一些额外功能通过包提供
- 要使用包，只需激活它：
  <tool name="use_package">
  <param name="package_name">package_name_here</param>
  </tool>
- 这将显示包中的所有工具及其使用方法
- 只有在激活包后，才能直接使用其工具"""

    // Tool Call API 模式下的工具使用简要说明（保留重要的"调用前描述"指示）
    private const val TOOL_USAGE_BRIEF_EN = """
Before calling a tool, briefly describe what you are about to do."""
    private const val TOOL_USAGE_BRIEF_CN = """
调用工具前，请简要说明你要做什么。"""

    // Tool Call API 模式下的包系统说明（不使用XML格式）
    private const val PACKAGE_SYSTEM_GUIDELINES_TOOL_CALL_EN = """
PACKAGE SYSTEM
- Some additional functionality is available through packages
- To use a package, call the use_package function with the package_name parameter
- This will show you all the tools in the package and how to use them
- If use_package for a package has appeared earlier in this chat, treat that package as activated
- After a package is activated, call its tools directly using function names like packageName:toolName
- Package tools may not appear in the current system/tool list, but they are still callable after activation"""
    private const val PACKAGE_SYSTEM_GUIDELINES_TOOL_CALL_STRICT_EN = """
PACKAGE SYSTEM
- Some additional functionality is available through packages
- To use a package, call the use_package function with the package_name parameter
- If use_package for a package has appeared earlier in this chat, treat that package as activated
- For package tools, call package_proxy:
  - Set tool_name to the actual package tool name (e.g. packageName:toolName)
  - Put target tool arguments in params as a JSON object"""

    private const val PACKAGE_SYSTEM_GUIDELINES_TOOL_CALL_STRICT_CN = """
包系统：
- 一些额外功能通过包提供
- 要使用包，调用 use_package 函数并传入 package_name 参数
- 只要本次聊天中该包曾出现过 use_package，就视为该包已激活
- 调用包工具请使用 package_proxy：
  - tool_name 填写真实工具名（例如 packageName:toolName）
  - 将目标工具参数放入 params（JSON对象）"""

    private const val PACKAGE_SYSTEM_GUIDELINES_TOOL_CALL_CN = """
包系统：
- 一些额外功能通过包提供
- 要使用包，调用 use_package 函数并传入 package_name 参数
- 这将显示包中的所有工具及其使用方法
- 只要本次聊天中该包曾出现过 use_package，就视为该包已激活
- 包激活后，请直接使用 packageName:toolName 形式的函数名调用包内工具
- 包内工具可能不会出现在当前系统/工具列表中，但激活后依然可以直接调用"""

    private const val PACKAGE_SYSTEM_GUIDELINES_TOOL_CALL_PROXY_EN = """
PACKAGE SYSTEM
- Some additional functionality is available through packages
- To use a package, call the use_package function with the package_name parameter
- If use_package for a package has appeared earlier in this chat, treat that package as activated
- For package tools, call package_proxy:
  - Set tool_name to the actual package tool name (e.g. packageName:toolName)
  - Put target tool arguments in params as a JSON object"""

    private const val PACKAGE_SYSTEM_GUIDELINES_TOOL_CALL_PROXY_CN = """
包系统：
- 一些额外功能通过包提供
- 要使用包，调用 use_package 函数并传入 package_name 参数
- 只要本次聊天中该包曾出现过 use_package，就视为该包已激活
- 调用包工具请使用 package_proxy：
  - tool_name 填写真实工具名（例如 packageName:toolName）
  - 将目标工具参数放入 params（JSON对象）"""

    private fun getAvailableToolsEn(
        hasImageRecognition: Boolean,
        chatModelHasDirectImage: Boolean,
        hasAudioRecognition: Boolean,
        hasVideoRecognition: Boolean,
        chatModelHasDirectAudio: Boolean,
        chatModelHasDirectVideo: Boolean,
        safBookmarkNames: List<String>,
        toolVisibility: Map<String, Boolean>
    ): String {
        return SystemToolPrompts.generateToolsPromptEn(
            hasBackendImageRecognition = hasImageRecognition,
            includeMemoryTools = false,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasAudioRecognition,
            hasBackendVideoRecognition = hasVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames,
            toolVisibility = toolVisibility
        )
    }

    private fun getMemoryToolsEn(toolVisibility: Map<String, Boolean>): String {
        return SystemToolPrompts.generateMemoryToolsPromptEn(toolVisibility)
    }

    private fun getAvailableToolsCn(
        hasImageRecognition: Boolean,
        chatModelHasDirectImage: Boolean,
        hasAudioRecognition: Boolean,
        hasVideoRecognition: Boolean,
        chatModelHasDirectAudio: Boolean,
        chatModelHasDirectVideo: Boolean,
        safBookmarkNames: List<String>,
        toolVisibility: Map<String, Boolean>
    ): String {
        return SystemToolPrompts.generateToolsPromptCn(
            hasBackendImageRecognition = hasImageRecognition,
            includeMemoryTools = false,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasAudioRecognition,
            hasBackendVideoRecognition = hasVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames,
            toolVisibility = toolVisibility
        )
    }

    private fun getMemoryToolsCn(toolVisibility: Map<String, Boolean>): String {
        return SystemToolPrompts.generateMemoryToolsPromptCn(toolVisibility)
    }


    /** Base system prompt template used by the enhanced AI service */
    val SYSTEM_PROMPT_TEMPLATE =
"""
BEGIN_SELF_INTRODUCTION_SECTION

THINKING_GUIDANCE_SECTION

$BEHAVIOR_GUIDELINES_EN

WEB_WORKSPACE_GUIDELINES_SECTION

FORMULA FORMATTING: For mathematical formulas, use $ $ for inline LaTeX and $$ $$ for block/display LaTeX equations.

TOOL_USAGE_GUIDELINES_SECTION

PACKAGE_SYSTEM_GUIDELINES_SECTION

ACTIVE_PACKAGES_SECTION

AVAILABLE_TOOLS_SECTION
""".trimIndent()

    /** Guidance for the AI on how to "think" using tags. */
    val THINKING_GUIDANCE_PROMPT =
"""
THINKING PROCESS GUIDELINES:
- Before providing your final response, you MUST use a <think> block to outline your thought process. This is for your internal monologue.
- In your thoughts, deconstruct the user's request, consider alternatives, anticipate outcomes, and reflect on the best strategy. Formulate a precise action plan. Your plan should be efficient and use multiple tools in parallel for information gathering whenever possible.
- The user will see your thoughts but cannot reply to them directly. This block is NOT saved in the chat history, so your final answer must be self-contained.
- The <think> block must be immediately followed by your final answer or tool call without any newlines.
- **CRITICAL REMINDER:** Even if previous messages in the chat history do not show a `<think>` block, you MUST include one in your current response. This is a mandatory instruction for this conversation mode.
- Example:
<think>The user wants to know about the configuration files for project A and project B. I need to read the config files for both projects. To be efficient, I will call the `read_file` tool twice in one turn to read `projectA/config.json` and `projectB/config.xml` respectively.</think><tool name="read_file"><param name="path">/sdcard/projectA/config.json</param></tool><tool name="read_file"><param name="path">/sdcard/projectB/config.xml</param></tool>
""".trimIndent()


    /** 中文版本系统提示模板 */
    val SYSTEM_PROMPT_TEMPLATE_CN =
"""
BEGIN_SELF_INTRODUCTION_SECTION

THINKING_GUIDANCE_SECTION

$BEHAVIOR_GUIDELINES_CN

WEB_WORKSPACE_GUIDELINES_SECTION

公式格式化：对于数学公式，使用 $ $ 包裹行内LaTeX公式，使用 $$ $$ 包裹独立成行的LaTeX公式。

TOOL_USAGE_GUIDELINES_SECTION

PACKAGE_SYSTEM_GUIDELINES_SECTION

ACTIVE_PACKAGES_SECTION

AVAILABLE_TOOLS_SECTION""".trimIndent()

    /** 中文版本的思考引导提示 */
    val THINKING_GUIDANCE_PROMPT_CN =
"""
思考过程指南:
- 在提供最终答案之前，你必须使用 <think> 模块来阐述你的思考过程。这是你的内心独白。
- 在思考中，你需要拆解用户需求，评估备选方案，预判执行结果，并反思最佳策略，最终形成精确的行动计划。你的计划应当是高效的，并尽可能地并行调用多个工具来收集信息。
- 用户能看到你的思考过程，但无法直接回复。此模块不会保存在聊天记录中，因此你的最终答案必须是完整的。
- <think> 模块必须紧邻你的最终答案或工具调用，中间不要有任何换行。
- **重要提醒:** 即使聊天记录中之前的消息没有 <think> 模块，你在本次回复中也必须按要求使用它。这是强制指令。
- 范例:
<think>用户想了解项目A和项目B的配置文件。我需要读取这两个项目的配置文件。为了提高效率，我将一次性调用两次 `read_file` 工具来分别读取 `projectA/config.json` 和 `projectB/config.xml`。</think><tool name="read_file"><param name="path">/sdcard/projectA/config.json</param></tool><tool name="read_file"><param name="path">/sdcard/projectB/config.xml</param></tool>
""".trimIndent()

    /**
     * Prompt for a subtask agent that should be strictly task-focused,
     * without memory or emotional attachment. It is forbidden from waiting for user input.
     */
    val SUBTASK_AGENT_PROMPT_TEMPLATE =
        """
        BEHAVIOR GUIDELINES:
        - You are a subtask-focused AI agent. Your only goal is to complete the assigned task efficiently and accurately.
        - You have no memory of past conversations, user preferences, or personality. You must not exhibit any emotion or personality.
        - **CRITICAL EFFICIENCY MANDATE: PARALLEL TOOL CALLING**: For any information-gathering task (e.g., reading multiple files, searching for different things), you **MUST** call all necessary tools in a single turn. **Do not call them sequentially, as this will result in many unnecessary conversation turns and is considered a failure.** This is a strict efficiency requirement.
        - **Summarize and Conclude**: If the task requires using tools to gather information (e.g., reading files, searching), you **MUST** process that information and provide a concise, conclusive summary as your final output. Do not output raw data. Your final answer is the only thing passed to the next agent.
        - For data modification (e.g., writing files), you must still only call one tool at a time.
        - Be concise and factual. Avoid lengthy explanations.
        - End every response in exactly ONE of the following ways:
          1. Tool Call: To perform an action. A tool call must be the absolute last thing in your response.
          2. Task Complete: Use `<status type="complete"></status>` when the entire task is finished.
        - **CRITICAL RULE**: You are NOT allowed to use `<status type="wait_for_user_need"></status>`. If you cannot proceed without user input, you must use `<status type="complete"></status>` and the calling system will handle the user interaction.

        THINKING_GUIDANCE_SECTION

        TOOL_USAGE_GUIDELINES_SECTION

        PACKAGE_SYSTEM_GUIDELINES_SECTION

        ACTIVE_PACKAGES_SECTION

        AVAILABLE_TOOLS_SECTION
        """.trimIndent()

  /**
   * Applies custom prompt replacements from ApiPreferences to the system prompt
   *
   * @param systemPrompt The original system prompt
   * @param customIntroPrompt The custom introduction prompt (about Custard)
   * @return The system prompt with custom prompts applied
   */
  fun applyCustomPrompts(
          systemPrompt: String,
          customIntroPrompt: String
  ): String {
    // Replace the default prompts with custom ones if provided and non-empty
    var result = systemPrompt

    if (customIntroPrompt.isNotEmpty()) {
      result = result.replace("BEGIN_SELF_INTRODUCTION_SECTION", customIntroPrompt)
    }

    return result
  }

  /**
   * Generates the system prompt with dynamic package information
   *
   * @param packageManager The PackageManager instance to get package information from
   * @param workspacePath The current workspace path, if available.
   * @param useEnglish Whether to use English or Chinese version
   * @param thinkingGuidance Whether thinking guidance is enabled
   * @param customSystemPromptTemplate Custom system prompt template (empty means use built-in)
   * @param enableTools Whether tools are enabled
   * @param enableMemoryQuery Whether the AI is allowed to query memories.
   * @param hasImageRecognition Whether a backend image recognition service is configured
   * @param chatModelHasDirectImage Whether the chat model has direct image capability
   * @return The complete system prompt with package information
   */
  fun getSystemPrompt(
          packageManager: PackageManager,
          workspacePath: String? = null,
          workspaceEnv: String? = null,
          safBookmarkNames: List<String> = emptyList(),
          useEnglish: Boolean = false,
          thinkingGuidance: Boolean = false,
          customSystemPromptTemplate: String = "",
          enableTools: Boolean = true,
          enableMemoryQuery: Boolean = true,
          hasImageRecognition: Boolean = false,
          chatModelHasDirectImage: Boolean = false,
          hasAudioRecognition: Boolean = false,
          hasVideoRecognition: Boolean = false,
          chatModelHasDirectAudio: Boolean = false,
          chatModelHasDirectVideo: Boolean = false,
          useToolCallApi: Boolean = false,
          strictToolCall: Boolean = false,
          disableLatexDescription: Boolean = false,
          toolVisibility: Map<String, Boolean> = emptyMap()
  ): String {
    val importedPackages = packageManager.getImportedPackages()
    val mcpServers = packageManager.getAvailableServerPackages()
    val skillPackages = try {
        SkillRepository.getInstance(
            com.ai.assistance.custard.core.application.CustardApplication.instance.applicationContext
        ).getAiVisibleSkillPackages()
    } catch (_: Exception) {
        emptyMap()
    }

    // Build the available packages section
    val packagesSection = StringBuilder()

    // Filter out imported packages that no longer exist in availablePackages
    val validImportedPackages = importedPackages.filter { packageName ->
        packageManager.getPackageTools(packageName) != null &&
            !packageManager.isToolPkgContainer(packageName)
    }

    // Check if any packages (JS, MCP, or Skills) are available
    val hasPackages = validImportedPackages.isNotEmpty() || mcpServers.isNotEmpty() || skillPackages.isNotEmpty()

    if (hasPackages) {
      packagesSection.appendLine("Available packages:")

      // List imported JS packages (only those that still exist)
      for (packageName in validImportedPackages) {
        val packageTools = packageManager.getPackageTools(packageName)
        if (packageTools != null) {
          val preferredLanguage = if (useEnglish) "en" else "zh"
          val resolvedDescription = try {
              packageTools.description.resolve(preferredLanguage)
          } catch (_: Exception) {
              packageTools.description.toString()
          }
          packagesSection.appendLine("- $packageName : $resolvedDescription")
        }
      }

      // List available MCP servers as regular packages
      for ((serverName, serverConfig) in mcpServers) {
        packagesSection.appendLine("- $serverName : ${serverConfig.description}")
      }

      // List available Skills as regular packages
      for ((skillName, skill) in skillPackages) {
        if (skill.description.isNotBlank()) {
          packagesSection.appendLine("- $skillName : ${skill.description}")
        } else {
          packagesSection.appendLine("- $skillName")
        }
      }
    } else {
      packagesSection.appendLine("No packages are currently available.")
    }

    // Information about using packages
    packagesSection.appendLine()
    packagesSection.appendLine("To use a package:")
    packagesSection.appendLine(
            "<tool name=\"use_package\"><param name=\"package_name\">package_name_here</param></tool>"
    )

    // Select appropriate template based on custom template or language preference
    val templateToUse = if (customSystemPromptTemplate.isNotEmpty()) {
        customSystemPromptTemplate
    } else {
        if (useEnglish) SYSTEM_PROMPT_TEMPLATE else SYSTEM_PROMPT_TEMPLATE_CN
    }
    val thinkingGuidancePromptToUse = if (useEnglish) THINKING_GUIDANCE_PROMPT else THINKING_GUIDANCE_PROMPT_CN

    // Generate workspace guidelines
    val workspaceGuidelines = getWorkspaceGuidelines(workspacePath, workspaceEnv, useEnglish)

    // Build prompt with appropriate sections
    var prompt = templateToUse
        .replace("ACTIVE_PACKAGES_SECTION", if (enableTools) packagesSection.toString() else "")
        .replace("WEB_WORKSPACE_GUIDELINES_SECTION", workspaceGuidelines)
            
    // Add thinking guidance section if enabled
    prompt =
            if (thinkingGuidance) {
                prompt.replace("THINKING_GUIDANCE_SECTION", thinkingGuidancePromptToUse)
            } else {
                prompt.replace("THINKING_GUIDANCE_SECTION", "")
            }

    // Determine the available tools string based on memory query setting and image recognition
    // 当使用Tool Call API时，不在系统提示词中包含工具描述（工具已通过API的tools字段发送）
    val availableToolsEn = if (useToolCallApi) "" else (
        if (enableMemoryQuery) {
            getMemoryToolsEn(toolVisibility) +
                getAvailableToolsEn(
                    hasImageRecognition = hasImageRecognition,
                    chatModelHasDirectImage = chatModelHasDirectImage,
                    hasAudioRecognition = hasAudioRecognition,
                    hasVideoRecognition = hasVideoRecognition,
                    chatModelHasDirectAudio = chatModelHasDirectAudio,
                    chatModelHasDirectVideo = chatModelHasDirectVideo,
                    safBookmarkNames = safBookmarkNames,
                    toolVisibility = toolVisibility
                )
        } else {
            getAvailableToolsEn(
                hasImageRecognition = hasImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasAudioRecognition = hasAudioRecognition,
                hasVideoRecognition = hasVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames,
                toolVisibility = toolVisibility
            )
        }
    )
    val availableToolsCn = if (useToolCallApi) "" else (
        if (enableMemoryQuery) {
            getMemoryToolsCn(toolVisibility) +
                getAvailableToolsCn(
                    hasImageRecognition = hasImageRecognition,
                    chatModelHasDirectImage = chatModelHasDirectImage,
                    hasAudioRecognition = hasAudioRecognition,
                    hasVideoRecognition = hasVideoRecognition,
                    chatModelHasDirectAudio = chatModelHasDirectAudio,
                    chatModelHasDirectVideo = chatModelHasDirectVideo,
                    safBookmarkNames = safBookmarkNames,
                    toolVisibility = toolVisibility
                )
        } else {
            getAvailableToolsCn(
                hasImageRecognition = hasImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasAudioRecognition = hasAudioRecognition,
                hasVideoRecognition = hasVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames,
                toolVisibility = toolVisibility
            )
        }
    )

    // Handle tools disable/enable
    if (enableTools) {
        // 当使用Tool Call API时，使用简化的工具使用指南（保留"调用前描述"的重要指示），移除XML格式说明和工具列表
        if (useToolCallApi) {
            val packageGuidelines =
                if (useEnglish) {
                    if (strictToolCall) {
                        PACKAGE_SYSTEM_GUIDELINES_TOOL_CALL_PROXY_EN
                    } else {
                        PACKAGE_SYSTEM_GUIDELINES_TOOL_CALL_EN
                    }
                } else {
                    if (strictToolCall) {
                        PACKAGE_SYSTEM_GUIDELINES_TOOL_CALL_PROXY_CN
                    } else {
                        PACKAGE_SYSTEM_GUIDELINES_TOOL_CALL_CN
                    }
                }
            prompt = prompt
                .replace("TOOL_USAGE_GUIDELINES_SECTION", if (useEnglish) TOOL_USAGE_BRIEF_EN else TOOL_USAGE_BRIEF_CN)
                .replace("PACKAGE_SYSTEM_GUIDELINES_SECTION", packageGuidelines)
                .replace("AVAILABLE_TOOLS_SECTION", "")
        } else {
            prompt = prompt
                .replace("TOOL_USAGE_GUIDELINES_SECTION", if (useEnglish) TOOL_USAGE_GUIDELINES_EN else TOOL_USAGE_GUIDELINES_CN)
                .replace("PACKAGE_SYSTEM_GUIDELINES_SECTION", if (useEnglish) PACKAGE_SYSTEM_GUIDELINES_EN else PACKAGE_SYSTEM_GUIDELINES_CN)
                .replace("AVAILABLE_TOOLS_SECTION", if (useEnglish) availableToolsEn else availableToolsCn)
        }
    } else {
        if (enableMemoryQuery) {
            // Only memory tools are available, package system is disabled
            prompt = prompt
                .replace("TOOL_USAGE_GUIDELINES_SECTION", if (useEnglish) TOOL_USAGE_GUIDELINES_EN else TOOL_USAGE_GUIDELINES_CN)
                .replace("PACKAGE_SYSTEM_GUIDELINES_SECTION", "")
                .replace(
                    "AVAILABLE_TOOLS_SECTION",
                    if (useEnglish) getMemoryToolsEn(toolVisibility) else getMemoryToolsCn(toolVisibility)
                )
        } else {
            // Remove all guidance sections when tools and memory are disabled
            // Replace tool-related sections and remove behavior guidelines and workspace guidelines
            prompt = prompt
                .replace("TOOL_USAGE_GUIDELINES_SECTION", "")
                .replace("PACKAGE_SYSTEM_GUIDELINES_SECTION", "")
                .replace("AVAILABLE_TOOLS_SECTION", "")
                .replace(if (useEnglish) BEHAVIOR_GUIDELINES_EN else BEHAVIOR_GUIDELINES_CN, "")
                .replace(workspaceGuidelines, "")
        }
    }


    if (disableLatexDescription) {
        prompt = prompt
                .replace(Regex("(?m)^\\s*FORMULA FORMATTING:.*(?:\\r?\\n)?"), "")
                .replace(Regex("(?m)^\\s*公式格式化：.*(?:\\r?\\n)?"), "")
    }

    // Clean up multiple consecutive blank lines (replace 3+ newlines with 2)
    prompt = prompt.replace(Regex("\n{3,}"), "\n\n")

    return prompt
  }
  
  /**
   * Generates the dynamic web workspace guidelines based on the provided path.
   *
   * @param workspacePath The current path of the workspace. Null if not bound.
   * @param useEnglish Whether to use the English or Chinese version of the guidelines.
   * @return A string containing the appropriate workspace guidelines.
   */
  private fun getWorkspaceGuidelines(workspacePath: String?, workspaceEnv: String?, useEnglish: Boolean): String {
      val envLabel = workspaceEnv?.trim().orEmpty().ifBlank { "android" }
      val shouldShowEnv = envLabel.isNotBlank()
      return if (workspacePath != null) {
          if (useEnglish) {
              """
              WEB WORKSPACE GUIDELINES:
              - Your working directory, `$workspacePath`${if (shouldShowEnv) " (environment=$envLabel)" else ""}, is automatically set up as a web server root.
              - Use the `apply_file` tool to create web files (HTML/CSS/JS).
              - The main file must be `index.html` for user previews.
              - It's recommended to split code into multiple files for better stability and maintainability.
              - For more complex projects, consider creating `js` and `css` folders and organizing files accordingly.
              - Always use relative paths for file references.
              ${if (shouldShowEnv) "- When reading/writing workspace files via tools, pass `environment=\"$envLabel\"` and use absolute paths like `/...`." else ""}
              - Terminal mount note: common mounts include `/storage/emulated/0 -> /sdcard`, `/storage/emulated/0 -> /storage/emulated/0`, and app sandbox `/data/user/0/com.ai.assistance.custard/files -> same path`.
              - If the workspace is under mounted paths, execute workspace files directly in the Linux terminal environment; do not copy files before execution.
              - **Best Practice for Code Modifications**: Before modifying any file, use `grep_code` and `grep_context` to locate and understand relevant code with surrounding context. This ensures you understand the codebase structure before making changes.
              """.trimIndent()
          } else {
              """
              Web工作区指南：
              - 你的工作目录，$workspacePath${if (shouldShowEnv) "（environment=$envLabel）" else ""}，已被自动配置为Web服务器的根目录。
              - 使用 apply_file 工具创建网页文件 (HTML/CSS/JS)。
              - 主文件必须是 index.html，用户可直接预览。
              - 建议将代码拆分到不同文件，以提高稳定性和可维护性。
              - 如果项目较为复杂，可以考虑新建js文件夹和css文件夹并创建多个文件。
              - 文件引用请使用相对路径。
              ${if (shouldShowEnv) "- 通过工具读写工作区文件时，请带上 `environment=\"$envLabel\"`，并使用 `/...` 形式的绝对路径。" else ""}
              - 终端挂载说明：常见挂载包括 `/storage/emulated/0 -> /sdcard`、`/storage/emulated/0 -> /storage/emulated/0`，以及应用沙箱 `/data/user/0/com.ai.assistance.custard/files -> 同路径`。
              - 若工作区位于已挂载路径中，直接在 Linux 终端环境中执行工作区文件；无需先复制再执行。
              - **代码修改最佳实践**：修改任何文件之前，建议组合使用 `grep_code` 与 `grep_context` 定位并理解相关代码及其上下文，避免在未理解项目结构时盲改。
              """.trimIndent()
          }
      } else {
          if (useEnglish) {
              """
              WEB WORKSPACE GUIDELINES:
              - A web workspace is not yet configured for this chat. To enable web development features, please prompt the user to click the 'Web' button in the top-right corner of the app to bind a workspace directory.
              """.trimIndent()
          } else {
              """
              Web工作区指南：
              - 当前对话尚未配置Web工作区。如需启用Web开发功能，请提示用户点击应用右上角的 "Web" 按钮来绑定一个工作区目录。
              """.trimIndent()
          }
      }
  }

  /**
   * Generates the system prompt with dynamic package information and custom prompts
   *
   * @param packageManager The PackageManager instance to get package information from
   * @param workspacePath The current workspace path, if available.
   * @param customIntroPrompt Custom introduction prompt text
   * @param thinkingGuidance Whether thinking guidance is enabled
   * @param customSystemPromptTemplate Custom system prompt template (empty means use built-in)
   * @param enableTools Whether tools are enabled
   * @param enableMemoryQuery Whether the AI is allowed to query memories.
   * @param hasImageRecognition Whether image recognition service is configured
   * @param chatModelHasDirectImage Whether the chat model has direct image capability
   * @return The complete system prompt with custom prompts and package information
   */
  fun getSystemPromptWithCustomPrompts(
          packageManager: PackageManager,
          workspacePath: String?,
          workspaceEnv: String? = null,
          safBookmarkNames: List<String> = emptyList(),
          customIntroPrompt: String,
          useEnglish: Boolean = false,
          thinkingGuidance: Boolean = false,
          customSystemPromptTemplate: String = "",
          enableTools: Boolean = true,
          enableMemoryQuery: Boolean = true,
          hasImageRecognition: Boolean = false,
          chatModelHasDirectImage: Boolean = false,
          hasAudioRecognition: Boolean = false,
          hasVideoRecognition: Boolean = false,
          chatModelHasDirectAudio: Boolean = false,
          chatModelHasDirectVideo: Boolean = false,
          useToolCallApi: Boolean = false,
          strictToolCall: Boolean = false,
          disableLatexDescription: Boolean = false,
          toolVisibility: Map<String, Boolean> = emptyMap()
  ): String {
    // Get the base system prompt
    val basePrompt = getSystemPrompt(
        packageManager = packageManager,
        workspacePath = workspacePath,
        workspaceEnv = workspaceEnv,
        safBookmarkNames = safBookmarkNames,
        useEnglish = useEnglish,
        thinkingGuidance = thinkingGuidance,
        customSystemPromptTemplate = customSystemPromptTemplate,
        enableTools = enableTools,
        enableMemoryQuery = enableMemoryQuery,
        hasImageRecognition = hasImageRecognition,
        chatModelHasDirectImage = chatModelHasDirectImage,
        hasAudioRecognition = hasAudioRecognition,
        hasVideoRecognition = hasVideoRecognition,
        chatModelHasDirectAudio = chatModelHasDirectAudio,
        chatModelHasDirectVideo = chatModelHasDirectVideo,
        useToolCallApi = useToolCallApi,
        strictToolCall = strictToolCall,
        disableLatexDescription = disableLatexDescription,
        toolVisibility = toolVisibility
    )

    // Apply custom prompts
    return applyCustomPrompts(basePrompt, customIntroPrompt)
  }

  /** Original method for backward compatibility */
  fun getSystemPrompt(packageManager: PackageManager): String {
    return getSystemPrompt(
        packageManager = packageManager,
        workspacePath = null,
        workspaceEnv = null,
        safBookmarkNames = emptyList(),
        useEnglish = false,
        thinkingGuidance = false,
        customSystemPromptTemplate = "",
        enableTools = true,
        enableMemoryQuery = true,
        hasImageRecognition = false,
        chatModelHasDirectImage = false,
        hasAudioRecognition = false,
        hasVideoRecognition = false,
        chatModelHasDirectAudio = false,
        chatModelHasDirectVideo = false,
        useToolCallApi = false,
        strictToolCall = false,
        disableLatexDescription = false
    )
  }
}
