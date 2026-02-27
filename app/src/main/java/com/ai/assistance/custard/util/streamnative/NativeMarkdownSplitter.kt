package com.ai.assistance.custard.util.streamnative

object NativeMarkdownSplitter {

    init {
        System.loadLibrary("streamnative")
    }

    private external fun nativeCreateBlockSession(): Long
    private external fun nativeCreateInlineSession(): Long
    private external fun nativeDestroySession(handle: Long)
    private external fun nativePush(handle: Long, chunk: String): IntArray

    class Session internal constructor(
        private val handle: Long,
    ) {
        fun push(chunk: String): IntArray = nativePush(handle, chunk)
        fun destroy() = nativeDestroySession(handle)
    }

    fun createBlockSession(): Session = Session(nativeCreateBlockSession())
    fun createInlineSession(): Session = Session(nativeCreateInlineSession())
}
