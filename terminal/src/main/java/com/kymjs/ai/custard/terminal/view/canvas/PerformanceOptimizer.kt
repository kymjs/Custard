package com.kymjs.ai.custard.terminal.view.canvas

import android.graphics.Rect
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * 性能优化器
 * 包含脏区域追踪、帧率自适应、内存池等优化
 */
class PerformanceOptimizer {
    
    companion object {
        private const val TAG = "PerformanceOptimizer"
        private const val TARGET_FPS = 60f
        private const val MIN_FPS = 15f
        private const val MAX_FRAME_TIME_MS = 16L // ~60fps
    }
    
    /**
     * 脏区域追踪器
     */
    class DirtyRegionTracker {
        private val dirtyRegions = mutableListOf<Rect>()
        private var fullRedrawNeeded = true
        
        fun markDirty(rect: Rect) {
            dirtyRegions.add(Rect(rect))
            fullRedrawNeeded = false
        }
        
        fun markDirtyCell(row: Int, col: Int, charWidth: Float, charHeight: Float) {
            val rect = Rect(
                (col * charWidth).toInt(),
                (row * charHeight).toInt(),
                ((col + 1) * charWidth).toInt(),
                ((row + 1) * charHeight).toInt()
            )
            markDirty(rect)
        }
        
        fun markFullRedraw() {
            fullRedrawNeeded = true
            dirtyRegions.clear()
        }
        
        fun needsFullRedraw(): Boolean = fullRedrawNeeded
        
        fun getDirtyRegions(): List<Rect> = dirtyRegions.toList()
        
        fun clearDirty() {
            dirtyRegions.clear()
            fullRedrawNeeded = false
        }
        
        /**
         * 合并相邻的脏区域以减少绘制调用
         */
        fun optimizeDirtyRegions(): List<Rect> {
            if (dirtyRegions.isEmpty()) return emptyList()
            
            val merged = mutableListOf<Rect>()
            val sorted = dirtyRegions.sortedBy { it.top * 10000 + it.left }
            
            var current = Rect(sorted[0])
            for (i in 1 until sorted.size) {
                val rect = sorted[i]
                if (shouldMerge(current, rect)) {
                    current.union(rect)
                } else {
                    merged.add(current)
                    current = Rect(rect)
                }
            }
            merged.add(current)
            
            return merged
        }
        
        private fun shouldMerge(r1: Rect, r2: Rect): Boolean {
            // 如果区域相交或非常接近，则合并
            val threshold = 50
            return Rect.intersects(r1, r2) ||
                   (r1.bottom >= r2.top - threshold && r1.right >= r2.left - threshold)
        }
    }
    
    /**
     * 帧率自适应控制器
     */
    class AdaptiveFrameRateController {
        private val frameTimes = ArrayDeque<Long>(60)
        private var lastActivityTime = System.currentTimeMillis()
        private var currentFps = TARGET_FPS
        
        fun recordFrameTime(frameTimeMs: Long) {
            frameTimes.addLast(frameTimeMs)
            if (frameTimes.size > 60) {
                frameTimes.removeFirst()
            }
        }
        
        fun notifyActivity() {
            lastActivityTime = System.currentTimeMillis()
        }
        
        fun getAdaptiveSleepTime(): Long {
            val idleTime = System.currentTimeMillis() - lastActivityTime
            
            // 如果空闲超过1秒，降低帧率
            return when {
                idleTime > 5000 -> 100 // 10fps when very idle
                idleTime > 1000 -> 33  // 30fps when idle
                else -> MAX_FRAME_TIME_MS // 60fps when active
            }
        }
        
        fun getCurrentFps(): Float {
            if (frameTimes.isEmpty()) return currentFps
            
            val avgFrameTime = frameTimes.average()
            currentFps = if (avgFrameTime > 0) 1000f / avgFrameTime.toFloat() else TARGET_FPS
            return currentFps
        }
        
        fun shouldRender(): Boolean {
            val idleTime = System.currentTimeMillis() - lastActivityTime
            // 如果超过5秒没有活动，每秒只渲染一次
            return idleTime < 5000 || (System.currentTimeMillis() % 1000) < 50
        }
    }
    
    /**
     * 内存池 - 复用对象以减少GC压力
     */
    class ObjectPool<T>(
        private val factory: () -> T,
        private val reset: (T) -> Unit,
        private val maxSize: Int = 50
    ) {
        private val pool = ArrayDeque<T>(maxSize)
        
        fun acquire(): T {
            return pool.removeFirstOrNull() ?: factory()
        }
        
        fun release(obj: T) {
            if (pool.size < maxSize) {
                reset(obj)
                pool.addLast(obj)
            }
        }
        
        fun clear() {
            pool.clear()
        }
    }
    
    /**
     * 性能指标收集器
     */
    class PerformanceMetrics {
        private var frameCount = 0L
        private var totalFrameTime = 0L
        private var lastReportTime = System.currentTimeMillis()
        
        fun recordFrame(frameTimeMs: Long) {
            frameCount++
            totalFrameTime += frameTimeMs
            
            // 每秒报告一次
            val now = System.currentTimeMillis()
            if (now - lastReportTime >= 1000) {
                val avgFrameTime = if (frameCount > 0) totalFrameTime / frameCount else 0
                val fps = if (avgFrameTime > 0) 1000f / avgFrameTime else 0f
                
                Log.d(TAG, "Performance: FPS=${"%.1f".format(fps)}, AvgFrameTime=${avgFrameTime}ms, Frames=$frameCount")
                
                frameCount = 0
                totalFrameTime = 0
                lastReportTime = now
            }
        }
        
        fun getCurrentStats(): Stats {
            val avgFrameTime = if (frameCount > 0) totalFrameTime / frameCount else 0
            val fps = if (avgFrameTime > 0) 1000f / avgFrameTime else 0f
            return Stats(fps, avgFrameTime)
        }
        
        data class Stats(val fps: Float, val avgFrameTimeMs: Long)
    }
}

