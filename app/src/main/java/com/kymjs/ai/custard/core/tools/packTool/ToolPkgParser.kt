package com.kymjs.ai.custard.core.tools.packTool

import android.content.Context
import com.kymjs.ai.custard.core.tools.LocalizedText
import com.kymjs.ai.custard.core.tools.ToolPackage
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.hjson.JsonValue
import org.json.JSONObject

internal enum class ToolPkgSourceType {
    ASSET,
    EXTERNAL
}

internal data class ToolPkgResourceRuntime(
    val key: String,
    val path: String,
    val mime: String
)

internal data class ToolPkgUiModuleRuntime(
    val id: String,
    val runtime: String,
    val entry: String,
    val title: LocalizedText,
    val showInPackageManager: Boolean
)

internal data class ToolPkgSubpackageRuntime(
    val packageName: String,
    val containerPackageName: String,
    val subpackageId: String,
    val displayName: LocalizedText,
    val description: LocalizedText,
    val enabledByDefault: Boolean,
    val toolCount: Int
)

internal data class ToolPkgContainerRuntime(
    val packageName: String,
    val displayName: LocalizedText,
    val description: LocalizedText,
    val version: String,
    val sourceType: ToolPkgSourceType,
    val sourcePath: String,
    val subpackages: List<ToolPkgSubpackageRuntime>,
    val resources: List<ToolPkgResourceRuntime>,
    val uiModules: List<ToolPkgUiModuleRuntime>
)

internal data class ToolPkgLoadResult(
    val containerPackage: ToolPackage,
    val subpackagePackages: List<ToolPackage>,
    val containerRuntime: ToolPkgContainerRuntime
)

@Serializable
internal data class ToolPkgManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("toolpkg_id") val toolpkgId: String,
    val version: String = "",
    @SerialName("display_name") val displayName: LocalizedText = LocalizedText.of(""),
    val description: LocalizedText = LocalizedText.of(""),
    val subpackages: List<ToolPkgManifestSubpackage> = emptyList(),
    @SerialName("ui_modules") val uiModules: List<ToolPkgManifestUiModule> = emptyList(),
    val resources: List<ToolPkgManifestResource> = emptyList()
)

@Serializable
internal data class ToolPkgManifestSubpackage(
    val id: String,
    val entry: String
)

@Serializable
internal data class ToolPkgManifestUiModule(
    val id: String,
    val runtime: String = "",
    val entry: String = "",
    val title: LocalizedText = LocalizedText.of(""),
    @SerialName("show_in_package_manager") val showInPackageManager: Boolean = false
)

@Serializable
internal data class ToolPkgManifestResource(
    val key: String,
    val path: String,
    val mime: String = ""
)

