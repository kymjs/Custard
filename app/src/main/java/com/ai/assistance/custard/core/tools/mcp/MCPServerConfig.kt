package com.ai.assistance.custard.core.tools.mcp

import kotlinx.serialization.Serializable

/** Configuration class for MCP Servers */
@Serializable
data class MCPServerConfig(
        val name: String,
        val endpoint: String,
        val description: String,
        val capabilities: List<String>,
        val extraData: Map<String, String>
)
