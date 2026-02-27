package com.ai.assistance.custard.terminal.provider.type

import com.ai.assistance.custard.terminal.Pty
import com.ai.assistance.custard.terminal.TerminalSession
import com.ai.assistance.custard.terminal.provider.filesystem.FileSystemProvider

/**
 * 终端提供者抽象接口
 * 
 * 支持不同类型的终端实现（本地、SSH等）
 */
interface TerminalProvider {
    
    /**
     * 是否已连接/可用
     */
    suspend fun isConnected(): Boolean
    
    /**
     * 连接到终端（对于SSH需要先连接）
     */
    suspend fun connect(): Result<Unit>
    
    /**
     * 断开连接
     */
    suspend fun disconnect()
    
    /**
     * 启动终端会话
     * 
     * @param sessionId 会话ID
     * @return 终端会话和PTY的配对
     */
    suspend fun startSession(sessionId: String): Result<Pair<TerminalSession, Pty>>
    
    /**
     * 关闭终端会话
     * 
     * @param sessionId 会话ID
     */
    suspend fun closeSession(sessionId: String)
    
    /**
     * 获取文件系统提供者
     * 
     * @return 对应的文件系统提供者
     */
    fun getFileSystemProvider(): FileSystemProvider
    
    /**
     * 获取工作目录
     * 
     * @return 当前工作目录
     */
    suspend fun getWorkingDirectory(): String
    
    /**
     * 获取环境变量
     * 
     * @return 环境变量映射
     */
    fun getEnvironment(): Map<String, String>
}

/**
 * 终端类型枚举
 */
enum class TerminalType {
    /**
     * 本地终端（proot + Ubuntu）
     */
    LOCAL,
    
    /**
     * SSH 远程终端
     */
    SSH,
    
    /**
     * ADB 终端（未来可能支持）
     */
    ADB
}