internal object ToolPkgArchiveParser {
    fun parseToolPkgFromEntries(
        entries: Map<String, ByteArray>,
        sourceType: ToolPkgSourceType,
        sourcePath: String,
        isBuiltIn: Boolean,
        parseJsPackage: (String, (String, String) -> Unit) -> ToolPackage?,
        reportPackageLoadError: (String, String) -> Unit
    ): ToolPkgLoadResult {
        val manifestEntryName = findManifestEntry(entries)
            ?: throw IllegalArgumentException("manifest.hjson or manifest.json not found")
        val manifestBytes =
            entries[manifestEntryName]
                ?: throw IllegalArgumentException("Failed to read manifest entry")
        val manifestText = manifestBytes.toString(StandardCharsets.UTF_8)
        val manifest = parseToolPkgManifest(manifestText, manifestEntryName)

        if (manifest.toolpkgId.isBlank()) {
            throw IllegalArgumentException("manifest.toolpkg_id is required")
        }

        val subpackagePackages = mutableListOf<ToolPackage>()
        val subpackageRuntimes = mutableListOf<ToolPkgSubpackageRuntime>()

        manifest.subpackages.forEach { subpackage ->
            if (subpackage.id.isBlank()) {
                throw IllegalArgumentException("subpackage.id is required")
            }
            if (subpackage.entry.isBlank()) {
                throw IllegalArgumentException("subpackage.entry is required for '${subpackage.id}'")
            }

            val normalizedSubpackageId = subpackage.id.trim()
            val packageName = normalizedSubpackageId

            val entryBytes =
                findZipEntryContent(entries, subpackage.entry)
                    ?: throw IllegalArgumentException(
                        "Cannot find subpackage entry '${subpackage.entry}'"
                    )
            val jsContent = entryBytes.toString(StandardCharsets.UTF_8)

            val parsedPackage =
                parseJsPackage(jsContent) { _, error ->
                    reportPackageLoadError(packageName, "$sourcePath:${subpackage.entry}: $error")
                }
                    ?: throw IllegalArgumentException(
                        "Failed to parse subpackage script '${subpackage.entry}'"
                    )

            val resolvedDescription = parsedPackage.description

            val resolvedDisplayName =
                if (hasLocalizedTextContent(parsedPackage.displayName)) {
                    parsedPackage.displayName
                } else {
                    LocalizedText.of(parsedPackage.name)
                }

            val normalizedPackage =
                parsedPackage.copy(
                    name = packageName,
                    isBuiltIn = isBuiltIn
                )

            subpackagePackages.add(normalizedPackage)
            subpackageRuntimes.add(
                ToolPkgSubpackageRuntime(
                    packageName = packageName,
                    containerPackageName = manifest.toolpkgId,
                    subpackageId = normalizedSubpackageId,
                    displayName = resolvedDisplayName,
                    description = resolvedDescription,
                    enabledByDefault = normalizedPackage.enabledByDefault,
                    toolCount = normalizedPackage.tools.size
                )
            )
        }

        val resources =
            manifest.resources.map { resource ->
                if (resource.key.isBlank()) {
                    throw IllegalArgumentException("resource.key is required")
                }
                if (resource.path.isBlank()) {
                    throw IllegalArgumentException(
                        "resource.path is required for key '${resource.key}'"
                    )
                }
                val normalizedPath =
                    normalizeZipEntryPath(resource.path)
                        ?: throw IllegalArgumentException("Invalid resource path: ${resource.path}")
                if (!containsZipEntry(entries, normalizedPath)) {
                    throw IllegalArgumentException("Cannot find resource path '${resource.path}'")
                }
                ToolPkgResourceRuntime(
                    key = resource.key,
                    path = normalizedPath,
                    mime = resource.mime
                )
            }

        val uiModules =
            manifest.uiModules.map { uiModule ->
                ToolPkgUiModuleRuntime(
                    id = uiModule.id,
                    runtime = uiModule.runtime,
                    entry = uiModule.entry,
                    title = uiModule.title,
                    showInPackageManager = uiModule.showInPackageManager
                )
            }

        val containerDisplayName =
            if (hasLocalizedTextContent(manifest.displayName)) {
                manifest.displayName
            } else {
                LocalizedText.of(manifest.toolpkgId)
            }

        val containerDescription =
            when {
                hasLocalizedTextContent(manifest.description) -> manifest.description
                hasLocalizedTextContent(manifest.displayName) -> manifest.displayName
                else -> LocalizedText.of(manifest.toolpkgId)
            }

        val containerPackage =
            ToolPackage(
                name = manifest.toolpkgId,
                description = containerDescription,
                tools = emptyList(),
                isBuiltIn = isBuiltIn,
                enabledByDefault = true
            )

        val runtime =
            ToolPkgContainerRuntime(
                packageName = manifest.toolpkgId,
                displayName = containerDisplayName,
                description = containerDescription,
                version = manifest.version,
                sourceType = sourceType,
                sourcePath = sourcePath,
                subpackages = subpackageRuntimes,
                resources = resources,
                uiModules = uiModules
            )

        return ToolPkgLoadResult(
            containerPackage = containerPackage,
            subpackagePackages = subpackagePackages,
            containerRuntime = runtime
        )
    }

