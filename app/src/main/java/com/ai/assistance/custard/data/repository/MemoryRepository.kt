package com.ai.assistance.custard.data.repository

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.ai.assistance.custard.R
import com.ai.assistance.custard.data.db.ObjectBoxManager
import com.ai.assistance.custard.data.model.Memory
import com.ai.assistance.custard.data.model.MemoryLink
import com.ai.assistance.custard.data.model.MemoryTag
import com.ai.assistance.custard.data.model.MemoryTag_
import com.ai.assistance.custard.data.model.Memory_
import com.ai.assistance.custard.data.model.DocumentChunk
import com.ai.assistance.custard.data.model.Embedding
import com.ai.assistance.custard.data.model.CloudEmbeddingConfig
import com.ai.assistance.custard.data.model.DimensionCount
import com.ai.assistance.custard.data.model.EmbeddingDimensionUsage
import com.ai.assistance.custard.data.model.EmbeddingRebuildProgress
import com.ai.assistance.custard.data.preferences.MemorySearchSettingsPreferences
import com.ai.assistance.custard.services.CloudEmbeddingService
import com.ai.assistance.custard.ui.features.memory.screens.graph.model.Edge
import com.ai.assistance.custard.ui.features.memory.screens.graph.model.Graph
import com.ai.assistance.custard.ui.features.memory.screens.graph.model.Node
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.query
import io.objectbox.query.QueryBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.objectbox.query.QueryCondition
import java.io.IOException
import java.util.UUID
import java.util.Date
import java.util.Locale
import com.ai.assistance.custard.data.model.MemoryExportData
import com.ai.assistance.custard.data.model.SerializableMemory
import com.ai.assistance.custard.data.model.SerializableLink
import com.ai.assistance.custard.data.model.ImportStrategy
import com.ai.assistance.custard.data.model.MemoryImportResult
import com.ai.assistance.custard.data.model.MemorySearchDebugCandidate
import com.ai.assistance.custard.data.model.MemorySearchDebugInfo
import com.ai.assistance.custard.data.model.MemoryScoreMode
import com.ai.assistance.custard.util.CustardPaths
import com.ai.assistance.custard.util.TextSegmenter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

/**
 * Repository for handling Memory data operations. It abstracts the data source (ObjectBox) from the
 * rest of the application.
 */
class MemoryRepository(private val context: Context, profileId: String) {

    companion object {
        /** Represents a strong link, e.g., "A is a B". */
        const val STRONG_LINK = 1.0f

        /** Represents a medium-strength link, e.g., "A is related to B". */
        const val MEDIUM_LINK = 0.7f

        /** Represents a weak link, e.g., "A is sometimes associated with B". */
        const val WEAK_LINK = 0.3f
        private const val DANGLING_LINK_CLEANUP_INTERVAL_MS = 30_000L

        fun normalizeFolderPath(folderPath: String?): String? {
            val raw = folderPath?.trim() ?: return null
            if (raw.isBlank()) return null

            val parts = raw.replace('\\', '/')
                .split('/')
                .map { it.trim() }
                .filter { it.isNotBlank() }

            return parts.takeIf { it.isNotEmpty() }?.joinToString("/")
        }
    }

    private val store = ObjectBoxManager.get(context, profileId)
    private val memoryBox: Box<Memory> = store.boxFor()
    private val tagBox = store.boxFor<MemoryTag>()
    private val linkBox = store.boxFor<MemoryLink>()
    private val chunkBox = store.boxFor<DocumentChunk>()

    private val searchSettingsPreferences = MemorySearchSettingsPreferences(context, profileId)
    private val cloudEmbeddingService = CloudEmbeddingService()
    @Volatile
    private var lastDanglingCleanupAtMs: Long = 0L

    private suspend fun generateEmbedding(text: String, config: CloudEmbeddingConfig): Embedding? {
        return cloudEmbeddingService.generateEmbedding(config, text)
    }

    private fun cosineSimilarity(left: Embedding, right: Embedding): Float {
        val leftVector = left.vector
        val rightVector = right.vector

        if (leftVector.isEmpty() || rightVector.isEmpty() || leftVector.size != rightVector.size) {
            return 0f
        }

        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0

        for (index in leftVector.indices) {
            val leftValue = leftVector[index].toDouble()
            val rightValue = rightVector[index].toDouble()
            dot += leftValue * rightValue
            leftNorm += leftValue * leftValue
            rightNorm += rightValue * rightValue
        }

        if (leftNorm <= 0.0 || rightNorm <= 0.0) {
            return 0f
        }

        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat()
    }

    /**
     * 对关键词做“切碎扩展”：
     * - 保留原词
     * - 使用 Jieba 分词补充分片
     *
     * 目的：在未开启语义检索时，提高中文长句与标题之间的匹配召回。
     */
    private fun expandKeywordToken(token: String): Set<String> {
        val normalized = token.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return emptySet()

        val expanded = linkedSetOf<String>()
        if (shouldKeepRawLexicalToken(normalized)) {
            expanded.add(normalized)
        }

        // 优先使用项目内已集成的 Jieba 分词，提升中文检索召回质量。
        val jiebaTokens = TextSegmenter.segment(normalized)
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { shouldKeepRawLexicalToken(it) }
        expanded.addAll(jiebaTokens)

        return expanded.filterTo(linkedSetOf()) { shouldKeepRawLexicalToken(it) }
    }

    private data class TitleMatchCandidate(
        val memory: Memory,
        val matchedTokenCount: Int
    )

    private data class SearchScoreParts(
        var matchedKeywordTokenCount: Int = 0,
        var keywordScore: Double = 0.0,
        var reverseContainmentScore: Double = 0.0,
        var semanticScore: Double = 0.0,
        var edgeScore: Double = 0.0
    )

    private data class SearchComputationResult(
        val memories: List<Memory>,
        val debug: MemorySearchDebugInfo
    )

    private fun buildLexicalQueryTokens(query: String, keywords: List<String>): List<String> {
        val merged = linkedSetOf<String>()
        if (keywords.isNotEmpty()) {
            keywords.forEach { merged.addAll(expandKeywordToken(it)) }
        } else {
            merged.addAll(expandKeywordToken(query))
        }
        return merged
            .filter { shouldKeepRawLexicalToken(it) }
            .distinct()
            .sortedByDescending { it.length }
            .take(32)
    }

    private fun shouldKeepRawLexicalToken(token: String): Boolean {
        val t = token.trim()
        if (t.length !in 2..24) return false
        if (t.startsWith("http", ignoreCase = true)) return false
        if (t.contains('<') || t.contains('>')) return false
        if (t.contains("tool", ignoreCase = true)) return false
        if (t.contains("param", ignoreCase = true)) return false
        if (t.contains("visit", ignoreCase = true)) return false
        if (t.contains("name=", ignoreCase = true)) return false
        return t.any { ch -> ch.isLetterOrDigit() || ch.code in 0x4E00..0x9FFF }
    }

