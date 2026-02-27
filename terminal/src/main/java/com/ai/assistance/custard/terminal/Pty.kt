package com.ai.assistance.custard.terminal

import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

open class Pty(
    val process: Process,
    val masterFd: FileDescriptor?,
    private val ptyMaster: Int,
    val stdout: InputStream,
    val stdin: OutputStream
) {
    // 为本地终端提供的便利构造函数
    constructor(process: Process, masterFd: FileDescriptor, ptyMaster: Int) : this(
        process = process,
        masterFd = masterFd,
        ptyMaster = ptyMaster,
        stdout = FileInputStream(masterFd),
        stdin = FileOutputStream(masterFd)
    )

    fun waitFor(): Int {
        return process.waitFor()
    }

    fun destroy() {
        process.destroy()
        try {
            // It's important to close the master FD to signal EOF to the process
            stdout.close()
            stdin.close()
        } catch (e: IOException) {
            Log.e("Pty", "Error closing PTY streams", e)
        }
    }

    companion object {
        private const val TAG = "Pty"

        init {
            try {
                System.loadLibrary("pty")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libpty.so", e)
                // Handle error appropriately, maybe disable PTY functionality
            }
        }

        @Throws(IOException::class)
        fun start(command: Array<String>, environment: Map<String, String>, workingDir: File): Pty {
            val envArray = environment.map { "${it.key}=${it.value}" }.toTypedArray()

            // This will return an array of two integers: { pid, masterFd }
            val processInfo = createSubprocess(command, envArray, workingDir.absolutePath)
            val pid = processInfo[0]
            val masterFdInt = processInfo[1]

            if (pid <= 0 || masterFdInt <= 0) {
                throw IOException("Failed to create subprocess with PTY. pid=$pid, fd=$masterFdInt")
            }

            val fileDescriptor = Reflect.getFileDescriptor(masterFdInt)
            
            // We need a Process object to manage the subprocess lifetime
            val dummyProcess = object : Process() {
                override fun destroy() {
                    // Send SIGHUP to the process group to ensure all child processes are terminated
                    try {
                        android.os.Process.sendSignal(pid, 1) // SIGHUP
                    } catch (e: Exception) {
                        // Ignore
                    }
                    
                    // Send SIGKILL to ensure the process is dead immediately
                    try {
                        android.os.Process.sendSignal(pid, 9) // SIGKILL
                    } catch (e: Exception) {
                        // Ignore
                    }
                }

                override fun exitValue(): Int {
                    // We can't get the actual exit value without a blocking waitpid call,
                    // which we do in waitFor(). The contract of exitValue() is to throw
                    // an exception if the process is still running.
                    try {
                        // sendSignal(pid, 0) checks if the process exists.
                        // If it doesn't throw, the process is still alive.
                        android.os.Process.sendSignal(pid, 0)
                        throw IllegalThreadStateException("Process hasn't exited")
                    } catch (e: Exception) {
                        // The process is dead. We don't have the exit code without waiting,
                        // so we can't fulfill the contract perfectly. Returning 0 is a
                        // reasonable fallback for a terminated process where the specific
                        // exit code isn't available.
                        return 0
                    }
                }

                override fun getErrorStream(): InputStream? = null
                override fun getInputStream(): InputStream? = null
                override fun getOutputStream(): OutputStream? = null

                override fun waitFor(): Int {
                    return Companion.waitFor(pid)
                }
            }
            
            return Pty(dummyProcess, fileDescriptor, masterFdInt)
        }

        private external fun createSubprocess(cmdArray: Array<String>, envArray: Array<String>, workingDir: String): IntArray

        private external fun waitFor(pid: Int): Int
        
        /**
         * 获取终端标志位
         * bit 0: ICANON - canonical mode (line-buffered input)
         * bit 1: ECHO - echo input characters
         * bit 2: ISIG - generate signals for special characters
         * bit 3: IEXTEN - enable extended input processing
         */
        private external fun getTerminalFlags(fd: Int): Int
        
        /**
         * 获取可读字节数（用于检测是否有输出等待读取）
         */
        private external fun getAvailableBytes(fd: Int): Int
    }
    
    /**
     * 获取 PTY 模式信息
     */
    open fun getPtyMode(): PtyMode {
        if (ptyMaster <= 0) {
            // SSH 等远程终端返回默认模式
            return PtyMode(
                isCanonicalMode = true,
                isEchoEnabled = true,
                isSignalEnabled = true,
                isExtendedEnabled = true,
                availableBytes = 0
            )
        }
        
        val flags = Companion.getTerminalFlags(ptyMaster)
        val availableBytes = Companion.getAvailableBytes(ptyMaster)
        
        return PtyMode(
            isCanonicalMode = (flags and 0x01) != 0,
            isEchoEnabled = (flags and 0x02) != 0,
            isSignalEnabled = (flags and 0x04) != 0,
            isExtendedEnabled = (flags and 0x08) != 0,
            availableBytes = availableBytes
        )
    }
    
    /**
     * 设置 PTY 窗口大小
     * @param rows 行数
     * @param cols 列数
     * @return true 表示成功，false 表示失败
     */
    open fun setWindowSize(rows: Int, cols: Int): Boolean {
        if (ptyMaster <= 0) {
            // SSH 等远程终端由子类实现
            return false
        }
        
        val result = setPtyWindowSize(ptyMaster, rows, cols)
        if (result == 0) {
            Log.d("Pty", "PTY window size updated to ${rows}x${cols}")
            return true
        } else {
            Log.e("Pty", "Failed to set PTY window size to ${rows}x${cols}")
            return false
        }
    }
    
    private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int
}

/**
 * PTY 模式信息
 * 用于检测终端是否处于交互式输入状态
 */
data class PtyMode(
    val isCanonicalMode: Boolean,  // true = 行缓冲模式（正常命令），false = 字符模式（交互式输入）
    val isEchoEnabled: Boolean,     // 是否回显输入
    val isSignalEnabled: Boolean,   // 是否启用信号处理
    val isExtendedEnabled: Boolean, // 是否启用扩展处理
    val availableBytes: Int         // 可读字节数
) {
    /**
     * 判断是否正在等待交互式输入
     * 
     * 两种场景：
     * 1. 非规范模式（Node.js REPL, Python REPL）：禁用 ICANON，字符模式输入
     * 2. 规范模式但等待输入（apt upgrade, sudo）：保持 ICANON，但输出已停止
     */
    fun isWaitingForInput(): Boolean {
        // 场景 1: 非规范模式 = REPL（Node/Python）
        if (!isCanonicalMode && availableBytes == 0) {
            return true
        }
        
        // 场景 2: 规范模式但输出已停止 = 等待确认（apt/sudo）
        // 条件：缓冲区为空（输出已停止）
        if (availableBytes == 0) {
            // 需要由上层结合命令状态判断（是否有命令正在执行）
            return true
        }
        
        return false
    }
}

// Reflection helper to create FileDescriptor from an int fd.
object Reflect {
    fun getFileDescriptor(fd: Int): FileDescriptor {
        val fileDescriptor = FileDescriptor()
        try {
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.set(fileDescriptor, fd)
        } catch (e: Exception) {
            throw IOException("Failed to create FileDescriptor from integer fd", e)
        }
        return fileDescriptor
    }
} 