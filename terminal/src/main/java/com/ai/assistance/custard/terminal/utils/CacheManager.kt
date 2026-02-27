package com.ai.assistance.custard.terminal.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.ensureActive

class CacheManager(private val context: Context) {

    companion object {
        private const val TAG = "CacheManager"
    }

    private val filesDir: File = context.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val tmpDir: File = File(filesDir, "tmp")
    private val binDir: File = File(filesDir, "bin")

    private val forbiddenDeleteRoots: List<Path> = listOf(
        Paths.get("/sdcard"),
        Paths.get("/storage"),
        Paths.get("/mnt")
    )

    private fun ensureSafeDeleteTarget(target: File) {
        val filesDirCanonical = try {
            filesDir.canonicalFile
        } catch (e: Exception) {
            Log.e(TAG, "Reset safety: failed to resolve app filesDir", e)
            throw IllegalStateException("Unable to resolve app filesDir", e)
        }

        val targetCanonical = try {
            target.canonicalFile
        } catch (e: Exception) {
            Log.e(TAG, "Reset safety: failed to resolve delete target: ${target.absolutePath}", e)
            throw IllegalStateException("Unable to resolve delete target: ${target.absolutePath}", e)
        }

        val filesDirPath = filesDirCanonical.toPath().normalize()
        val targetPath = targetCanonical.toPath().normalize()

        if (targetPath == filesDirPath) {
            Log.e(TAG, "Reset safety: refusing to delete app filesDir: $targetPath")
            throw IllegalStateException("Refusing to delete app filesDir")
        }
        if (!targetPath.startsWith(filesDirPath)) {
            Log.e(TAG, "Reset safety: refusing to delete path outside app filesDir. target=$targetPath filesDir=$filesDirPath")
            throw IllegalStateException("Refusing to delete path outside app filesDir: $targetPath")
        }

        if (forbiddenDeleteRoots.any { targetPath.startsWith(it) }) {
            Log.e(TAG, "Reset safety: refusing to delete external storage path: $targetPath")
            throw IllegalStateException("Refusing to delete external storage path: $targetPath")
        }
    }

    private fun shQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun decodeProcMountField(value: String): String {
        return value
            .replace("\\040", " ")
            .replace("\\011", "\t")
            .replace("\\012", "\n")
            .replace("\\134", "\\")
    }