    private fun queryTitleCandidatesByFragments(
        fragments: List<String>,
        scopedMemoryIds: Set<Long>
    ): List<TitleMatchCandidate> {
        if (fragments.isEmpty() || scopedMemoryIds.isEmpty()) return emptyList()

        val memoryById = mutableMapOf<Long, Memory>()
        val hitTokensByMemoryId = mutableMapOf<Long, MutableSet<String>>()

        fragments.forEach { fragment ->
            val matches = memoryBox.query()
                .contains(Memory_.title, fragment, QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .build()
                .find()

            matches.forEach { memory ->
                if (!scopedMemoryIds.contains(memory.id)) return@forEach
                memoryById.putIfAbsent(memory.id, memory)
                hitTokensByMemoryId.getOrPut(memory.id) { linkedSetOf() }.add(fragment)
            }
        }

        return memoryById.values
            .map { memory ->
                TitleMatchCandidate(
                    memory = memory,
                    matchedTokenCount = hitTokensByMemoryId[memory.id]?.size ?: 0
                )
            }
            .sortedWith(
                compareByDescending<TitleMatchCandidate> { it.matchedTokenCount }
                    .thenByDescending { it.memory.importance }
                    .thenByDescending { it.memory.updatedAt.time }
            )
    }
    
    /**
     * 从外部文档创建记忆。
     * @param title 文档记忆的标题。
     * @param filePath 文档的路径。
     * @param fileContent 文档的文本内容。
     * @param folderPath 文件夹路径。
     * @return 创建的Memory对象。
     */
    suspend fun createMemoryFromDocument(documentName: String, originalPath: String, text: String, folderPath: String = ""): Memory = withContext(Dispatchers.IO) {
        val cloudConfig = searchSettingsPreferences.loadCloudEmbedding()
        val documentEmbedding = generateEmbedding(documentName, cloudConfig)

        val documentMemory = Memory(
            title = documentName,
            content = context.getString(R.string.memory_repository_document_node_content, documentName),
            uuid = UUID.randomUUID().toString()
        ).apply {
            this.embedding = documentEmbedding
            this.isDocumentNode = true
            this.documentPath = originalPath
            this.chunkIndexFilePath = null
            this.folderPath = normalizeFolderPath(folderPath)
        }
        memoryBox.put(documentMemory)

        val chunks = text.split(Regex("(\\r?\\n[\\t ]*){2,}"))
            .mapNotNull { chunkText ->
                val cleanedText = chunkText.replace(Regex("(?m)^[\\*\\-=_]{3,}\\s*$"), "").trim()
                if (cleanedText.isNotBlank()) {
                    DocumentChunk(content = cleanedText, chunkIndex = 0)
                } else {
                    null
                }
            }.mapIndexed { index, chunk ->
                chunk.apply { this.chunkIndex = index }
            }

        if (chunks.isNotEmpty()) {
            chunks.forEach { it.memory.target = documentMemory }
            chunkBox.put(chunks)

            val embeddings = chunks.map { generateEmbedding(it.content, cloudConfig) }

            chunks.forEachIndexed { index, chunk ->
                if (index < embeddings.size) {
                    val embedding = embeddings[index]
                    if (embedding != null) {
                        chunk.embedding = embedding
                    }
                }
            }
            chunkBox.put(chunks)
        }

        memoryBox.put(documentMemory)
        documentMemory
    }

    /**
     * 生成带有元数据（可信度、重要性）的文本，用于embedding。
     */
    private fun generateTextForEmbedding(memory: Memory): String {
        return memory.content
    }

    // --- Memory CRUD Operations ---

    /**
     * Creates or updates a memory, automatically generating its embedding.
     * @param memory The memory object to be saved.
     * @return The ID of the saved memory.
     */
    suspend fun saveMemory(memory: Memory): Long = withContext(Dispatchers.IO){
        val cloudConfig = searchSettingsPreferences.loadCloudEmbedding()
        memory.folderPath = normalizeFolderPath(memory.folderPath)
        if (memory.content.isNotBlank()) {
            val textForEmbedding = generateTextForEmbedding(memory)
            memory.embedding = generateEmbedding(textForEmbedding, cloudConfig)
        }
        val id = memoryBox.put(memory)
        addMemoryToIndex(memory)
        id
    }

    /**
     * Finds a memory by its ID.
     * @param id The ID of the memory to find.
     * @return The found Memory object, or null if not found.
     */
    suspend fun findMemoryById(id: Long): Memory? = withContext(Dispatchers.IO) {
        memoryBox.get(id)
    }

    /**
     * Finds a memory by its UUID.
     * @param uuid The UUID of the memory to find.
     * @return The found Memory object, or null if not found.
     */
    suspend fun findMemoryByUuid(uuid: String): Memory? = withContext(Dispatchers.IO) {
        memoryBox.query(Memory_.uuid.equal(uuid)).build().findFirst()
    }

    /**
     * Finds a memory by its exact title.
     * @param title The title of the memory to find.
     * @return The found Memory object, or null if not found.
     */
    suspend fun findMemoryByTitle(title: String): Memory? = withContext(Dispatchers.IO) {
        memoryBox.query(Memory_.title.equal(title)).build().findFirst()
    }

    /**
     * Finds all memories with the exact title (case-sensitive).
     * @param title The title of the memories to find.
     * @return A list of found Memory objects.
     */
    suspend fun findMemoriesByTitle(title: String): List<Memory> = withContext(Dispatchers.IO) {
        memoryBox.query(Memory_.title.equal(title)).build().find()
    }

    /**
     * Deletes a memory and all its links. This is a critical operation and should be handled with
     * care.
     * @param memoryId The ID of the memory to delete.
     * @return True if deletion was successful, false otherwise.
     */
    suspend fun deleteMemory(memoryId: Long): Boolean = withContext(Dispatchers.IO) {
        val memory = findMemoryById(memoryId) ?: return@withContext false

        // 如果是文档节点，删除其专属的区块索引文件和所有区块
        if (memory.isDocumentNode) {
            if (memory.chunkIndexFilePath != null) {
                try {
                    val indexFile = File(memory.chunkIndexFilePath!!)
                    if (indexFile.exists()) {
                        if (indexFile.delete()) {
                            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Deleted chunk index file: ${indexFile.path}")
                        } else {
                            com.ai.assistance.custard.util.AppLogger.w("MemoryRepo", "Failed to delete chunk index file: ${indexFile.path}")
                        }
                    }
                } catch (e: Exception) {
                    com.ai.assistance.custard.util.AppLogger.e("MemoryRepo", "Error deleting chunk index file for memory ID $memoryId", e)
                }
            }
            // 删除关联的区块
            val chunkIds = memory.documentChunks.map { it.id }
            if (chunkIds.isNotEmpty()) {
                chunkBox.removeByIds(chunkIds)
                com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Deleted ${chunkIds.size} associated chunks for document.")
            }
        }

        val linkIdsToDelete = collectLinkIdsForDeletion(setOf(memoryId), includeDangling = false)
        if (linkIdsToDelete.isNotEmpty()) {
            linkBox.removeByIds(linkIdsToDelete)
            com.ai.assistance.custard.util.AppLogger.d(
                "MemoryRepo",
                "Deleted ${linkIdsToDelete.size} links while deleting memory id=$memoryId."
            )
        }
        memoryBox.remove(memory)
    }

    // --- Link CRUD Operations ---
    private fun collectLinkIdsForDeletion(
        memoryIdsToDelete: Set<Long>,
        includeDangling: Boolean = true
    ): Set<Long> {
        val existingMemoryIds =
            if (includeDangling) memoryBox.all.map { it.id }.toHashSet() else emptySet()
        return linkBox.all
            .asSequence()
            .filter { link ->
                val sourceId = link.source.targetId
                val targetId = link.target.targetId
                val linkedToDeletingMemories =
                    sourceId in memoryIdsToDelete || targetId in memoryIdsToDelete
                val danglingLink = if (includeDangling) {
                    sourceId <= 0L ||
                        targetId <= 0L ||
                        sourceId !in existingMemoryIds ||
                        targetId !in existingMemoryIds
                } else {
                    false
                }
                linkedToDeletingMemories || danglingLink
            }
            .map { it.id }
            .toSet()
    }

    private fun cleanupDanglingLinksIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDanglingCleanupAtMs < DANGLING_LINK_CLEANUP_INTERVAL_MS) {
            return
        }
        val danglingLinkIds = collectLinkIdsForDeletion(emptySet())
        if (danglingLinkIds.isNotEmpty()) {
            linkBox.removeByIds(danglingLinkIds)
            com.ai.assistance.custard.util.AppLogger.w(
                "MemoryRepo",
                "Cleaned ${danglingLinkIds.size} dangling memory links."
            )
        }
        lastDanglingCleanupAtMs = now
    }

    suspend fun findLinkById(linkId: Long): MemoryLink? = withContext(Dispatchers.IO) {
        linkBox.get(linkId)
    }

    suspend fun updateLink(linkId: Long, type: String, weight: Float, description: String): MemoryLink? = withContext(Dispatchers.IO) {
        val link = findLinkById(linkId) ?: return@withContext null
        val sourceMemory = link.source.target

        link.type = type
        link.weight = weight
        link.description = description
        linkBox.put(link)

        // 在更新link后，同样put其所属的source memory。
        // 这是为了向ObjectBox明确指出，这个父实体的关系集合“脏了”，
        // 以此来避免后续查询时拿到缓存的旧数据。
        if (sourceMemory != null) {
            memoryBox.put(sourceMemory)
        }

        link
    }

    suspend fun deleteLink(linkId: Long): Boolean = withContext(Dispatchers.IO) {
        // 为了健壮性，在删除链接后，也更新其父实体。
        val link = findLinkById(linkId)
        val sourceMemory = link?.source?.target

        val wasRemoved = linkBox.remove(linkId)

        if (wasRemoved && sourceMemory != null) {
            // 通过put源实体，我们确保它的ToMany关系缓存在其他线程或未来的查询中得到更新。
            memoryBox.put(sourceMemory)
        }
        wasRemoved
    }

    // --- Tagging Operations ---

    /**
     * Adds a tag to a memory.
     * @param memory The memory to tag.
     * @param tagName The name of the tag.
     * @return The MemoryTag object.
     */
    suspend fun addTagToMemory(memory: Memory, tagName: String): MemoryTag = withContext(Dispatchers.IO) {
        // Find existing tag or create a new one
        val tag =
                tagBox.query()
                        .equal(MemoryTag_.name, tagName, QueryBuilder.StringOrder.CASE_SENSITIVE)
                        .build()
                        .findFirst()
                        ?: MemoryTag(name = tagName).also { tagBox.put(it) }

        if (!memory.tags.any { it.id == tag.id }) {
            memory.tags.add(tag)
            memoryBox.put(memory)
        }
        tag
    }

    // --- Linking Operations ---

    /**
     * Creates a link between two memories.
     * @param source The source memory.
     * @param target The target memory.
     * @param type The type of the link (e.g., "causes", "explains").
     * @param weight The strength of the link, ideally between 0.0 and 1.0.
     *               It's recommended to use the predefined constants like [STRONG_LINK],
     *               [MEDIUM_LINK], or [WEAK_LINK] for consistency. The value will be
     *               automatically clamped to the [0.0, 1.0] range.
     * @param description A description of the link.
     */
    suspend fun linkMemories(
            source: Memory,
            target: Memory,
            type: String,
            weight: Float = MEDIUM_LINK,
            description: String = ""
    ) = withContext(Dispatchers.IO) {
        // 检查链接是否已存在
        val existingLink = source.links.find { link ->
            link.target.target?.id == target.id && 
            link.type == type
        }
        
        if (existingLink != null) {
            // 链接已存在，可以选择更新或直接返回
            // 这里我们选择直接返回，不创建重复链接
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Link already exists from memory ${source.id} to ${target.id} with type $type")
            return@withContext
        }
        
        // Coerce the weight to be within the valid range [0.0, 1.0] to ensure data integrity.
        val sanitizedWeight = weight.coerceIn(0.0f, 1.0f)
        val link = MemoryLink(type = type, weight = sanitizedWeight, description = description)
        link.source.target = source
        link.target.target = target

        source.links.add(link)
        memoryBox.put(source)
    }

    /** Gets all outgoing links from a memory. */
    suspend fun getOutgoingLinks(memoryId: Long): List<MemoryLink> = withContext(Dispatchers.IO) {
        val memory = findMemoryById(memoryId)
        memory?.links?.reset()
        memory?.links ?: emptyList()
    }

    /** Gets all incoming links to a memory. */
    suspend fun getIncomingLinks(memoryId: Long): List<MemoryLink> = withContext(Dispatchers.IO) {
        val memory = findMemoryById(memoryId)
        memory?.backlinks?.reset()
        memory?.backlinks ?: emptyList()
    }

    /**
     * Query memory links with optional filters.
     * If no filter is provided, returns recent links up to limit.
     */
    suspend fun queryMemoryLinks(
        linkId: Long? = null,
        sourceMemoryId: Long? = null,
        targetMemoryId: Long? = null,
        linkType: String? = null,
        limit: Int = 20
    ): List<MemoryLink> = withContext(Dispatchers.IO) {
        cleanupDanglingLinksIfNeeded()

        val normalizedType = linkType?.trim()?.takeIf { it.isNotEmpty() }
        val validLimit = limit.coerceIn(1, 200)

        val candidates = when {
            linkId != null -> listOfNotNull(linkBox.get(linkId))
            sourceMemoryId != null && targetMemoryId != null ->
                getOutgoingLinks(sourceMemoryId).filter { it.target.targetId == targetMemoryId }
            sourceMemoryId != null -> getOutgoingLinks(sourceMemoryId)
            targetMemoryId != null -> getIncomingLinks(targetMemoryId)
            else -> linkBox.all
        }

        candidates
            .asSequence()
            .filter { link ->
                val sourceId = link.source.targetId
                val targetId = link.target.targetId

                (sourceMemoryId == null || sourceId == sourceMemoryId) &&
                    (targetMemoryId == null || targetId == targetMemoryId) &&
                    (normalizedType == null || link.type == normalizedType)
            }
            .sortedByDescending { it.id }
            .take(validLimit)
            .toList()
    }

    // --- Complex Queries ---

    /**
     * Searches memories using semantic search if a query is provided, otherwise returns all
     * memories.
     * @param query The search query string. Keywords can be separated by '|' or spaces.
     * @param folderPath Optional path to a folder to limit the search.
     * @param semanticThreshold The minimum semantic similarity threshold (0.0-1.0). Lower values return more results.
     * @return A list of matching Memory objects, sorted by relevance.
     */
    suspend fun searchMemories(
        query: String,
        folderPath: String? = null,
        semanticThreshold: Float = 0.6f,
        scoreMode: MemoryScoreMode = MemoryScoreMode.BALANCED,
        keywordWeight: Float = 10.0f,
        semanticWeight: Float = 0.5f,
        edgeWeight: Float = 0.4f,
        createdAtStartMs: Long? = null,
        createdAtEndMs: Long? = null
    ): List<Memory> {
        return runSearchMemoriesWithDebug(
            query = query,
            folderPath = folderPath,
            semanticThreshold = semanticThreshold,
            scoreMode = scoreMode,
            keywordWeight = keywordWeight,
            semanticWeight = semanticWeight,
            edgeWeight = edgeWeight,
            createdAtStartMs = createdAtStartMs,
            createdAtEndMs = createdAtEndMs
        ).memories
    }

    suspend fun searchMemoriesDebug(
        query: String,
        folderPath: String? = null,
        semanticThreshold: Float = 0.6f,
        scoreMode: MemoryScoreMode = MemoryScoreMode.BALANCED,
        keywordWeight: Float = 10.0f,
        semanticWeight: Float = 0.5f,
        edgeWeight: Float = 0.4f,
        createdAtStartMs: Long? = null,
        createdAtEndMs: Long? = null
    ): MemorySearchDebugInfo {
        return runSearchMemoriesWithDebug(
            query = query,
            folderPath = folderPath,
            semanticThreshold = semanticThreshold,
            scoreMode = scoreMode,
            keywordWeight = keywordWeight,
            semanticWeight = semanticWeight,
            edgeWeight = edgeWeight,
            createdAtStartMs = createdAtStartMs,
            createdAtEndMs = createdAtEndMs
        ).debug
    }

    private suspend fun runSearchMemoriesWithDebug(
        query: String,
        folderPath: String? = null,
        semanticThreshold: Float = 0.6f,
        scoreMode: MemoryScoreMode = MemoryScoreMode.BALANCED,
        keywordWeight: Float = 10.0f,
        semanticWeight: Float = 0.5f,
        edgeWeight: Float = 0.4f,
        createdAtStartMs: Long? = null,
        createdAtEndMs: Long? = null
    ): SearchComputationResult = withContext(Dispatchers.IO) {
        val normalizedFolderPath = normalizeFolderPath(folderPath)

        val memoriesInScope = if (normalizedFolderPath == null) {
            if (folderPath == context.getString(R.string.memory_uncategorized)) {
                memoryBox.all.filter { normalizeFolderPath(it.folderPath) == null }
            } else {
                memoryBox.all
            }
        } else {
            getMemoriesByFolderPath(normalizedFolderPath)
        }

        val timeFilteredMemoriesInScope = if (createdAtStartMs == null && createdAtEndMs == null) {
            memoriesInScope
        } else {
            memoriesInScope.filter { memory ->
                val createdAtMs = memory.createdAt.time
                (createdAtStartMs == null || createdAtMs >= createdAtStartMs) &&
                    (createdAtEndMs == null || createdAtMs <= createdAtEndMs)
            }
        }

        val normalizedThreshold = semanticThreshold.coerceIn(0.0f, 1.0f)
        val normalizedKeywordWeight = keywordWeight.coerceAtLeast(0.0f).toDouble()
        val normalizedSemanticWeight = semanticWeight.coerceAtLeast(0.0f)
        val normalizedEdgeWeight = edgeWeight.coerceAtLeast(0.0f).toDouble()
        val (modeKeywordMultiplier, modeSemanticMultiplier, modeEdgeMultiplier) = when (scoreMode) {
            MemoryScoreMode.BALANCED -> Triple(1.0, 1.0, 1.0)
            MemoryScoreMode.KEYWORD_FIRST -> Triple(1.3, 0.8, 0.9)
            MemoryScoreMode.SEMANTIC_FIRST -> Triple(0.8, 1.3, 1.1)
        }
        val effectiveKeywordWeight = normalizedKeywordWeight * modeKeywordMultiplier
        val effectiveSemanticWeight = normalizedSemanticWeight * modeSemanticMultiplier.toFloat()
        val effectiveEdgeWeight = normalizedEdgeWeight * modeEdgeMultiplier
        val relevanceThreshold = 0.025

        fun buildDebug(
            keywords: List<String> = emptyList(),
            lexicalTokens: List<String> = emptyList(),
            semanticKeywordNormFactor: Double = if (keywords.isNotEmpty()) {
                1.0 / sqrt(keywords.size.toDouble())
            } else {
                1.0
            },
            keywordMatchesCount: Int = 0,
            reverseContainmentMatchesCount: Int = 0,
            semanticMatchesCount: Int = 0,
            graphEdgesTraversed: Int = 0,
            scoredCount: Int = 0,
            passedThresholdCount: Int = 0,
            candidates: List<MemorySearchDebugCandidate> = emptyList(),
            finalResultIds: List<Long> = emptyList()
        ): MemorySearchDebugInfo {
            return MemorySearchDebugInfo(
                query = query,
                keywords = keywords,
                lexicalTokens = lexicalTokens,
                scoreMode = scoreMode,
                semanticThreshold = normalizedThreshold,
                relevanceThreshold = relevanceThreshold,
                effectiveKeywordWeight = effectiveKeywordWeight,
                effectiveSemanticWeight = effectiveSemanticWeight,
                semanticKeywordNormFactor = semanticKeywordNormFactor,
                effectiveEdgeWeight = effectiveEdgeWeight,
                memoriesInScopeCount = timeFilteredMemoriesInScope.size,
                keywordMatchesCount = keywordMatchesCount,
                reverseContainmentMatchesCount = reverseContainmentMatchesCount,
                semanticMatchesCount = semanticMatchesCount,
                graphEdgesTraversed = graphEdgesTraversed,
                scoredCount = scoredCount,
                passedThresholdCount = passedThresholdCount,
                candidates = candidates,
                finalResultIds = finalResultIds
            )
        }

        // 支持通配符搜索：如果查询是 "*"，返回所有记忆（在文件夹过滤后）
        if (query.trim() == "*") {
            return@withContext SearchComputationResult(
                memories = timeFilteredMemoriesInScope,
                debug = buildDebug(finalResultIds = timeFilteredMemoriesInScope.map { it.id })
            )
        }

        if (query.isBlank()) {
            return@withContext SearchComputationResult(
                memories = timeFilteredMemoriesInScope,
                debug = buildDebug(finalResultIds = timeFilteredMemoriesInScope.map { it.id })
            )
        }

        // 支持两种分隔符：'|' 或空格
        val keywords = if (query.contains('|')) {
            query.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            query.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (keywords.isEmpty()) {
            return@withContext SearchComputationResult(
                memories = emptyList(),
                debug = buildDebug(keywords = emptyList())
            )
        }
        val keywordTokensForLexicalMatch = buildLexicalQueryTokens(query, keywords)

        val scores = mutableMapOf<Long, Double>()
        val scorePartsByMemoryId = mutableMapOf<Long, SearchScoreParts>()
        fun getScoreParts(memoryId: Long): SearchScoreParts {
            return scorePartsByMemoryId.getOrPut(memoryId) { SearchScoreParts() }
        }
        val k = 60.0 // RRF constant for result fusion

        val semanticKeywordNormFactor =
            if (keywords.isNotEmpty()) 1.0 / sqrt(keywords.size.toDouble()) else 1.0
        com.ai.assistance.custard.util.AppLogger.d(
            "MemoryRepo",
            "search settings => mode=$scoreMode, threshold=${String.format("%.2f", normalizedThreshold)}, " +
                "keyword=${String.format("%.2f", effectiveKeywordWeight)}, semantic=${String.format("%.2f", effectiveSemanticWeight)}, " +
                "semanticNorm=${String.format("%.4f", semanticKeywordNormFactor)}, edge=${String.format("%.2f", effectiveEdgeWeight)}"
        )
        com.ai.assistance.custard.util.AppLogger.d(
            "MemoryRepo",
            "keyword fragments: raw=${keywords.size}, lexical=${keywordTokensForLexicalMatch.size}"
        )
        if (keywordTokensForLexicalMatch.isNotEmpty()) {
            com.ai.assistance.custard.util.AppLogger.d(
                "MemoryRepo",
                "keyword fragments preview: ${
                    keywordTokensForLexicalMatch.take(12).joinToString(" | ")
                }"
            )
        }

        // --- PRE-FILTERING BY FOLDER ---
        // If a folder path is provided, all subsequent searches will be performed on this subset.
        // Otherwise, search all memories.
        val memoriesToSearch = timeFilteredMemoriesInScope

        if (memoriesToSearch.isEmpty()) {
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "No memories found in folder '$folderPath' to search.")
            return@withContext SearchComputationResult(
                memories = emptyList(),
                debug = buildDebug(
                    keywords = keywords,
                    lexicalTokens = keywordTokensForLexicalMatch,
                    semanticKeywordNormFactor = semanticKeywordNormFactor
                )
            )
        }


        // 1. Keyword-based search (DB title contains any fragment from the query)
        val memoriesToSearchIdSet = memoriesToSearch.map { it.id }.toHashSet()
        val keywordResults = queryTitleCandidatesByFragments(
            fragments = keywordTokensForLexicalMatch,
            scopedMemoryIds = memoriesToSearchIdSet
        )

        if (keywordResults.isNotEmpty()) {
            com.ai.assistance.custard.util.AppLogger.d(
                "MemoryRepo",
                "Keyword search (title fragments): ${keywordResults.size} matches"
            )
        }
        keywordResults.forEachIndexed { index, candidate ->
            val memory = candidate.memory
            val rank = index + 1
            val baseScore = 1.0 / (k + rank)
            val coverageRatio = if (keywordTokensForLexicalMatch.isNotEmpty()) {
                candidate.matchedTokenCount.toDouble() / keywordTokensForLexicalMatch.size.toDouble()
            } else {
                0.0
            }
            val coverageMultiplier = 1.0 + (0.6 * coverageRatio)
            val weightedScore = baseScore * memory.importance * effectiveKeywordWeight * coverageMultiplier
            scores[memory.id] = scores.getOrDefault(memory.id, 0.0) + weightedScore
            val parts = getScoreParts(memory.id)
            parts.keywordScore += weightedScore
            parts.matchedKeywordTokenCount = maxOf(parts.matchedKeywordTokenCount, candidate.matchedTokenCount)
        }

        // 2. Reverse Containment Search (Query contains Memory Title)
        // This is crucial for finding "长安大学" within the query "长安大学在西安".
        val reverseContainmentResults =
                memoriesToSearch.filter { memory -> query.contains(memory.title, ignoreCase = true) }
        
        if (reverseContainmentResults.isNotEmpty()) {
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Reverse containment: ${reverseContainmentResults.size} matches")
        }
        reverseContainmentResults.forEachIndexed { index, memory ->
            val rank = index + 1
            // Use the same RRF formula to add to the score
            val baseScore = 1.0 / (k + rank)
            val weightedScore = baseScore * memory.importance * effectiveKeywordWeight
            scores[memory.id] = scores.getOrDefault(memory.id, 0.0) + weightedScore
            getScoreParts(memory.id).reverseContainmentScore += weightedScore
        }

        // 3. Semantic search (for conceptual matches)
        val allMemoriesWithEmbedding = memoriesToSearch.filter { it.embedding != null }
        val minSimilarityThreshold = normalizedThreshold
        val cloudConfig = searchSettingsPreferences.loadCloudEmbedding()
        val semanticMatchedIds = mutableSetOf<Long>()

        if (effectiveSemanticWeight > 0.0f && cloudConfig.isReady()) {
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "--- Starting Semantic Search for ${keywords.size} keywords ---")
            keywords.forEach { keyword ->
                val queryEmbedding = generateEmbedding(keyword, cloudConfig)
                if (queryEmbedding == null) {
                    com.ai.assistance.custard.util.AppLogger.w("MemoryRepo", "Failed to generate embedding for: '$keyword'")
                    return@forEach
                }

                val semanticResultsWithScores = allMemoriesWithEmbedding
                    .mapNotNull { memory ->
                        val memoryEmbedding = memory.embedding ?: return@mapNotNull null
                        if (memoryEmbedding.vector.size != queryEmbedding.vector.size) return@mapNotNull null
                        val similarity = cosineSimilarity(queryEmbedding, memoryEmbedding)
                        if (similarity >= minSimilarityThreshold) {
                            Pair(memory, similarity)
                        } else {
                            null
                        }
                    }
                    .sortedByDescending { it.second }

                if (semanticResultsWithScores.isNotEmpty()) {
                    com.ai.assistance.custard.util.AppLogger.d(
                        "MemoryRepo",
                        "Keyword '$keyword': ${semanticResultsWithScores.size} matches (top: ${String.format("%.2f", semanticResultsWithScores.first().second)})"
                    )
                }

                semanticResultsWithScores.forEachIndexed { index, (memory, similarity) ->
                    val rank = index + 1
                    val rankScore = 1.0 / (k + rank)
                    val similarityScore = similarity * effectiveSemanticWeight
                    val weightedScore =
                        ((rankScore * sqrt(memory.importance.toDouble())) + similarityScore) *
                            semanticKeywordNormFactor
                    scores[memory.id] = scores.getOrDefault(memory.id, 0.0) + weightedScore
                    getScoreParts(memory.id).semanticScore += weightedScore
                    semanticMatchedIds.add(memory.id)
                }
            }
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "--- Semantic Search Completed ---")
        }

        // 4. Graph-based expansion: Boost scores of connected memories based on edge weights
        // Take top-scoring memories as "seed nodes" and propagate scores through edges
        val topMemoriesForExpansion = if (effectiveEdgeWeight > 0.0) {
            scores.entries.sortedByDescending { it.value }.take(10)
        } else {
            emptyList()
        }

        var edgesTraversed = 0
        val graphPropagationWeight = effectiveEdgeWeight
        val basePropagationScore = 0.03 * effectiveEdgeWeight // Give a minimum score boost for any connection

        topMemoriesForExpansion.forEach { (sourceId, _) ->
            val sourceMemory = memoriesToSearch.find { it.id == sourceId } ?: return@forEach
            val sourceScore = scores[sourceId] ?: 0.0
            
            // 重置关系缓存以获取最新连接
            sourceMemory.links.reset()
            sourceMemory.backlinks.reset()
            
            // Propagate score through outgoing links
            sourceMemory.links.forEach { link ->
                val targetMemory = link.target.target
                if (targetMemory != null) {
                    // 边权重越高，传播的分数越多
                    val propagatedScore = (sourceScore * link.weight * graphPropagationWeight) + basePropagationScore
                    scores[targetMemory.id] = scores.getOrDefault(targetMemory.id, 0.0) + propagatedScore
                    getScoreParts(targetMemory.id).edgeScore += propagatedScore
                    edgesTraversed++
                }
            }
            
            // Propagate score through incoming links (backlinks)
            sourceMemory.backlinks.forEach { link ->
                val targetMemory = link.source.target
                if (targetMemory != null) {
                    // 边权重越高，传播的分数越多
                    val propagatedScore = (sourceScore * link.weight * graphPropagationWeight) + basePropagationScore
                    scores[targetMemory.id] = scores.getOrDefault(targetMemory.id, 0.0) + propagatedScore
                    getScoreParts(targetMemory.id).edgeScore += propagatedScore
                    edgesTraversed++
                }
            }
        }
        if (edgesTraversed > 0) {
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Graph expansion: ${edgesTraversed} edges traversed")
        }

        // 5. Fuse results using RRF and return sorted list
        if (scores.isEmpty()) {
            return@withContext SearchComputationResult(
                memories = emptyList(),
                debug = buildDebug(
                    keywords = keywords,
                    lexicalTokens = keywordTokensForLexicalMatch,
                    semanticKeywordNormFactor = semanticKeywordNormFactor,
                    keywordMatchesCount = keywordResults.size,
                    reverseContainmentMatchesCount = reverseContainmentResults.size,
                    semanticMatchesCount = semanticMatchedIds.size,
                    graphEdgesTraversed = edgesTraversed
                )
            )
        }

        // 添加相关性阈值过滤，避免返回不相关的记忆
        val filteredScores = scores.entries.filter { it.value >= relevanceThreshold }

        com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Final results: ${filteredScores.size}/${scores.size} above threshold")

        // 只显示前3个结果的分数
        val sortedScoresForLogging = scores.entries.sortedByDescending { it.value }
        val scoredMemoryMap = memoryBox.get(scores.keys.toList()).filterNotNull().associateBy { it.id }
        sortedScoresForLogging.take(3).forEach { (id, score) ->
            val memory = scoredMemoryMap[id] ?: memoriesToSearch.find { it.id == id }
            if (memory != null) {
                com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "  Top: [${memory.title}] = ${String.format("%.4f", score)}")
            }
        }

        val candidateRows = sortedScoresForLogging.map { (memoryId, totalScore) ->
            val memory = scoredMemoryMap[memoryId]
            val parts = scorePartsByMemoryId[memoryId] ?: SearchScoreParts()
            MemorySearchDebugCandidate(
                memoryId = memoryId,
                title = memory?.title ?: "#$memoryId",
                folderPath = memory?.folderPath,
                matchedKeywordTokenCount = parts.matchedKeywordTokenCount,
                keywordScore = parts.keywordScore,
                reverseContainmentScore = parts.reverseContainmentScore,
                semanticScore = parts.semanticScore,
                edgeScore = parts.edgeScore,
                totalScore = totalScore,
                passedThreshold = totalScore >= relevanceThreshold
            )
        }

        val debugInfo = buildDebug(
            keywords = keywords,
            lexicalTokens = keywordTokensForLexicalMatch,
            semanticKeywordNormFactor = semanticKeywordNormFactor,
            keywordMatchesCount = keywordResults.size,
            reverseContainmentMatchesCount = reverseContainmentResults.size,
            semanticMatchesCount = semanticMatchedIds.size,
            graphEdgesTraversed = edgesTraversed,
            scoredCount = scores.size,
            passedThresholdCount = filteredScores.size,
            candidates = candidateRows,
            finalResultIds = filteredScores.sortedByDescending { it.value }.map { it.key }
        )

        if (filteredScores.isEmpty()) {
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "No memories above relevance threshold")
            return@withContext SearchComputationResult(
                memories = emptyList(),
                debug = debugInfo
            )
        }

        val sortedMemoryIds = filteredScores.sortedByDescending { it.value }.map { it.key }

        // Fetch the sorted entities from the database
        val sortedMemories = memoryBox.get(sortedMemoryIds).filterNotNull()

        // 7. Semantic Deduplication
        // deduplicateBySemantics(sortedMemories)
        SearchComputationResult(
            memories = sortedMemories,
            debug = debugInfo
        )
    }

    /**
     * 获取指定记忆的所有文档区块。
     * @param memoryId 父记忆的ID。
     * @return 该记忆关联的DocumentChunk列表。
     */
    suspend fun getChunksForMemory(memoryId: Long): List<DocumentChunk> = withContext(Dispatchers.IO) {
        val memory = findMemoryById(memoryId)
        // 从数据库关系中获取，并按原始顺序排序
        memory?.documentChunks?.sortedBy { it.chunkIndex } ?: emptyList()
    }

    /**
     * 根据索引获取单个文档区块。
     * @param memoryId 父记忆的ID。
     * @param chunkIndex 区块索引（0-based）。
     * @return 对应的DocumentChunk，如果不存在则返回null。
     */
    suspend fun getChunkByIndex(memoryId: Long, chunkIndex: Int): DocumentChunk? = withContext(Dispatchers.IO) {
        val chunks = getChunksForMemory(memoryId)
        chunks.firstOrNull { it.chunkIndex == chunkIndex }
    }

    /**
     * 获取指定范围内的文档区块。
     * @param memoryId 父记忆的ID。
     * @param startIndex 起始索引（0-based，包含）。
     * @param endIndex 结束索引（0-based，包含）。
     * @return 指定范围内的DocumentChunk列表。
     */
    suspend fun getChunksByRange(memoryId: Long, startIndex: Int, endIndex: Int): List<DocumentChunk> = withContext(Dispatchers.IO) {
        val chunks = getChunksForMemory(memoryId)
        chunks.filter { it.chunkIndex in startIndex..endIndex }
    }

    /**
     * 获取文档的总区块数。
     * @param memoryId 父记忆的ID。
     * @return 总区块数。
     */
    suspend fun getTotalChunkCount(memoryId: Long): Int = withContext(Dispatchers.IO) {
        getChunksForMemory(memoryId).size
    }

    /**
     * 在指定文档的区块内进行搜索。
     * @param memoryId 父记忆的ID。
     * @param query 搜索查询。
     * @return 匹配的DocumentChunk列表。
     */
    suspend fun searchChunksInDocument(memoryId: Long, query: String): List<DocumentChunk> = withContext(Dispatchers.IO) {
        com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "--- Starting search in document (Memory ID: $memoryId) for query: '$query' ---")
        val memory = findMemoryById(memoryId) ?: return@withContext emptyList<DocumentChunk>().also {
            com.ai.assistance.custard.util.AppLogger.w("MemoryRepo", "Document with ID $memoryId not found.")
        }
        if (!memory.isDocumentNode) {
            return@withContext emptyList()
        }

        if (query.isBlank()) {
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Query is blank, returning all chunks sorted by index.")
            return@withContext getChunksForMemory(memoryId) // 返回有序的全部区块
        }

        val keywords = query.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) {
            return@withContext getChunksForMemory(memoryId)
        }

        val allChunks = getChunksForMemory(memoryId)
        val keywordResults = allChunks
            .filter { chunk -> keywords.any { keyword -> chunk.content.contains(keyword, ignoreCase = true) } }
            .toMutableList()

        val semanticResults = mutableListOf<DocumentChunk>()
        val cloudConfig = searchSettingsPreferences.loadCloudEmbedding()
        if (cloudConfig.isReady()) {
            val queryEmbedding = generateEmbedding(query, cloudConfig)
            if (queryEmbedding != null) {
                val semanticThreshold = 0.55f
                semanticResults.addAll(
                    allChunks
                        .mapNotNull { chunk ->
                            val chunkEmbedding = chunk.embedding ?: return@mapNotNull null
                            if (chunkEmbedding.vector.size != queryEmbedding.vector.size) return@mapNotNull null
                            val similarity = cosineSimilarity(queryEmbedding, chunkEmbedding)
                            if (similarity >= semanticThreshold) {
                                Pair(chunk, similarity)
                            } else {
                                null
                            }
                        }
                        .sortedByDescending { it.second }
                        .take(20)
                        .map { it.first }
                )
            }
        }

        val combinedResults = (keywordResults + semanticResults).distinctBy { it.id }
        com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Combined and deduplicated results count: ${combinedResults.size}. Results are now ordered by relevance.")
        com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "--- Search in document finished ---")

        combinedResults
    }

    /**
     * 更新单个文档区块的内容。
     * @param chunkId 要更新的区块ID。
     * @param newContent 新的文本内容。
     */
    suspend fun updateChunk(chunkId: Long, newContent: String) = withContext(Dispatchers.IO) {
        val chunk = chunkBox.get(chunkId) ?: return@withContext
        val cloudConfig = searchSettingsPreferences.loadCloudEmbedding()

        chunk.content = newContent
        chunk.embedding = generateEmbedding(newContent, cloudConfig)
        chunkBox.put(chunk)
    }

    suspend fun addMemoryToIndex(memory: Memory) = withContext(Dispatchers.IO) {
        // No-op: HNSW is intentionally not used in the current memory retrieval path.
        memory.id
    }

    suspend fun removeMemoryFromIndex(memory: Memory) = withContext(Dispatchers.IO) {
        // No-op: HNSW is intentionally not used in the current memory retrieval path.
        memory.id
    }

    /** 基于数据库向量的精确语义检索。 */
    suspend fun searchMemoriesPrecise(query: String, similarityThreshold: Float = 0.95f): List<Memory> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val cloudConfig = searchSettingsPreferences.loadCloudEmbedding()
        if (!cloudConfig.isReady()) return@withContext emptyList()

        val queryEmbedding = generateEmbedding(query, cloudConfig) ?: return@withContext emptyList()
        memoryBox.all
            .mapNotNull { memory ->
                val memoryEmbedding = memory.embedding ?: return@mapNotNull null
                if (memoryEmbedding.vector.size != queryEmbedding.vector.size) return@mapNotNull null
                val similarity = cosineSimilarity(queryEmbedding, memoryEmbedding)
                if (similarity >= similarityThreshold) {
                    Pair(memory, similarity)
                } else {
                    null
                }
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun loadCloudEmbeddingConfig(): CloudEmbeddingConfig {
        return searchSettingsPreferences.loadCloudEmbedding()
    }

    fun saveCloudEmbeddingConfig(config: CloudEmbeddingConfig) {
        searchSettingsPreferences.saveCloudEmbedding(config.normalized())
    }

    suspend fun getEmbeddingDimensionUsage(): EmbeddingDimensionUsage = withContext(Dispatchers.IO) {
        val memories = memoryBox.all
        val chunks = chunkBox.all

        val memoryDimensions = memories
            .mapNotNull { it.embedding?.vector?.size?.takeIf { dimension -> dimension > 0 } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { DimensionCount(dimension = it.key, count = it.value) }

        val chunkDimensions = chunks
            .mapNotNull { it.embedding?.vector?.size?.takeIf { dimension -> dimension > 0 } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { DimensionCount(dimension = it.key, count = it.value) }

        EmbeddingDimensionUsage(
            memoryTotal = memories.size,
            memoryMissing = memories.count { it.embedding == null || it.embedding!!.vector.isEmpty() },
            memoryDimensions = memoryDimensions,
            chunkTotal = chunks.size,
            chunkMissing = chunks.count { it.embedding == null || it.embedding!!.vector.isEmpty() },
            chunkDimensions = chunkDimensions
        )
    }

    suspend fun rebuildEmbeddings(onProgress: (EmbeddingRebuildProgress) -> Unit) = withContext(Dispatchers.IO) {
        val cloudConfig = searchSettingsPreferences.loadCloudEmbedding()
        if (!cloudConfig.isReady()) {
            throw IOException("Cloud embedding configuration is incomplete.")
        }

        val memories = memoryBox.all
        val chunks = chunkBox.all
        val total = memories.size + chunks.size
        var processed = 0
        var failed = 0

        onProgress(
            EmbeddingRebuildProgress(
                total = total,
                processed = processed,
                failed = failed,
                currentStage = "preparing"
            )
        )

        memories.forEach { memory ->
            val textForEmbedding = if (memory.isDocumentNode) memory.title else generateTextForEmbedding(memory)
            val embedding = generateEmbedding(textForEmbedding, cloudConfig)
            if (embedding == null) {
                failed += 1
            }
            memory.embedding = embedding
            memoryBox.put(memory)
            processed += 1
            onProgress(
                EmbeddingRebuildProgress(
                    total = total,
                    processed = processed,
                    failed = failed,
                    currentStage = "memory"
                )
            )
        }

        chunks.forEach { chunk ->
            val embedding = generateEmbedding(chunk.content, cloudConfig)
            if (embedding == null) {
                failed += 1
            }
            chunk.embedding = embedding
            chunkBox.put(chunk)
            processed += 1
            onProgress(
                EmbeddingRebuildProgress(
                    total = total,
                    processed = processed,
                    failed = failed,
                    currentStage = "chunk"
                )
            )
        }

        cleanupLegacyVectorIndexFiles()
        onProgress(
            EmbeddingRebuildProgress(
                total = total,
                processed = processed,
                failed = failed,
                currentStage = "done"
            )
        )
    }

    private fun cleanupLegacyVectorIndexFiles() {
        val vectorDir = CustardPaths.vectorIndexDir(context)
        if (vectorDir.exists()) {
            vectorDir.listFiles()?.forEach { file ->
                val shouldDelete =
                    (file.name.startsWith("memory_hnsw_") && file.name.endsWith(".idx")) ||
                        (file.name.startsWith("doc_index_") && file.name.endsWith(".hnsw"))
                if (shouldDelete) {
                    file.delete()
                }
            }
        }

        val documentMemories = memoryBox.all.filter { it.chunkIndexFilePath != null }
        if (documentMemories.isNotEmpty()) {
            documentMemories.forEach { it.chunkIndexFilePath = null }
            memoryBox.put(documentMemories)
        }
    }

    /**
     * Builds a Graph object from a given list of memories. This is used to display a subset of the
     * entire memory graph, e.g., after a search.
     * @param memories The list of memories to include in the graph.
     * @return A Graph object.
     */
    suspend fun getGraphForMemories(memories: List<Memory>): Graph = withContext(Dispatchers.IO) {
        // Expand the initial list of memories to include direct neighbors
        val expandedMemories = mutableSetOf<Memory>()
        expandedMemories.addAll(memories)

        memories.forEach { memory ->
            memory.links.forEach { link -> link.target.target?.let { expandedMemories.add(it) } }
            memory.backlinks.forEach { backlink ->
                backlink.source.target?.let { expandedMemories.add(it) }
            }
        }

        com.ai.assistance.custard.util.AppLogger.d(
                "MemoryRepo",
                "Initial memories: ${memories.size}, Expanded memories: ${expandedMemories.size}"
        )
        buildGraphFromMemories(expandedMemories.toList(), null)
    }

    /** Retrieves a single memory by its UUID. */
    suspend fun getMemoryByUuid(uuid: String): Memory? =
            withContext(Dispatchers.IO) {
                memoryBox.query(Memory_.uuid.equal(uuid)).build().findUnique()
            }

    /**
     * 获取所有唯一的文件夹路径。
     * @return 所有唯一的文件夹路径列表。
     */
    suspend fun getAllFolderPaths(): List<String> = withContext(Dispatchers.IO) {
        val allMemories = memoryBox.all
        com.ai.assistance.custard.util.AppLogger.d("MemoryRepository", "getAllFolderPaths: Total memories: ${allMemories.size}")
        val folderPaths = allMemories
            .map { normalizeFolderPath(it.folderPath) ?: context.getString(R.string.memory_uncategorized) }
            .distinct()
            .sorted()
        com.ai.assistance.custard.util.AppLogger.d("MemoryRepository", "getAllFolderPaths: Unique folders: $folderPaths")
        folderPaths
    }

    /**
     * 按文件夹路径获取记忆（包括所有子文件夹）。
     * @param folderPath 文件夹路径。
     * @return 该文件夹及其所有子文件夹下的记忆列表。
     */
    suspend fun getMemoriesByFolderPath(folderPath: String): List<Memory> = withContext(Dispatchers.IO) {
        val normalizedTarget = normalizeFolderPath(folderPath)
        if (folderPath == context.getString(R.string.memory_uncategorized) || normalizedTarget == null) {
            memoryBox.all.filter { normalizeFolderPath(it.folderPath) == null }
        } else {
            memoryBox.all.filter { memory ->
                val path = normalizeFolderPath(memory.folderPath) ?: return@filter false
                path == normalizedTarget || path.startsWith("$normalizedTarget/")
            }
        }
    }

    /**
     * 获取指定文件夹的图谱（包括跨文件夹的边）。
     * @param folderPath 文件夹路径。
     * @return 该文件夹的图谱对象。
     */
    suspend fun getGraphForFolder(folderPath: String): Graph = withContext(Dispatchers.IO) {
        val memories = getMemoriesByFolderPath(folderPath)
        buildGraphFromMemories(memories, folderPath)
    }

    /**
     * 重命名文件夹（更新该文件夹下所有记忆的 folderPath）。
     * @param oldPath 旧的文件夹路径。
     * @param newPath 新的文件夹路径。
     * @return 是否成功。
     */
    suspend fun renameFolder(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedOldPath = normalizeFolderPath(oldPath) ?: return@withContext false
        val normalizedNewPath = normalizeFolderPath(newPath) ?: return@withContext false
        if (normalizedOldPath == normalizedNewPath) return@withContext true
        
        try {
            // 获取该文件夹及其所有子文件夹下的记忆
            val memories = memoryBox.all.filter { memory ->
                val path = normalizeFolderPath(memory.folderPath) ?: return@filter false
                path == normalizedOldPath || path.startsWith("$normalizedOldPath/")
            }
            
            // 批量更新路径
            memories.forEach { memory ->
                val currentPath = normalizeFolderPath(memory.folderPath) ?: return@forEach
                memory.folderPath = if (currentPath == normalizedOldPath) {
                    normalizedNewPath
                } else {
                    normalizedNewPath + currentPath.removePrefix(normalizedOldPath)
                }
            }
            
            memoryBox.put(memories)
            true
        } catch (e: Exception) {
            com.ai.assistance.custard.util.AppLogger.e("MemoryRepo", "Failed to rename folder", e)
            false
        }
    }

    /**
     * 移动记忆到新文件夹。
     * @param memoryIds 要移动的记忆ID列表。
     * @param targetFolderPath 目标文件夹路径。
     * @return 是否成功。
     */
    suspend fun moveMemoriesToFolder(memoryIds: List<Long>, targetFolderPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalizedTarget = if (targetFolderPath == context.getString(R.string.memory_uncategorized)) {
                null
            } else {
                normalizeFolderPath(targetFolderPath)
            }
            val memories = memoryIds.mapNotNull { findMemoryById(it) }
            memories.forEach { it.folderPath = normalizedTarget }
            memoryBox.put(memories)
            true
        } catch (e: Exception) {
            com.ai.assistance.custard.util.AppLogger.e("MemoryRepo", "Failed to move memories", e)
            false
        }
    }

    /**
     * 创建新文件夹（实际上是通过在该路径下创建一个占位记忆来实现）。
     * @param folderPath 新文件夹的路径。
     * @return 是否成功。
     */
    suspend fun createFolder(folderPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalizedFolderPath = normalizeFolderPath(folderPath) ?: return@withContext false
            // 检查是否已存在该文件夹
            val exists = memoryBox.all.any { normalizeFolderPath(it.folderPath) == normalizedFolderPath }
            if (exists) return@withContext true
            
            // 创建一个占位记忆
            val placeholder = Memory(
                title = context.getString(R.string.memory_repository_folder_description_title),
                content = context.getString(R.string.memory_repository_folder_description_content, normalizedFolderPath),
                uuid = UUID.randomUUID().toString(),
                folderPath = normalizedFolderPath
            )
            val cloudConfig = searchSettingsPreferences.loadCloudEmbedding()
            val embedding = generateEmbedding(placeholder.content, cloudConfig)
            if (embedding != null) placeholder.embedding = embedding
            memoryBox.put(placeholder)
            true
        } catch (e: Exception) {
            com.ai.assistance.custard.util.AppLogger.e("MemoryRepo", "Failed to create folder", e)
            false
        }
            }

    /**
     * 创建新记忆并自动生成embedding，保存到数据库并同步索引。
     */
    suspend fun createMemory(title: String, content: String, contentType: String = "text/plain", source: String = "user_input", folderPath: String = ""): Memory? = withContext(Dispatchers.IO) {
        val memory = Memory(
            title = title,
            content = content,
            contentType = contentType,
            source = source,
            folderPath = normalizeFolderPath(folderPath)
        )
        saveMemory(memory)
        addMemoryToIndex(memory)
        memory
    }

    /**
     * 更新已有记忆内容（title/content等），自动更新embedding和索引。
     */
    suspend fun updateMemory(
        memory: Memory,
        newTitle: String,
        newContent: String,
        newContentType: String = memory.contentType,
        newSource: String = memory.source,
        newCredibility: Float = memory.credibility,
        newImportance: Float = memory.importance,
        newFolderPath: String? = memory.folderPath,
        newTags: List<String>? = null // 可选的要更新的标签列表
    ): Memory? = withContext(Dispatchers.IO) {
        val cloudConfig = searchSettingsPreferences.loadCloudEmbedding()
        val contentChanged = memory.content != newContent
        val credibilityChanged = memory.credibility != newCredibility
        val importanceChanged = memory.importance != newImportance

        val needsReEmbedding = contentChanged || credibilityChanged || importanceChanged

        // 更新记忆属性
        memory.apply {
            title = newTitle
            content = newContent
            contentType = newContentType
            source = newSource
            credibility = newCredibility
            importance = newImportance
            folderPath = normalizeFolderPath(newFolderPath)
        }

        val newEmbedding = if (needsReEmbedding) {
            val textForEmbedding = generateTextForEmbedding(memory)
            generateEmbedding(textForEmbedding, cloudConfig)
        } else {
            memory.embedding
        }
        memory.embedding = newEmbedding

        // 更新标签
        if (newTags != null) {
            memory.tags.clear() // 清除旧标签
            newTags.forEach { tagName ->
                // Find existing tag or create a new one
                val tag = tagBox.query(MemoryTag_.name.equal(tagName, QueryBuilder.StringOrder.CASE_SENSITIVE))
                    .build().findFirst() ?: MemoryTag(name = tagName).also { tagBox.put(it) }
                memory.tags.add(tag)
            }
        }

        // 更新记忆属性
        memory.apply {
            this.updatedAt = java.util.Date()
        }

        // 这里不再需要调用 saveMemory，因为 memory 对象已经被修改，
        // 最后的 memoryBox.put(memory) 会保存所有更改。
        memoryBox.put(memory)

        if (needsReEmbedding) {
            addMemoryToIndex(memory)
        }
        memory
    }

    /**
     * Merges multiple source memories into a single new memory, redirecting all links.
     */
    suspend fun mergeMemories(
        sourceTitles: List<String>,
        newTitle: String,
        newContent: String,
        newTags: List<String>,
        folderPath: String
    ): Memory? = withContext(Dispatchers.IO) {
        // Step 1: Find all unique source memories from the given titles.
        // Using a Set ensures that we handle each memory object only once, even if titles are duplicated.
        val sourceMemories = mutableSetOf<Memory>()
        for (title in sourceTitles.distinct()) {
            sourceMemories.addAll(findMemoriesByTitle(title))
        }

        // After finding all memories, check if we have enough to merge.
        if (sourceMemories.size < 2) {
            com.ai.assistance.custard.util.AppLogger.w("MemoryRepo", "Merge requires at least two unique source memories to be found. Found: ${sourceMemories.size} from titles: ${sourceTitles.joinToString()}.")
            return@withContext null
        }

        var newMemory: Memory? = null
        try {
            store.runInTx {
                // 2. Create the new merged memory (without embedding yet)
                val mergedMemory = Memory(
            title = newTitle,
            content = newContent,
                    folderPath = normalizeFolderPath(folderPath),
                    source = "merged_from_problem_library"
                )
                memoryBox.put(mergedMemory) // Save to get an ID

                // 3. Add tags to the new memory
                newTags.forEach { tagName ->
                    val tag = tagBox.query(MemoryTag_.name.equal(tagName, QueryBuilder.StringOrder.CASE_SENSITIVE))
                        .build().findFirst() ?: MemoryTag(name = tagName).also { tagBox.put(it) }
                    mergedMemory.tags.add(tag)
                }
                memoryBox.put(mergedMemory)

                // 4. Collect all unique links and redirect them
                val allLinksToProcess = mutableSetOf<MemoryLink>()
                val sourceIdsSet = sourceMemories.map { it.id }.toSet()

                sourceMemories.forEach {
                    it.links.reset()
                    it.backlinks.reset()
                    allLinksToProcess.addAll(it.links)
                    allLinksToProcess.addAll(it.backlinks)
                }

                allLinksToProcess.forEach { link ->
                    if (link.source.targetId in sourceIdsSet) {
                        link.source.target = mergedMemory
                    }
                    if (link.target.targetId in sourceIdsSet) {
                        link.target.target = mergedMemory
                    }
                }
                linkBox.put(allLinksToProcess.toList())

                // 5. Delete old source memories
                memoryBox.removeByIds(sourceIdsSet.toList())

                newMemory = mergedMemory
            }

            // After the transaction, handle non-transactional parts
            newMemory?.let { memory ->
                val cloudConfig = searchSettingsPreferences.loadCloudEmbedding()
                // Generate and save embedding for the new memory
                val textForEmbedding = generateTextForEmbedding(memory)
                memory.embedding = generateEmbedding(textForEmbedding, cloudConfig)
                memoryBox.put(memory)

                // Update vector index
                addMemoryToIndex(memory)
                for (mem in sourceMemories) {
                    removeMemoryFromIndex(mem)
                }
            }
        } catch (e: Exception) {
            com.ai.assistance.custard.util.AppLogger.e("MemoryRepo", "Error during memory merge transaction.", e)
            return@withContext null
        }

        newMemory
    }

    /**
     * 删除记忆并同步索引。
     */
    suspend fun deleteMemoryAndIndex(memoryId: Long): Boolean = withContext(Dispatchers.IO) {
        val memory = findMemoryById(memoryId) ?: return@withContext false
        removeMemoryFromIndex(memory)
        deleteMemory(memoryId)
    }

    /**
     * 根据UUID批量删除记忆及其所有关联。
     * @param uuids 要删除的记忆的UUID集合。
     * @return 如果操作成功，返回true。
     */
    suspend fun deleteMemoriesByUuids(uuids: Set<String>): Boolean = withContext(Dispatchers.IO) {
        com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Attempting to delete memories with UUIDs: $uuids")
        if (uuids.isEmpty()) {
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "UUID set is empty, nothing to delete.")
            return@withContext true
        }

        // 使用QueryBuilder动态构建OR查询
        val builder = memoryBox.query()
        // ObjectBox的QueryBuilder.equal()不支持字符串，我们必须从Property本身开始构建条件
        if (uuids.isNotEmpty()) {
            var finalCondition: QueryCondition<Memory> = Memory_.uuid.equal(uuids.first())
            uuids.drop(1).forEach { uuid ->
                finalCondition = finalCondition.or(Memory_.uuid.equal(uuid))
            }
            builder.apply(finalCondition)
        }
        val memoriesToDelete = builder.build().find()

        com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Found ${memoriesToDelete.size} memories to delete.")
        if (memoriesToDelete.isEmpty()) {
            return@withContext true
        }

        // 在一个事务中执行所有数据库写入操作
        try {
            store.runInTx {
                // 1. 收集所有相关链接和区块的ID
                val memoryIdsToDelete = memoriesToDelete.map { it.id }.toSet()
                val linkIdsToDelete =
                    collectLinkIdsForDeletion(memoryIdsToDelete, includeDangling = false)
                        .toMutableSet()
                val chunkIdsToDelete = mutableSetOf<Long>()
                for (memory in memoriesToDelete) {
                    if (memory.isDocumentNode) {
                        memory.documentChunks.reset()
                        memory.documentChunks.forEach { chunk -> chunkIdsToDelete.add(chunk.id) }
                    }
                }
                com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Found ${linkIdsToDelete.size} unique links and ${chunkIdsToDelete.size} chunks to delete.")

                // 2. 批量删除链接和区块
                if (linkIdsToDelete.isNotEmpty()) {
                    linkBox.removeByIds(linkIdsToDelete)
                    com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Bulk-deleted ${linkIdsToDelete.size} links.")
                }
                if (chunkIdsToDelete.isNotEmpty()) {
                    chunkBox.removeByIds(chunkIdsToDelete)
                    com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Bulk-deleted ${chunkIdsToDelete.size} chunks.")
                }

                // 3. 批量删除记忆本身
                memoryBox.removeByIds(memoryIdsToDelete.toList())
                com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Bulk-deleted ${memoriesToDelete.size} memories.")
            }
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Transaction completed successfully.")
        } catch (e: Exception) {
            com.ai.assistance.custard.util.AppLogger.e("MemoryRepo", "Error during bulk delete transaction.", e)
            return@withContext false
        }

        // 4. 在事务外处理向量索引和文件
        for (memory in memoriesToDelete) {
            removeMemoryFromIndex(memory)
            // 删除文档的专属索引文件
            if (memory.isDocumentNode && memory.chunkIndexFilePath != null) {
                try {
                    val indexFile = File(memory.chunkIndexFilePath!!)
                    if (indexFile.exists() && indexFile.delete()) {
                         com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Deleted chunk index file: ${indexFile.path}")
                    }
                } catch (e: Exception) {
                    com.ai.assistance.custard.util.AppLogger.e("MemoryRepo", "Error deleting chunk index file for memory UUID ${memory.uuid}", e)
                }
            }
        }
        com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Removed memories from vector index and cleaned up chunk index files.")

        return@withContext true
    }

    // --- Graph Export ---

    /** Fetches all memories and their links, and converts them into a Graph data structure. */
    suspend fun getMemoryGraph(): Graph = withContext(Dispatchers.IO) {
        cleanupDanglingLinksIfNeeded()
        buildGraphFromMemories(memoryBox.all, null)
    }

    /**
     * Private helper to construct a graph from a specific list of memories. Ensures that edges are
     * only created if both source and target nodes are in the list.
     * @param memories 要构建图谱的记忆列表
     * @param currentFolderPath 当前选中的文件夹路径（用于判断跨文件夹连接），null表示显示全部
     */
    private fun buildGraphFromMemories(memories: List<Memory>, currentFolderPath: String? = null): Graph {
        val memoryUuids = memories.map { it.uuid }.toSet()

        val nodes =
                memories.map { memory ->
                    Node(
                            id = memory.uuid,
                            label = memory.title,
                            color =
                                    if (memory.isDocumentNode) {
                                        Color(0xFF9575CD) // Purple for documents
                                    } else {
                                    when (memory.tags.firstOrNull()?.name) {
                                        "Person" -> Color(0xFF81C784) // Green
                                        "Concept" -> Color(0xFF64B5F6) // Blue
                                        else -> Color.LightGray
                                        }
                                    }
                    )
                }

        val edges = mutableListOf<Edge>()
        memories.forEach { memory ->
            // 关键：重置关系缓存，确保获取最新的连接信息
            memory.links.reset()
            memory.links.forEach { link ->
                val sourceMemory = link.source.target
                val targetMemory = link.target.target
                val sourceId = sourceMemory?.uuid
                val targetId = targetMemory?.uuid
                
                // Only add edges if both source and target are in the filtered list
                if (sourceId != null &&
                    targetId != null &&
                    sourceId in memoryUuids &&
                    targetId in memoryUuids
                ) {
                    // 检测是否为跨文件夹连接
                    // 始终检测跨文件夹连接，无论是否选择了特定文件夹
                    val isCrossFolder = if (sourceMemory != null && targetMemory != null) {
                        val sourcePath = normalizeFolderPath(sourceMemory.folderPath) ?: context.getString(R.string.memory_uncategorized)
                        val targetPath = normalizeFolderPath(targetMemory.folderPath) ?: context.getString(R.string.memory_uncategorized)
                        sourcePath != targetPath
                    } else {
                        false
                    }
                    
                    edges.add(
                        Edge(
                            id = link.id,
                            sourceId = sourceId,
                            targetId = targetId,
                            label = link.type,
                            weight = link.weight,
                            isCrossFolderLink = isCrossFolder
                        )
                    )
                } else if (sourceId != null && targetId != null) {
                    // Log discarded edges for debugging
                    // com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Discarding edge: $sourceId -> $targetId
                    // (Not in filtered list)")
                }
            }
        }
        com.ai.assistance.custard.util.AppLogger.d(
                "MemoryRepo",
                "Built graph with ${nodes.size} nodes and ${edges.distinct().size} edges."
        )
        return Graph(nodes = nodes, edges = edges.distinct())
    }

    /**
     * 删除文件夹：将所有属于 folderPath 的记忆移动到"未分类"
     */
    suspend fun deleteFolder(folderPath: String) {
        withContext(Dispatchers.IO) {
            val normalizedTarget = normalizeFolderPath(folderPath)
            val memories = if (normalizedTarget == null || folderPath == context.getString(R.string.memory_uncategorized)) {
                memoryBox.all.filter { normalizeFolderPath(it.folderPath) == null }
            } else {
                memoryBox.all.filter { normalizeFolderPath(it.folderPath) == normalizedTarget }
            }
            memories.forEach { memory ->
                memory.folderPath = null
                memoryBox.put(memory)
            }
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Deleted folder '$folderPath', moved ${memories.size} memories to uncategorized")
        }
    }

    /**
     * 导出所有记忆（不包括文档节点）为 JSON 字符串
     * @return JSON 格式的记忆库数据
     */
    suspend fun exportMemoriesToJson(): String = withContext(Dispatchers.IO) {
        // 获取所有非文档节点的记忆
        val memories = memoryBox.query(Memory_.isDocumentNode.equal(false)).build().find()
        
        // 转换为可序列化格式
        val serializableMemories = memories.map { memory ->
            // 获取标签名称
            memory.tags.reset()
            val tagNames = memory.tags.map { it.name }
            
            SerializableMemory(
                uuid = memory.uuid,
                title = memory.title,
                content = memory.content,
                contentType = memory.contentType,
                source = memory.source,
                credibility = memory.credibility,
                importance = memory.importance,
                folderPath = memory.folderPath,
                createdAt = memory.createdAt,
                updatedAt = memory.updatedAt,
                tagNames = tagNames
            )
        }
        
        // 获取所有链接关系（只包含非文档节点之间的链接）
        val memoryUuids = memories.map { it.uuid }.toSet()
        val serializableLinks = mutableListOf<SerializableLink>()
        
        memories.forEach { memory ->
            memory.links.reset()
            memory.links.forEach { link ->
                val sourceUuid = link.source.target?.uuid
                val targetUuid = link.target.target?.uuid
                
                // 只导出两端都是非文档节点的链接
                if (sourceUuid != null && targetUuid != null && 
                    sourceUuid in memoryUuids && targetUuid in memoryUuids) {
                    serializableLinks.add(
                        SerializableLink(
                            sourceUuid = sourceUuid,
                            targetUuid = targetUuid,
                            type = link.type,
                            weight = link.weight,
                            description = link.description
                        )
                    )
                }
            }
        }
        
        // 创建导出数据
        val exportData = MemoryExportData(
            memories = serializableMemories,
            links = serializableLinks.distinct(), // 去重
            exportDate = Date(),
            version = "1.0"
        )
        
        // 序列化为 JSON
        val json = Json { 
            prettyPrint = true
            ignoreUnknownKeys = true
        }
        json.encodeToString(exportData)
    }
    
    /**
     * 从 JSON 字符串导入记忆
     * @param jsonString JSON 格式的记忆库数据
     * @param strategy 导入策略（遇到重复记忆时的处理方式）
     * @return 导入结果统计
     */
    suspend fun importMemoriesFromJson(
        jsonString: String,
        strategy: ImportStrategy = ImportStrategy.SKIP
    ): MemoryImportResult = withContext(Dispatchers.IO) {
        val json = Json { 
            ignoreUnknownKeys = true
        }
        
        try {
            val exportData = json.decodeFromString<MemoryExportData>(jsonString)
            
            var newCount = 0
            var updatedCount = 0
            var skippedCount = 0
            val uuidMap = mutableMapOf<String, Memory>() // 旧UUID -> 新Memory对象
            
            // 导入记忆
            exportData.memories.forEach { serializableMemory ->
                val existingMemory = memoryBox.query(Memory_.uuid.equal(serializableMemory.uuid))
                    .build().findFirst()
                
                when {
                    existingMemory != null && strategy == ImportStrategy.SKIP -> {
                        skippedCount++
                        uuidMap[serializableMemory.uuid] = existingMemory
                    }
                    
                    existingMemory != null && strategy == ImportStrategy.UPDATE -> {
                        // 更新现有记忆
                        existingMemory.apply {
                            title = serializableMemory.title
                            content = serializableMemory.content
                            contentType = serializableMemory.contentType
                            source = serializableMemory.source
                            credibility = serializableMemory.credibility
                            importance = serializableMemory.importance
                            folderPath = normalizeFolderPath(serializableMemory.folderPath)
                            updatedAt = Date()
                        }
                        memoryBox.put(existingMemory)
                        updatedCount++
                        uuidMap[serializableMemory.uuid] = existingMemory
                        
                        // 更新标签
                        updateMemoryTags(existingMemory, serializableMemory.tagNames)
                    }
                    
                    else -> {
                        // 创建新记忆
                        val newMemory = createMemoryFromSerializable(
                            serializableMemory,
                            strategy == ImportStrategy.CREATE_NEW
                        )
                        newCount++
                        uuidMap[serializableMemory.uuid] = newMemory
                    }
                }
            }
            
            // 导入链接关系
            var newLinksCount = 0
            exportData.links.forEach { serializableLink ->
                val sourceMemory = uuidMap[serializableLink.sourceUuid]
                val targetMemory = uuidMap[serializableLink.targetUuid]
                
                if (sourceMemory != null && targetMemory != null) {
                    // 检查链接是否已存在 - 查询所有链接并手动过滤
                    val existingLink = sourceMemory.links.find { link ->
                        link.target.target?.id == targetMemory.id && 
                        link.type == serializableLink.type
                    }
                    
                    if (existingLink == null) {
                        val newLink = MemoryLink(
                            type = serializableLink.type,
                            weight = serializableLink.weight,
                            description = serializableLink.description
                        )
                        newLink.source.target = sourceMemory
                        newLink.target.target = targetMemory
                        // 将链接添加到源记忆的 links 集合中，并保存源记忆
                        // 这与 linkMemories 方法保持一致
                        sourceMemory.links.add(newLink)
                        memoryBox.put(sourceMemory)
                        newLinksCount++
                    }
                }
            }
            
            com.ai.assistance.custard.util.AppLogger.d("MemoryRepo", "Import completed: $newCount new, $updatedCount updated, $skippedCount skipped, $newLinksCount links")
            
            MemoryImportResult(
                newMemories = newCount,
                updatedMemories = updatedCount,
                skippedMemories = skippedCount,
                newLinks = newLinksCount
            )
            
        } catch (e: Exception) {
            com.ai.assistance.custard.util.AppLogger.e("MemoryRepo", "Failed to import memories", e)
            throw e
        }
    }
    
    /**
     * 从可序列化的记忆数据创建 Memory 对象
     * @param serializable 可序列化的记忆数据
     * @param forceNewUuid 是否强制生成新的 UUID
     * @return 创建的 Memory 对象
     */
    private fun createMemoryFromSerializable(
        serializable: SerializableMemory,
        forceNewUuid: Boolean
    ): Memory {
        val memory = Memory(
            uuid = if (forceNewUuid) UUID.randomUUID().toString() else serializable.uuid,
            title = serializable.title,
            content = serializable.content,
            contentType = serializable.contentType,
            source = serializable.source,
            credibility = serializable.credibility,
            importance = serializable.importance,
            folderPath = normalizeFolderPath(serializable.folderPath),
            createdAt = serializable.createdAt,
            updatedAt = serializable.updatedAt
        )
        
        memoryBox.put(memory)
        
        // 添加标签
        updateMemoryTags(memory, serializable.tagNames)
        
        return memory
    }
    
    /**
     * 更新记忆的标签
     * @param memory 要更新的记忆
     * @param tagNames 标签名称列表
     */
    private fun updateMemoryTags(memory: Memory, tagNames: List<String>) {
        memory.tags.clear()
        
        tagNames.forEach { tagName ->
            val tag = tagBox.query(MemoryTag_.name.equal(tagName)).build().findFirst()
                ?: MemoryTag(name = tagName).also { tagBox.put(it) }
            memory.tags.add(tag)
        }
        
        memoryBox.put(memory)
    }

}
