package com.ai.assistance.custard.util.stream

import com.ai.assistance.custard.util.AppLogger
/** 将字符串转换为字符流 用于在MarkdownTextComposable中将普通字符串转换为所需的字符流 */
fun String.stream(): Stream<Char> {
    return stream {
        for (c in this@stream) {
            emit(c)
        }
    }
}
