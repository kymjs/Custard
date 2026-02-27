package com.ai.assistance.custard.core.tools.mcp

import kotlinx.serialization.Serializable

/**
 * Represents an MCP tool definition
 */
@Serializable
data class MCPTool(
    val name: String,
    val description: String,
    val parameters: List<MCPToolParameter> = emptyList()
) 