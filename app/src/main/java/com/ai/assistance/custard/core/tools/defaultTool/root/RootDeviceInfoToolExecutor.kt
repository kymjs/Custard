package com.ai.assistance.custard.core.tools.defaultTool.root

import android.content.Context
import com.ai.assistance.custard.core.tools.defaultTool.debugger.DebuggerDeviceInfoToolExecutor

/** Root级别的设备信息工具，继承管理员版本 */
open class RootDeviceInfoToolExecutor(context: Context) : DebuggerDeviceInfoToolExecutor(context) {
    // 当前阶段不添加新功能，仅继承管理员实现
}