    fun readZipEntries(input: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                val normalizedName = normalizeZipEntryPath(entry.name)
                if (!entry.isDirectory && normalizedName != null) {
                    entries[normalizedName] = zipInput.readBytes()
                }
                zipInput.closeEntry()
            }
        }
        return entries
    }

    fun normalizeZipEntryPath(rawPath: String): String? {
        val normalized = rawPath.replace('\\', '/').trim().trimStart('/')
        if (normalized.isBlank()) {
            return null
        }
        if (normalized.contains("..")) {
            return null
        }
        return normalized
    }

    fun findZipEntryContent(entries: Map<String, ByteArray>, rawPath: String): ByteArray? {
        val normalizedPath = normalizeZipEntryPath(rawPath) ?: return null
        entries[normalizedPath]?.let { return it }
        return entries.entries.firstOrNull { it.key.equals(normalizedPath, ignoreCase = true) }?.value
    }

    fun readZipEntryBytesFromExternal(zipFilePath: String, rawEntryPath: String): ByteArray? {
        val normalizedPath = normalizeZipEntryPath(rawEntryPath) ?: return null
        val zipFile = File(zipFilePath)
        if (!zipFile.exists()) {
            return null
        }

        ZipFile(zipFile).use { archive ->
            val direct = archive.getEntry(normalizedPath)
            if (direct != null) {
                archive.getInputStream(direct).use { input ->
                    return input.readBytes()
                }
            }

            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val normalizedEntry = normalizeZipEntryPath(entry.name)
                if (!entry.isDirectory && normalizedEntry != null && normalizedEntry.equals(normalizedPath, ignoreCase = true)) {
                    archive.getInputStream(entry).use { input ->
                        return input.readBytes()
                    }
                }
            }
        }

        return null
    }

    fun readZipEntryBytesFromAsset(
        context: Context,
        assetPath: String,
        rawEntryPath: String
    ): ByteArray? {
        val normalizedPath = normalizeZipEntryPath(rawEntryPath) ?: return null
        context.assets.open(assetPath).use { input ->
            ZipInputStream(input.buffered()).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val normalizedEntry = normalizeZipEntryPath(entry.name)
                    if (!entry.isDirectory && normalizedEntry != null && normalizedEntry.equals(normalizedPath, ignoreCase = true)) {
                        return zipInput.readBytes()
                    }
                    zipInput.closeEntry()
                }
            }
        }
        return null
    }

    private fun containsZipEntry(entries: Map<String, ByteArray>, normalizedPath: String): Boolean {
        if (entries.containsKey(normalizedPath)) {
            return true
        }
        return entries.keys.any { it.equals(normalizedPath, ignoreCase = true) }
    }

    private fun findManifestEntry(entries: Map<String, ByteArray>): String? {
        val exactHjson = entries.keys.firstOrNull { it.equals("manifest.hjson", ignoreCase = true) }
        if (exactHjson != null) return exactHjson

        val exactJson = entries.keys.firstOrNull { it.equals("manifest.json", ignoreCase = true) }
        if (exactJson != null) return exactJson

        val nestedHjson =
            entries.keys.firstOrNull {
                it.substringAfterLast('/').equals("manifest.hjson", ignoreCase = true)
            }
        if (nestedHjson != null) return nestedHjson

        return entries.keys.firstOrNull {
            it.substringAfterLast('/').equals("manifest.json", ignoreCase = true)
        }
    }

    private fun parseToolPkgManifest(content: String, manifestEntryName: String): ToolPkgManifest {
        val manifestJson =
            if (manifestEntryName.endsWith(".hjson", ignoreCase = true)) {
                JsonValue.readHjson(content).toString()
            } else {
                content
            }

        val jsonConfig = Json { ignoreUnknownKeys = true }
        return jsonConfig.decodeFromString<ToolPkgManifest>(manifestJson)
    }

    private fun hasLocalizedTextContent(text: LocalizedText?): Boolean {
        return text?.values?.values?.any { it.isNotBlank() } == true
    }
}

internal object ToolPkgUiScriptParser {
    fun extractUiSpecJson(
        scriptText: String,
        startMarker: String,
        endMarker: String
    ): JSONObject? {
        val markerRegex =
            Regex(
                pattern = "/\\*\\s*$startMarker\\s*(\\{[\\s\\S]*?\\})\\s*$endMarker\\s*\\*/",
                options = setOf(RegexOption.IGNORE_CASE)
            )

        val payload = markerRegex.find(scriptText)?.groupValues?.getOrNull(1) ?: return null
        return try {
            JSONObject(payload)
        } catch (_: Exception) {
            null
        }
    }

    fun readStringList(json: JSONObject?, key: String): List<String> {
        if (json == null) {
            return emptyList()
        }
        val arr = json.optJSONArray(key) ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i).trim()
            if (value.isNotBlank()) {
                result.add(value)
            }
        }
        return result
    }

    fun readString(json: JSONObject?, key: String, defaultValue: String): String {
        val value = json?.optString(key)?.trim().orEmpty()
        return if (value.isBlank()) defaultValue else value
    }
}
