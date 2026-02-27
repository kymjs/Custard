package com.ai.assistance.custard.terminal.provider

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import com.ai.assistance.custard.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.custard.terminal.provider.filesystem.LocalFileSystemProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * Ubuntu文件系统的DocumentsProvider
 * 
 * 通过Android的Storage Access Framework (SAF)暴露Ubuntu文件系统，
 * 允许其他应用通过标准文件选择器访问Ubuntu环境中的文件
 */
class UbuntuDocumentsProvider : DocumentsProvider() {
    
    companion object {
        private const val TAG = "UbuntuDocumentsProvider"
        
        // Authority需要与AndroidManifest中的声明一致
        private const val AUTHORITY = "com.ai.assistance.custard.documents.ubuntu"
        
        // Root ID
        private const val ROOT_ID = "ubuntu_root"
        
        // 默认文档列
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )
        
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }
    
    private lateinit var fileSystemProvider: LocalFileSystemProvider
    private lateinit var ubuntuRoot: File
    
    override fun onCreate(): Boolean {
        return try {
            val context = context ?: return false
            fileSystemProvider = LocalFileSystemProvider(context)
            ubuntuRoot = File(context.filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
            
            if (!ubuntuRoot.exists()) {
                Log.w(TAG, "Ubuntu root directory does not exist: ${ubuntuRoot.absolutePath}")
            }
            
            Log.d(TAG, "UbuntuDocumentsProvider initialized, root: ${ubuntuRoot.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize provider", e)
            false
        }
    }
    
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
        )
        row.add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_view)
        row.add(DocumentsContract.Root.COLUMN_TITLE, "Custard Ubuntu")
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, "Access Custard Ubuntu terminal files")
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, getDocIdForFile(ubuntuRoot))
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, ubuntuRoot.freeSpace)
        
        return result
    }
    
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId)
        return result
    }
    
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = getFileForDocId(parentDocumentId)
        
        if (!parent.isDirectory) {
            Log.w(TAG, "queryChildDocuments called on non-directory: $parentDocumentId")
            return result
        }
        
        val files = parent.listFiles() ?: emptyArray()
        for (file in files) {
            // 显示隐藏文件（以.开头）
            includeFile(result, file)
        }
        
        return result
    }
    
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = resolveFileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        if ((accessMode and ParcelFileDescriptor.MODE_CREATE) != 0) {
            file.parentFile?.mkdirs()
        }
        return ParcelFileDescriptor.open(file, accessMode)
    }
    
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String? {
        val parent = getFileForDocId(parentDocumentId)
        
        if (!parent.isDirectory) {
            throw IllegalArgumentException("Parent is not a directory")
        }
        
        val file = File(parent, displayName)
        
        try {
            if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType) {
                if (!file.mkdir()) {
                    throw IllegalStateException("Failed to create directory")
                }
            } else {
                if (!file.createNewFile()) {
                    throw IllegalStateException("Failed to create file")
                }
            }
            
            return getDocIdForFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create document", e)
            throw e
        }
    }
    
    override fun deleteDocument(documentId: String) {
        val file = getFileForDocId(documentId)
        
        if (!file.exists()) {
            throw FileNotFoundException("File not found: ${file.absolutePath}")
        }
        
        if (!file.delete()) {
            throw IllegalStateException("Failed to delete file")
        }
    }
    
    override fun renameDocument(documentId: String, displayName: String): String? {
        val sourceFile = getFileForDocId(documentId)
        val destFile = File(sourceFile.parentFile, displayName)
        
        if (!sourceFile.renameTo(destFile)) {
            throw IllegalStateException("Failed to rename file")
        }
        
        return getDocIdForFile(destFile)
    }
    
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = getFileForDocId(parentDocumentId)
        val child = getFileForDocId(documentId)
        
        return try {
            child.canonicalPath.startsWith(parent.canonicalPath)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking child document", e)
            false
        }
    }
    
    /**
     * 将文件信息添加到cursor
     */
    private fun includeFile(result: MatrixCursor, documentId: String) {
        val file = getFileForDocId(documentId)
        includeFile(result, file)
    }
    
    private fun includeFile(result: MatrixCursor, file: File) {
        val docId = getDocIdForFile(file)
        
        var flags = 0
        if (file.isDirectory) {
            // 目录支持创建子文件
            if (file.canWrite()) {
                flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            }
        } else if (file.canWrite()) {
            // 文件支持写入和删除
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        }
        
        // 支持重命名
        if (file.parentFile?.canWrite() == true) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        }
        
        val displayName = file.name
        val mimeType = if (file.isDirectory) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            getMimeType(file)
        }
        
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
    }
    
    /**
     * 根据文件获取Document ID
     * 使用相对于Ubuntu根目录的路径作为ID
     */
    private fun getDocIdForFile(file: File): String {
        val path = file.absolutePath
        val rootPath = ubuntuRoot.absolutePath
        
        return if (path == rootPath) {
            "/"
        } else if (path.startsWith(rootPath)) {
            path.substring(rootPath.length)
        } else {
            throw IllegalArgumentException("File is not under Ubuntu root: $path")
        }
    }
    
    /**
     * 根据Document ID获取文件
     */
    private fun getFileForDocId(documentId: String): File {
        val file = resolveFileForDocId(documentId)
        if (!file.exists()) {
            throw FileNotFoundException("File not found: ${file.absolutePath}")
        }
        return file
    }

    private fun resolveFileForDocId(documentId: String): File {
        val normalizedId = normalizeDocumentId(documentId)
        val file = if (normalizedId == "/") {
            ubuntuRoot
        } else {
            val relativePath = normalizedId.trimStart('/')
            File(ubuntuRoot, relativePath)
        }

        val canonicalRoot = ubuntuRoot.canonicalFile
        val canonicalFile = file.canonicalFile
        val canonicalRootPath = canonicalRoot.path
        val canonicalFilePath = canonicalFile.path
        if (canonicalFile != canonicalRoot &&
            !canonicalFilePath.startsWith(canonicalRootPath + File.separator)
        ) {
            throw FileNotFoundException("Invalid documentId: $documentId")
        }

        return file
    }

    private fun normalizeDocumentId(documentId: String): String {
        var id = documentId.trim()

        if (id.startsWith("content://")) {
            try {
                val uri = android.net.Uri.parse(id)
                val treeId = DocumentsContract.getTreeDocumentId(uri)
                if (!treeId.isNullOrBlank()) {
                    id = treeId
                } else {
                    val docId = DocumentsContract.getDocumentId(uri)
                    if (!docId.isNullOrBlank()) {
                        id = docId
                    }
                }
            } catch (_: Exception) {
            }
        }

        val docMarker = "/document/"
        val treeMarker = "/tree/"

        val lastDocIdx = id.lastIndexOf(docMarker)
        if (lastDocIdx >= 0) {
            id = id.substring(lastDocIdx + docMarker.length)
        }
        val lastTreeIdx = id.lastIndexOf(treeMarker)
        if (lastTreeIdx >= 0) {
            id = id.substring(lastTreeIdx + treeMarker.length)
        }

        while (id.startsWith("//")) {
            id = id.substring(1)
        }

        if (id.isEmpty()) {
            return "/"
        }
        if (!id.startsWith("/")) {
            id = "/$id"
        }
        return id
    }
    
    /**
     * 获取文件的MIME类型
     */
    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        
        return when {
            extension.isEmpty() -> "application/octet-stream"
            else -> {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    ?: "application/octet-stream"
            }
        }
    }
}