    private fun readMountPoints(): List<String> {
        val candidates = listOf("/proc/self/mounts", "/proc/mounts")
        val mountsFile = candidates.firstOrNull { File(it).canRead() } ?: return emptyList()
        return try {
            File(mountsFile).readLines()
                .mapNotNull { line ->
                    val parts = line.split(' ')
                    val rawMountPoint = parts.getOrNull(1) ?: return@mapNotNull null
                    decodeProcMountField(rawMountPoint)
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getMountPointsUnder(root: File): List<String> {
        val rootPathAbs = root.absolutePath.trimEnd('/')
        val rootPathCanonical = try {
            root.canonicalFile.absolutePath.trimEnd('/')
        } catch (_: Exception) {
            rootPathAbs
        }

        val prefixes = listOf(rootPathAbs, rootPathCanonical)
            .map { if (it.isEmpty()) "/" else it }
            .distinct()

        return readMountPoints().filter { mp ->
            val mpTrimmed = mp.trimEnd('/')
            prefixes.any { p -> mpTrimmed == p || mpTrimmed.startsWith("$p/") }
        }
    }

    private fun runSuCommand(command: String): Int? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            try {
                val output = process.inputStream.bufferedReader().readText()
                if (output.isNotBlank()) {
                    Log.d(TAG, "su output: ${output.take(500)}")
                }
            } catch (_: Exception) {
            }
            process.waitFor()
        } catch (_: Exception) {
            null
        }
    }

    private fun tryUnmount(mountPoint: String): Boolean {
        val busyboxPath = File(usrDir, "bin/busybox").absolutePath
        val mp = shQuote(mountPoint)
        val bb = shQuote(busyboxPath)
        val cmd = "(umount $mp || umount -l $mp || $bb umount $mp || $bb umount -l $mp) >/dev/null 2>&1"
        Log.d(TAG, "Reset: try unmount $mountPoint")
        val code = runSuCommand(cmd)
        if (code == null) {
            Log.w(TAG, "Reset: su not available or command failed to start; cannot unmount $mountPoint")
            return false
        }
        val ok = code == 0
        Log.d(TAG, "Reset: unmount $mountPoint -> exit=$code")
        return ok
    }

    private fun ensureNoActiveMountsUnder(root: File) {
        val before = getMountPointsUnder(root)
        if (before.isEmpty()) {
            Log.d(TAG, "Reset: no active mount points under ${root.absolutePath}")
            return
        }

        Log.w(TAG, "Reset: detected mount points under ${root.absolutePath}:\n${before.distinct().sorted().joinToString("\n")}")

        val sorted = before.distinct().sortedByDescending { it.length }
        sorted.forEach { mp ->
            tryUnmount(mp)
        }

        val after = getMountPointsUnder(root)
        if (after.isNotEmpty()) {
            val list = after.distinct().sorted().joinToString("\n")
            Log.e(TAG, "Reset blocked: mount points still active under ${root.absolutePath}:\n$list")
            throw IllegalStateException("Reset blocked: mount points still active under ${root.absolutePath}:\n$list")
        }

        Log.d(TAG, "Reset: all mount points under ${root.absolutePath} have been unmounted")
    }

    private fun isSymbolicLink(file: File): Boolean {
        return try {
            Files.isSymbolicLink(file.toPath())
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteRecursivelyNoFollow(dir: File) {
        if (!dir.exists()) return
        ensureSafeDeleteTarget(dir)
        Log.d(TAG, "Reset: deleting directory tree (no-follow): ${dir.absolutePath}")
        val root = dir.toPath()
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    return try {
                        if (Files.isSymbolicLink(dir)) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    } catch (_: Exception) {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    try {
                        Files.deleteIfExists(file)
                    } catch (_: Exception) {
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    try {
                        Files.deleteIfExists(dir)
                    } catch (_: Exception) {
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                    try {
                        Files.deleteIfExists(file)
                    } catch (_: Exception) {
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Reset: failed to delete directory tree (no-follow): ${dir.absolutePath}", e)
        }
    }

    private fun deleteBinLinks() {
        if (!binDir.exists()) return
        binDir.listFiles()?.forEach { file ->
            if (isSymbolicLink(file)) {
                try {
                    Files.deleteIfExists(file.toPath())
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun getCacheSize(onProgress: (bytes: Long) -> Unit): Long = withContext(Dispatchers.IO) {
        var totalSize = 0L
        var filesProcessed = 0
        val seenFiles = mutableSetOf<Any>()
        onProgress(0L) // Initial progress

        listOf(usrDir, tmpDir).forEach { dir ->
            if (!dir.exists()) return@forEach

            val rootPathNormalized = try {
                dir.canonicalFile.absolutePath.trimEnd('/')
            } catch (_: Exception) {
                dir.absolutePath.trimEnd('/')
            }

            val mountPointsUnderRoot = getMountPointsUnder(dir)
                .map { it.trimEnd('/') }
                .filter { it != rootPathNormalized }
                .toHashSet()

            val mountPointsUnderRootCanonical = mountPointsUnderRoot.mapNotNull { mp ->
                try {
                    File(mp).canonicalFile.absolutePath.trimEnd('/')
                } catch (_: Exception) {
                    null
                }
            }.toHashSet()

            val skipPathPrefixes = if (dir == usrDir) {
                val ubuntuPath = File(usrDir, "var/lib/proot-distro/installed-rootfs/ubuntu")
                val homeDirCandidates = listOf(
                    filesDir.absolutePath,
                    try {
                        filesDir.canonicalFile.absolutePath
                    } catch (_: Exception) {
                        null
                    }
                ).filterNotNull().distinct()

                val homeBindMountTargets = homeDirCandidates.map { homeDir ->
                    File(ubuntuPath.absolutePath + homeDir)
                }

                listOf(
                    File(ubuntuPath, "sdcard"),
                    File(ubuntuPath, "storage"),
                    File(ubuntuPath, "data"),
                    File(ubuntuPath, "proc"),
                    File(ubuntuPath, "sys"),
                    File(ubuntuPath, "dev")
                ).plus(homeBindMountTargets).flatMap { f ->
                    val abs = f.absolutePath.trimEnd('/')
                    val canon = try {
                        f.canonicalFile.absolutePath.trimEnd('/')
                    } catch (_: Exception) {
                        null
                    }
                    if (canon != null && canon != abs) listOf(abs, canon) else listOf(abs)
                }.toHashSet()
            } else {
                emptySet()
            }

            dir.walkTopDown().onEnter {
                if (!it.canRead() || isSymbolicLink(it)) return@onEnter false

                val currentPathAbs = it.absolutePath.trimEnd('/')
                val currentPathCanonical = try {
                    it.canonicalFile.absolutePath.trimEnd('/')
                } catch (_: Exception) {
                    currentPathAbs
                }

                if (currentPathAbs in mountPointsUnderRoot || currentPathCanonical in mountPointsUnderRoot) return@onEnter false
                if (currentPathAbs in mountPointsUnderRootCanonical || currentPathCanonical in mountPointsUnderRootCanonical) return@onEnter false
                if (skipPathPrefixes.any { p ->
                        currentPathAbs == p || currentPathCanonical == p ||
                        currentPathAbs.startsWith("$p/") || currentPathCanonical.startsWith("$p/")
                    }) return@onEnter false

                true
            }.forEach { file ->
                ensureActive() // 检查协程是否已被取消
                if (file.isFile && !isSymbolicLink(file)) {
                    try {
                        val path = file.toPath()

                        // On Linux, fileKey() returns an object containing inode and device ID.
                        // This allows us to correctly handle hard links and not double-count them.
                        val fileKey = try {
                            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
                        } catch (e: Exception) {
                            null // If we can't read attributes, assume no key.
                        }

                        if (fileKey != null) {
                            if (seenFiles.add(fileKey)) {
                                totalSize += file.length()
                            }
                        } else {
                            // Fallback for filesystems without fileKey support or if attribute read fails.
                            // This might overcount hard links, but it's the best we can do.
                            totalSize += file.length()
                        }

                        filesProcessed++
                        // To avoid overwhelming the main thread, update progress periodically.
                        if (filesProcessed % 200 == 0) {
                            onProgress(totalSize)
                        }
                    } catch (e: Exception) {
                        // Ignore files that can't be accessed, e.g. broken symlinks
                    }
                }
            }
        }

        onProgress(totalSize) // Final update with the total size
        totalSize
    }

    suspend fun clearCache(terminalManager: com.ai.assistance.custard.terminal.TerminalManager? = null) = withContext(Dispatchers.IO) {
        Log.w(TAG, "Reset: start. filesDir=${filesDir.absolutePath}")
        // 首先停止所有终端会话
        Log.d(TAG, "Reset: stopping terminal sessions...")
        terminalManager?.cleanup()

        // 等待一下确保进程完全停止
        kotlinx.coroutines.delay(1000)

        val prootDistroPath = File(usrDir, "var/lib/proot-distro")
        val ubuntuPath = File(prootDistroPath, "installed-rootfs/ubuntu")
        Log.d(TAG, "Reset: ubuntuPath=${ubuntuPath.absolutePath}")

        ensureNoActiveMountsUnder(usrDir)
        ensureNoActiveMountsUnder(tmpDir)
        Log.d(TAG, "Reset: removing install temp/lock dirs")
        deleteRecursivelyNoFollow(File(ubuntuPath.absolutePath + ".install.lock"))
        deleteRecursivelyNoFollow(File(ubuntuPath.absolutePath + ".install.tmp"))

        // 清理文件系统
        Log.d(TAG, "Reset: removing usr/tmp")
        deleteRecursivelyNoFollow(usrDir)
        deleteRecursivelyNoFollow(tmpDir)
        deleteBinLinks()

        // 清理其他相关文件
        val filesToClean = listOf(
            "common.sh",
            "proot-distro.zip",
            "ubuntu-noble-aarch64-pd-v4.18.0.tar.xz"
        )

        filesToClean.forEach { fileName ->
            val file = File(filesDir, fileName)
            if (file.exists()) {
                Log.d(TAG, "Reset: deleting file ${file.absolutePath}")
                file.delete()
            }
        }

        Log.w(TAG, "Reset: completed")
    }

    fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
} 