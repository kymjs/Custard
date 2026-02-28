package com.kymjs.ai.custard.core.tools.defaultTool.root

import android.content.Context
import com.kymjs.ai.custard.core.tools.defaultTool.debugger.DebuggerFileSystemTools

/** Root级别的文件系统工具，继承管理员级别 */
open class RootFileSystemTools(context: Context) : DebuggerFileSystemTools(context) {
    // 当前阶段不添加新功能，仅继承管理员级别实现
}
