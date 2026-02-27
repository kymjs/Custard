package com.ai.assistance.custard.core.tools.packTool

import android.content.Context
import com.ai.assistance.custard.util.AppLogger
import com.ai.assistance.custard.core.tools.AIToolHandler
import com.ai.assistance.custard.core.tools.StringResultData
import com.ai.assistance.custard.core.tools.PackageToolExecutor
import com.ai.assistance.custard.core.tools.PackageTool
import com.ai.assistance.custard.core.tools.ToolPackage
import com.ai.assistance.custard.core.tools.ToolPackageState
import com.ai.assistance.custard.core.tools.agent.ShowerController
import com.ai.assistance.custard.core.tools.condition.ConditionEvaluator
import com.ai.assistance.custard.core.tools.javascript.JsEngine
import com.ai.assistance.custard.core.tools.mcp.MCPManager
import com.ai.assistance.custard.core.tools.mcp.MCPPackage
import com.ai.assistance.custard.core.tools.mcp.MCPServerConfig
import com.ai.assistance.custard.core.tools.mcp.MCPToolExecutor
import com.ai.assistance.custard.core.tools.skill.SkillManager
import com.ai.assistance.custard.data.preferences.SkillVisibilityPreferences
import com.ai.assistance.custard.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.custard.core.tools.system.ShizukuAuthorizer
import com.ai.assistance.custard.data.preferences.DisplayPreferencesManager
import com.ai.assistance.custard.data.preferences.EnvPreferences
import com.ai.assistance.custard.data.preferences.androidPermissionPreferences
import com.ai.assistance.custard.data.model.PackageToolPromptCategory
import com.ai.assistance.custard.data.model.ToolPrompt
import com.ai.assistance.custard.data.model.ToolResult
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.hjson.JsonValue

/**
 * Manages the loading, registration, and handling of tool packages
 *
 * Package Lifecycle:
 * 1. Available Packages: All packages in assets (both JS and HJSON format)
 * 2. Imported Packages: Packages that user has imported (but not necessarily using)
 * 3. Used Packages: Packages that are loaded and registered with AI in current session
 */
class PackageManager
private constructor(private val context: Context, private val aiToolHandler: AIToolHandler) {
    companion object {
        private const val TAG = "PackageManager"
        private const val PACKAGES_DIR = "packages" // Directory for packages
        private const val ASSETS_PACKAGES_DIR = "packages" // Directory in assets for packages
        private const val PACKAGE_PREFS = "com.ai.assistance.custard.core.tools.PackageManager"
        private const val IMPORTED_PACKAGES_KEY = "imported_packages"
        private const val DISABLED_PACKAGES_KEY = "disabled_packages"
        private const val ACTIVE_PACKAGES_KEY = "active_packages"
        private const val TOOLPKG_SUBPACKAGE_STATES_KEY = "toolpkg_subpackage_states"
        private const val TOOLPKG_EXTENSION = ".toolpkg"

        @Volatile
        private var INSTANCE: PackageManager? = null

        fun getInstance(context: Context, aiToolHandler: AIToolHandler): PackageManager {
            return INSTANCE
                ?: synchronized(this) {
                    INSTANCE
                        ?: PackageManager(context.applicationContext, aiToolHandler).also {
                            INSTANCE = it
                        }
                }
        }
    }

    // Map of package name to package description (all available packages in market)
    private val availablePackages = mutableMapOf<String, ToolPackage>()

    private val packageLoadErrors = ConcurrentHashMap<String, String>()

    private val activePackageToolNames = mutableMapOf<String, Set<String>>()

    private val activePackageStateIds = ConcurrentHashMap<String, String?>()

    private val toolPkgContainers = mutableMapOf<String, ToolPkgContainerRuntime>()
    private val toolPkgSubpackageByPackageName = mutableMapOf<String, ToolPkgSubpackageRuntime>()

    data class ToolPkgSubpackageInfo(
        val packageName: String,
        val subpackageId: String,
        val displayName: String,
        val description: String,
        val enabledByDefault: Boolean,
        val toolCount: Int,
        val enabled: Boolean
    )

    data class ToolPkgContainerDetails(
        val packageName: String,
        val displayName: String,
        val description: String,
        val version: String,
        val resourceCount: Int,
        val uiModuleCount: Int,
        val subpackages: List<ToolPkgSubpackageInfo>
    )

    data class ToolPkgToolboxUiModule(
        val containerPackageName: String,
        val toolPkgId: String,
        val uiModuleId: String,
        val runtime: String,
        val entry: String,
        val title: String,
        val description: String,
        val moduleSpec: Map<String, Any?>
    )


    @Volatile
    private var isInitialized = false
    private val initLock = Any()

    private val skillManager by lazy { SkillManager.getInstance(context) }

    private val skillVisibilityPreferences by lazy { SkillVisibilityPreferences.getInstance(context) }

    // JavaScript engine for executing JS package code
    private val jsEngine by lazy { JsEngine(context) }

    // Environment preferences for package-level env variables
    private val envPreferences by lazy { EnvPreferences.getInstance(context) }

    // MCP Manager instance (lazy loading)
    private val mcpManager by lazy { MCPManager.getInstance(context) }

    // Get the external packages directory
    private val externalPackagesDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), PACKAGES_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            AppLogger.d(TAG, "External packages directory: ${dir.absolutePath}")
            return dir
        }

    private fun ensureInitialized() {
        if (isInitialized) return
        synchronized(initLock) {
            if (isInitialized) return
            // Create packages directory if it doesn't exist
            externalPackagesDir // This will create the directory if it doesn't exist

            // Load available packages info (metadata only) from assets and external storage
            loadAvailablePackages()

            // Automatically import built-in packages that are enabled by default
            initializeDefaultPackages()

            isInitialized = true
        }
    }

    private fun resolveToolPkgSubpackageRuntime(nameOrId: String): ToolPkgSubpackageRuntime? {
        val candidate = nameOrId.trim()
        if (candidate.isBlank()) {
            return null
        }

        toolPkgSubpackageByPackageName[candidate]?.let { return it }

        return toolPkgSubpackageByPackageName.values.firstOrNull {
            it.subpackageId.equals(candidate, ignoreCase = true)
        }
    }

    private fun normalizePackageName(packageName: String): String {
        val trimmed = packageName.trim()
        if (trimmed.isBlank()) {
            return trimmed
        }
        return resolveToolPkgSubpackageRuntime(trimmed)?.packageName ?: trimmed
    }

    private fun normalizeImportedPackageNames(packageNames: List<String>): List<String> {
        val normalized = LinkedHashSet<String>()
        packageNames.forEach { original ->
            val canonical = normalizePackageName(original)
            if (canonical.isNotBlank()) {
                normalized.add(canonical)
            }
        }
        return normalized.toList()
    }

    private fun normalizeToolPkgSubpackageStates(states: Map<String, Boolean>): Map<String, Boolean> {
        val normalized = linkedMapOf<String, Boolean>()
        states.forEach { (name, enabled) ->
            val canonical = normalizePackageName(name)
            if (!toolPkgSubpackageByPackageName.containsKey(canonical)) {
                return@forEach
            }

            val isCanonicalKey = name.trim().equals(canonical, ignoreCase = true)
            if (isCanonicalKey || !normalized.containsKey(canonical)) {
                normalized[canonical] = enabled
            }
        }
        return normalized
    }

    fun resolvePackageForDisplay(packageName: String): ToolPackage? {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)
        val toolPackage = availablePackages[normalizedPackageName] ?: return null
        return selectToolPackageState(toolPackage)
    }

    fun isToolPkgContainer(packageName: String): Boolean {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)
        return toolPkgContainers.containsKey(normalizedPackageName)
    }

    fun isToolPkgSubpackage(packageName: String): Boolean {
        ensureInitialized()
        return resolveToolPkgSubpackageRuntime(packageName) != null
    }

    fun isTopLevelPackage(packageName: String): Boolean {
        ensureInitialized()
        return resolveToolPkgSubpackageRuntime(packageName) == null
    }

    fun getTopLevelAvailablePackages(forceRefresh: Boolean = false): Map<String, ToolPackage> {
        val packages = getAvailablePackages(forceRefresh)
        return packages.filterKeys { !toolPkgSubpackageByPackageName.containsKey(it) }
    }

    fun getToolPkgContainerDetails(
        packageName: String,
        resolveContext: Context? = null
    ): ToolPkgContainerDetails? {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)
        val container = toolPkgContainers[normalizedPackageName] ?: return null
        val importedSet = getImportedPackages().toSet()
        val localizationContext = resolveContext ?: context
        val containerEnabled = importedSet.contains(container.packageName)

        val subpackages =
            container.subpackages.map { subpackage ->
                ToolPkgSubpackageInfo(
                    packageName = subpackage.packageName,
                    subpackageId = subpackage.subpackageId,
                    displayName = subpackage.displayName.resolve(localizationContext),
                    description = subpackage.description.resolve(localizationContext),
                    enabledByDefault = subpackage.enabledByDefault,
                    toolCount = subpackage.toolCount,
                    enabled = containerEnabled && importedSet.contains(subpackage.packageName)
                )
            }

        return ToolPkgContainerDetails(
            packageName = container.packageName,
            displayName = container.displayName.resolve(localizationContext),
            description = container.description.resolve(localizationContext),
            version = container.version,
            resourceCount = container.resources.size,
            uiModuleCount = container.uiModules.size,
            subpackages = subpackages
        )
    }

    fun getToolPkgToolboxUiModules(
        runtime: String = "compose_dsl",
        resolveContext: Context? = null
    ): List<ToolPkgToolboxUiModule> {
        ensureInitialized()
        val localizationContext = resolveContext ?: context
        fun resolveLocalized(text: com.ai.assistance.custard.core.tools.LocalizedText): String {
            return text.resolve(localizationContext)
        }
        val importedSet = getImportedPackages().toSet()

        return toolPkgContainers.values
            .filter { container -> importedSet.contains(container.packageName) }
            .flatMap { container ->
                val containerDisplayName =
                    resolveLocalized(container.displayName).ifBlank { container.packageName }
                val containerDescription = resolveLocalized(container.description)
                container.uiModules
                    .filter { module ->
                        module.showInPackageManager &&
                            module.runtime.equals(runtime, ignoreCase = true)
                    }
                    .map { module ->
                        val moduleTitle =
                            resolveLocalized(module.title).trim().ifBlank { containerDisplayName }
                        ToolPkgToolboxUiModule(
                            containerPackageName = container.packageName,
                            toolPkgId = container.packageName,
                            uiModuleId = module.id,
                            runtime = module.runtime,
                            entry = module.entry,
                            title = moduleTitle,
                            description = containerDescription,
                            moduleSpec =
                                mapOf(
                                    "id" to module.id,
                                    "runtime" to module.runtime,
                                    "entry" to module.entry,
                                    "title" to moduleTitle,
                                    "toolPkgId" to container.packageName
                                )
                        )
                    }
            }
            .sortedWith(
                compareBy(
                    ToolPkgToolboxUiModule::title,
                    ToolPkgToolboxUiModule::containerPackageName,
                    ToolPkgToolboxUiModule::uiModuleId
                )
            )
    }

    fun setToolPkgSubpackageEnabled(subpackagePackageName: String, enabled: Boolean): Boolean {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(subpackagePackageName)
        val subpackageRuntime = toolPkgSubpackageByPackageName[normalizedPackageName]
        if (subpackageRuntime == null) {
            return false
        }

        val importedPackages = LinkedHashSet(getImportedPackages())
        val subpackageStates = getToolPkgSubpackageStatesInternal().toMutableMap()
        val containerEnabled = importedPackages.contains(subpackageRuntime.containerPackageName)

        subpackageStates[normalizedPackageName] = enabled

        if (containerEnabled && enabled) {
            importedPackages.add(normalizedPackageName)
        } else {
            importedPackages.remove(normalizedPackageName)
            unregisterPackageTools(normalizedPackageName)
        }

        saveImportedPackages(importedPackages.toList())
        saveToolPkgSubpackageStates(subpackageStates)

        val stateSaved = getToolPkgSubpackageStatesInternal()[normalizedPackageName] == enabled
        val importedMatches =
            if (containerEnabled) {
                getImportedPackages().contains(normalizedPackageName) == enabled
            } else {
                !getImportedPackages().contains(normalizedPackageName)
            }
        return stateSaved && importedMatches
    }

    fun findPreferredPackageNameForSubpackageId(
        subpackageId: String,
        preferImported: Boolean = true
    ): String? {
        ensureInitialized()
        if (subpackageId.isBlank()) return null

        val directRuntime = resolveToolPkgSubpackageRuntime(subpackageId)
        if (directRuntime != null) {
            if (preferImported) {
                if (isPackageImported(directRuntime.packageName)) {
                    return directRuntime.packageName
                }
            }
            return directRuntime.packageName
        }

        val candidates =
            toolPkgSubpackageByPackageName.values.filter {
                it.subpackageId.equals(subpackageId, ignoreCase = true)
            }

        if (candidates.isEmpty()) {
            return null
        }

        if (preferImported) {
            val importedCandidate = candidates.firstOrNull { isPackageImported(it.packageName) }
            if (importedCandidate != null) {
                return importedCandidate.packageName
            }
        }

        return candidates.first().packageName
    }

    fun copyToolPkgResourceToFileBySubpackageId(
        subpackageId: String,
        resourceKey: String,
        destinationFile: File,
        preferImportedContainer: Boolean = true
    ): Boolean {
        ensureInitialized()
        if (subpackageId.isBlank() || resourceKey.isBlank()) {
            return false
        }

        val directSubpackage = resolveToolPkgSubpackageRuntime(subpackageId)
        val subpackages =
            if (directSubpackage != null) {
                listOf(directSubpackage)
            } else {
                toolPkgSubpackageByPackageName.values.filter {
                    it.subpackageId.equals(subpackageId, ignoreCase = true)
                }
            }

        if (subpackages.isEmpty()) {
            return false
        }

        val candidateContainers =
            if (preferImportedContainer) {
                val imported = getImportedPackages().toSet()
                val importedContainers =
                    subpackages
                        .map { it.containerPackageName }
                        .distinct()
                        .filter { imported.contains(it) }
                if (importedContainers.isNotEmpty()) {
                    importedContainers
                } else {
                    subpackages.map { it.containerPackageName }.distinct()
                }
            } else {
                subpackages.map { it.containerPackageName }.distinct()
            }

        candidateContainers.forEach { containerName ->
            if (copyToolPkgResourceToFile(containerName, resourceKey, destinationFile)) {
                return true
            }
        }

        return false
    }

    fun copyToolPkgResourceToFile(
        containerPackageName: String,
        resourceKey: String,
        destinationFile: File
    ): Boolean {
        ensureInitialized()
        val normalizedContainerPackageName = normalizePackageName(containerPackageName)
        val runtime = toolPkgContainers[normalizedContainerPackageName] ?: return false
        val importedSet = getImportedPackages().toSet()
        if (!importedSet.contains(runtime.packageName)) {
            return false
        }
        val resource =
            runtime.resources.firstOrNull {
                it.key.equals(resourceKey, ignoreCase = true)
            } ?: return false

        return try {
            val bytes = readToolPkgResourceBytes(runtime, resource.path) ?: return false
            val parent = destinationFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            destinationFile.outputStream().use { output ->
                output.write(bytes)
            }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to export toolpkg resource: ${runtime.packageName}:${resource.key}", e)
            false
        }
    }

    fun getToolPkgResourceOutputFileName(
        packageNameOrSubpackageId: String,
        resourceKey: String,
        preferImportedContainer: Boolean = true
    ): String? {
        ensureInitialized()
        val target = packageNameOrSubpackageId.trim()
        val key = resourceKey.trim()
        if (target.isBlank() || key.isBlank()) {
            return null
        }

        fun resolveFromContainer(containerName: String): String? {
            val normalizedContainerName = normalizePackageName(containerName)
            val runtime = toolPkgContainers[normalizedContainerName] ?: return null
            val resource =
                runtime.resources.firstOrNull {
                    it.key.equals(key, ignoreCase = true)
                } ?: return null
            val fileName =
                resource.path.substringAfterLast('/').substringAfterLast('\\').trim()
            return fileName.ifBlank { null }
        }

        resolveFromContainer(target)?.let { return it }

        val directSubpackage = resolveToolPkgSubpackageRuntime(target)
        if (directSubpackage != null) {
            resolveFromContainer(directSubpackage.containerPackageName)?.let { return it }
        }

        val subpackages =
            toolPkgSubpackageByPackageName.values.filter {
                it.subpackageId.equals(target, ignoreCase = true)
            }
        if (subpackages.isEmpty()) {
            return null
        }

        val candidateContainers =
            if (preferImportedContainer) {
                val imported = getImportedPackages().toSet()
                val importedContainers =
                    subpackages
                        .map { it.containerPackageName }
                        .distinct()
                        .filter { imported.contains(it) }
                if (importedContainers.isNotEmpty()) {
                    importedContainers
                } else {
                    subpackages.map { it.containerPackageName }.distinct()
                }
            } else {
                subpackages.map { it.containerPackageName }.distinct()
            }

        candidateContainers.forEach { containerName ->
            resolveFromContainer(containerName)?.let { return it }
        }

        return null
    }

    fun getToolPkgComposeDslScriptBySubpackageId(
        subpackageId: String,
        uiModuleId: String? = null,
        preferImportedContainer: Boolean = true
    ): String? {
        ensureInitialized()
        if (subpackageId.isBlank()) {
            return null
        }

        val directSubpackage = resolveToolPkgSubpackageRuntime(subpackageId)
        val subpackages =
            if (directSubpackage != null) {
                listOf(directSubpackage)
            } else {
                toolPkgSubpackageByPackageName.values.filter {
                    it.subpackageId.equals(subpackageId, ignoreCase = true)
                }
            }

        if (subpackages.isEmpty()) {
            return null
        }

        val candidateContainers =
            if (preferImportedContainer) {
                val imported = getImportedPackages().toSet()
                subpackages
                    .map { it.containerPackageName }
                    .distinct()
                    .filter { imported.contains(it) }
            } else {
                subpackages.map { it.containerPackageName }.distinct()
            }

        candidateContainers.forEach { containerName ->
            val script = getToolPkgComposeDslScript(containerName, uiModuleId)
            if (!script.isNullOrBlank()) {
                return script
            }
        }

        return null
    }

    fun getToolPkgComposeDslScript(
        containerPackageName: String,
        uiModuleId: String? = null
    ): String? {
        ensureInitialized()
        val normalizedContainerPackageName = normalizePackageName(containerPackageName)
        val runtime = toolPkgContainers[normalizedContainerPackageName] ?: return null
        val importedSet = getImportedPackages().toSet()
        if (!importedSet.contains(runtime.packageName)) {
            return null
        }

        val uiModule =
            if (!uiModuleId.isNullOrBlank()) {
                runtime.uiModules.firstOrNull { module ->
                    module.id.equals(uiModuleId, ignoreCase = true) &&
                        module.runtime.equals("compose_dsl", ignoreCase = true)
                }
            } else {
                runtime.uiModules.firstOrNull { module ->
                    module.runtime.equals("compose_dsl", ignoreCase = true)
                }
            } ?: return null

        if (uiModule.entry.isBlank()) {
            return null
        }

        return try {
            val bytes = readToolPkgResourceBytes(runtime, uiModule.entry) ?: return null
            bytes.toString(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.e(
                TAG,
                "Failed to read toolpkg compose_dsl script: ${runtime.packageName}:${uiModule.id}",
                e
            )
            null
        }
    }

    fun getToolPkgComposeDslEntryPath(
        containerPackageName: String,
        uiModuleId: String? = null
    ): String? {
        ensureInitialized()
        val normalizedContainerPackageName = normalizePackageName(containerPackageName)
        val runtime = toolPkgContainers[normalizedContainerPackageName] ?: return null
        val importedSet = getImportedPackages().toSet()
        if (!importedSet.contains(runtime.packageName)) {
            return null
        }

        val uiModule =
            if (!uiModuleId.isNullOrBlank()) {
                runtime.uiModules.firstOrNull { module ->
                    module.id.equals(uiModuleId, ignoreCase = true) &&
                        module.runtime.equals("compose_dsl", ignoreCase = true)
                }
            } else {
                runtime.uiModules.firstOrNull { module ->
                    module.runtime.equals("compose_dsl", ignoreCase = true)
                }
            } ?: return null

        return uiModule.entry.trim().ifBlank { null }
    }

    fun readToolPkgTextResource(
        packageNameOrSubpackageId: String,
        resourcePath: String,
        preferImportedContainer: Boolean = true
    ): String? {
        ensureInitialized()
        val target = packageNameOrSubpackageId.trim()
        val normalizedPath =
            resourcePath
                .trim()
                .replace('\\', '/')
                .trimStart('/')

        if (target.isBlank() || normalizedPath.isBlank()) {
            return null
        }

        val containerRuntime = toolPkgContainers[target]
        if (containerRuntime != null) {
            val importedSet = getImportedPackages().toSet()
            if (!importedSet.contains(containerRuntime.packageName)) {
                return null
            }
            return readToolPkgResourceBytes(containerRuntime, normalizedPath)
                ?.toString(StandardCharsets.UTF_8)
        }

        val directSubpackageRuntime = resolveToolPkgSubpackageRuntime(target)
        if (directSubpackageRuntime != null) {
            val directContainer = toolPkgContainers[directSubpackageRuntime.containerPackageName]
            if (directContainer != null) {
                val importedSet = getImportedPackages().toSet()
                if (!importedSet.contains(directContainer.packageName)) {
                    return null
                }
                return readToolPkgResourceBytes(directContainer, normalizedPath)
                    ?.toString(StandardCharsets.UTF_8)
            }
        }

        val subpackages =
            toolPkgSubpackageByPackageName.values.filter {
                it.subpackageId.equals(target, ignoreCase = true)
            }
        if (subpackages.isEmpty()) {
            return null
        }

        val candidateContainers =
            if (preferImportedContainer) {
                val imported = getImportedPackages().toSet()
                subpackages
                    .map { it.containerPackageName }
                    .distinct()
                    .filter { imported.contains(it) }
            } else {
                subpackages.map { it.containerPackageName }.distinct()
            }

        candidateContainers.forEach { containerName ->
            val runtime = toolPkgContainers[containerName] ?: return@forEach
            val text =
                readToolPkgResourceBytes(runtime, normalizedPath)
                    ?.toString(StandardCharsets.UTF_8)
            if (!text.isNullOrEmpty()) {
                return text
            }
        }

        return null
    }

    /**
     * Automatically imports built-in packages that are marked as enabled by default.
     * This ensures that essential or commonly used packages are available without
     * manual user intervention. It also respects a user's choice to disable a
     * default package.
     */
    private fun initializeDefaultPackages() {
        val importedPackages = getImportedPackagesInternal().toMutableSet()
        val disabledPackages = getDisabledPackagesInternal().toSet()
        var packagesChanged = false

        availablePackages.values.forEach { toolPackage ->
            if (
                toolPackage.isBuiltIn &&
                toolPackage.enabledByDefault &&
                !toolPkgSubpackageByPackageName.containsKey(toolPackage.name) &&
                !disabledPackages.contains(toolPackage.name)
            ) {
                if (importedPackages.add(toolPackage.name)) {
                    packagesChanged = true
                    AppLogger.d(TAG, "Auto-importing default package: ${toolPackage.name}")
                }
            }
        }

        if (packagesChanged) {
            val prefs = context.getSharedPreferences(PACKAGE_PREFS, Context.MODE_PRIVATE)
            val updatedJson = Json.encodeToString(importedPackages.toList())
            prefs.edit().putString(IMPORTED_PACKAGES_KEY, updatedJson).apply()
            AppLogger.d(TAG, "Updated imported packages with default packages.")
        }
    }

    /**
     * Loads all available packages metadata (from assets and external storage).
     * Includes legacy JS packages and new .toolpkg containers/subpackages.
     */
    private fun loadAvailablePackages() {
        synchronized(initLock) {
            packageLoadErrors.clear()
            availablePackages.clear()
            toolPkgContainers.clear()
            toolPkgSubpackageByPackageName.clear()

            val assetManager = context.assets
            val packageFiles = assetManager.list(ASSETS_PACKAGES_DIR) ?: emptyArray()

            for (fileName in packageFiles) {
                val assetPath = "$ASSETS_PACKAGES_DIR/$fileName"
                when {
                    fileName.endsWith(".js", ignoreCase = true) -> {
                        val packageMetadata = loadPackageFromJsAsset(assetPath)
                        if (packageMetadata != null) {
                            availablePackages[packageMetadata.name] = packageMetadata.copy(isBuiltIn = true)
                        }
                    }
                    fileName.endsWith(TOOLPKG_EXTENSION, ignoreCase = true) -> {
                        val loadResult = loadToolPkgFromAsset(assetPath)
                        if (loadResult != null) {
                            registerToolPkg(loadResult)
                        }
                    }
                }
            }

            if (externalPackagesDir.exists()) {
                val externalFiles = externalPackagesDir.listFiles() ?: emptyArray()
                for (file in externalFiles) {
                    if (!file.isFile) continue
                    when {
                        file.name.endsWith(".js", ignoreCase = true) -> {
                            val packageMetadata = loadPackageFromJsFile(file)
                            if (packageMetadata != null) {
                                availablePackages[packageMetadata.name] = packageMetadata.copy(isBuiltIn = false)
                            }
                        }
                        file.name.endsWith(TOOLPKG_EXTENSION, ignoreCase = true) -> {
                            val loadResult = loadToolPkgFromExternalFile(file)
                            if (loadResult != null) {
                                registerToolPkg(loadResult)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun registerToolPkg(loadResult: ToolPkgLoadResult): Boolean {
        val containerName = loadResult.containerPackage.name
        if (availablePackages.containsKey(containerName)) {
            packageLoadErrors[containerName] = "Duplicate package name: $containerName"
            AppLogger.w(TAG, "Skipped duplicated toolpkg container: $containerName")
            return false
        }

        val duplicateSubpackages =
            loadResult.subpackagePackages
                .map { it.name }
                .filter { availablePackages.containsKey(it) }

        if (duplicateSubpackages.isNotEmpty()) {
            packageLoadErrors[containerName] =
                "Duplicate subpackage names: ${duplicateSubpackages.joinToString(", ")}"
            AppLogger.w(TAG, "Skipped toolpkg '$containerName' due to duplicate subpackages: $duplicateSubpackages")
            return false
        }

        availablePackages[containerName] = loadResult.containerPackage
        toolPkgContainers[containerName] = loadResult.containerRuntime

        loadResult.subpackagePackages.forEach { subpackage ->
            availablePackages[subpackage.name] = subpackage
        }

        loadResult.containerRuntime.subpackages.forEach { runtime ->
            toolPkgSubpackageByPackageName[runtime.packageName] = runtime
        }
        return true
    }

    /** Loads a complete ToolPackage from a JavaScript file */
    private fun loadPackageFromJsFile(file: File): ToolPackage? {
        try {
            val jsContent = file.readText()
            return parseJsPackage(jsContent) { key, error ->
                packageLoadErrors[key] = "${file.path}: $error"
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading package from JS file: ${file.path}", e)
            packageLoadErrors[file.nameWithoutExtension] = "${file.path}: ${e.stackTraceToString()}"
            return null
        }
    }

    /** Loads a complete ToolPackage from a JavaScript file in assets */
    private fun loadPackageFromJsAsset(assetPath: String): ToolPackage? {
        try {
            val assetManager = context.assets
            val jsContent = assetManager.open(assetPath).bufferedReader().use { it.readText() }
            return parseJsPackage(jsContent) { key, error ->
                packageLoadErrors[key] = "$assetPath: $error"
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading package from JS asset: $assetPath", e)
            packageLoadErrors[assetPath.substringAfterLast("/").removeSuffix(".js")] =
                "$assetPath: ${e.stackTraceToString()}"
            return null
        }
    }

    private fun loadToolPkgFromExternalFile(file: File): ToolPkgLoadResult? {
        return try {
            file.inputStream().use { input ->
                val entries = ToolPkgArchiveParser.readZipEntries(input)
                ToolPkgArchiveParser.parseToolPkgFromEntries(
                    entries = entries,
                    sourceType = ToolPkgSourceType.EXTERNAL,
                    sourcePath = file.absolutePath,
                    isBuiltIn = false,
                    parseJsPackage = { jsContent, onError -> parseJsPackage(jsContent, onError) },
                    reportPackageLoadError = { key, error ->
                        packageLoadErrors[key] = error
                    }
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading toolpkg from external file: ${file.absolutePath}", e)
            packageLoadErrors[file.nameWithoutExtension] = "${file.absolutePath}: ${e.stackTraceToString()}"
            null
        }
    }

    private fun loadToolPkgFromAsset(assetPath: String): ToolPkgLoadResult? {
        return try {
            context.assets.open(assetPath).use { input ->
                val entries = ToolPkgArchiveParser.readZipEntries(input)
                ToolPkgArchiveParser.parseToolPkgFromEntries(
                    entries = entries,
                    sourceType = ToolPkgSourceType.ASSET,
                    sourcePath = assetPath,
                    isBuiltIn = true,
                    parseJsPackage = { jsContent, onError -> parseJsPackage(jsContent, onError) },
                    reportPackageLoadError = { key, error ->
                        packageLoadErrors[key] = error
                    }
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading toolpkg from asset: $assetPath", e)
            packageLoadErrors[assetPath.substringAfterLast('/').removeSuffix(TOOLPKG_EXTENSION)] =
                "$assetPath: ${e.stackTraceToString()}"
            null
        }
    }

    private fun readToolPkgResourceBytes(
        runtime: ToolPkgContainerRuntime,
        normalizedResourcePath: String
    ): ByteArray? {
        return when (runtime.sourceType) {
            ToolPkgSourceType.EXTERNAL ->
                ToolPkgArchiveParser.readZipEntryBytesFromExternal(
                    runtime.sourcePath,
                    normalizedResourcePath
                )
            ToolPkgSourceType.ASSET ->
                ToolPkgArchiveParser.readZipEntryBytesFromAsset(
                    context,
                    runtime.sourcePath,
                    normalizedResourcePath
                )
        }
    }

    /**
     * Parses a JavaScript package file into a ToolPackage object Uses the metadata in the file
     * header and extracts function definitions using JsEngine
     */
    private fun parseJsPackage(
        jsContent: String,
        onError: (key: String, error: String) -> Unit = { _, _ -> }
    ): ToolPackage? {
        try {
            // Extract metadata from comments at the top of the file
            val metadataString = extractMetadataFromJs(jsContent)

            // 先将元数据解析为 JSONObject 以便修改 tools 数组中的每个元素
            val metadataJson = org.json.JSONObject(JsonValue.readHjson(metadataString).toString())

            // 统一历史键名/值格式，避免 enabledByDefault 在 Kotlin 侧被错误解析为默认值
            normalizeJsPackageMetadata(metadataJson)

            // 检查并修复 tools 数组中的元素，确保每个工具都有 script 字段
            if (metadataJson.has("tools") && metadataJson.get("tools") is org.json.JSONArray) {
                val toolsArray = metadataJson.getJSONArray("tools")
                for (i in 0 until toolsArray.length()) {
                    val tool = toolsArray.getJSONObject(i)
                    if (!tool.has("script")) {
                        // 添加一个临时的空 script 字段
                        tool.put("script", "")
                    }
                }
            }

            // 检查并修复 states.tools 数组中的元素，确保每个工具都有 script 字段
            if (metadataJson.has("states") && metadataJson.get("states") is org.json.JSONArray) {
                val statesArray = metadataJson.getJSONArray("states")
                for (i in 0 until statesArray.length()) {
                    val state = statesArray.optJSONObject(i) ?: continue
                    if (state.has("tools") && state.get("tools") is org.json.JSONArray) {
                        val toolsArray = state.getJSONArray("tools")
                        for (j in 0 until toolsArray.length()) {
                            val tool = toolsArray.getJSONObject(j)
                            if (!tool.has("script")) {
                                tool.put("script", "")
                            }
                        }
                    }
                }
            }

            // 使用修改后的 JSON 字符串进行反序列化
            val jsonString = metadataJson.toString()

            val jsonConfig = Json { ignoreUnknownKeys = true }
            val packageMetadata = jsonConfig.decodeFromString<ToolPackage>(jsonString)

            // 更新所有工具，使用相同的完整脚本内容，但记录每个工具的函数名
            val tools =
                packageMetadata.tools.map { tool ->
                    // 检查函数是否存在于脚本中
                    if (!tool.advice) {
                        validateToolFunctionExists(jsContent, tool.name)
                    }

                    // 使用整个脚本，并记录函数名，而不是提取单个函数
                    tool.copy(script = jsContent)
                }

            val states =
                packageMetadata.states.map { state ->
                    val stateTools =
                        state.tools.map { tool ->
                            if (!tool.advice) {
                                validateToolFunctionExists(jsContent, tool.name)
                            }
                            tool.copy(script = jsContent)
                        }
                    state.copy(tools = stateTools)
                }

            return packageMetadata.copy(tools = tools, states = states)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error parsing JS package: ${e.message}", e)
            val fallbackKey = try {
                val metadataString = extractMetadataFromJs(jsContent)
                val metadataJson = org.json.JSONObject(JsonValue.readHjson(metadataString).toString())
                metadataJson.optString("name").takeIf { it.isNotBlank() } ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }
            onError(fallbackKey, e.stackTraceToString())
            return null
        }
    }

    private fun normalizeJsPackageMetadata(metadataJson: org.json.JSONObject) {
        normalizeBooleanFieldAlias(
            metadataJson = metadataJson,
            canonicalKey = "enabledByDefault",
            legacyAlias = "enabled_by_default"
        )
        normalizeBooleanFieldAlias(
            metadataJson = metadataJson,
            canonicalKey = "isBuiltIn",
            legacyAlias = "is_built_in"
        )
    }

    private fun normalizeBooleanFieldAlias(
        metadataJson: org.json.JSONObject,
        canonicalKey: String,
        legacyAlias: String
    ) {
        if (!metadataJson.has(canonicalKey) && metadataJson.has(legacyAlias)) {
            metadataJson.put(canonicalKey, metadataJson.opt(legacyAlias))
        }

        if (!metadataJson.has(canonicalKey)) {
            return
        }

        val normalized = normalizeToBoolean(metadataJson.opt(canonicalKey)) ?: return
        metadataJson.put(canonicalKey, normalized)
    }

    private fun normalizeToBoolean(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> {
                when (value.trim().lowercase()) {
                    "true", "1", "yes", "on" -> true
                    "false", "0", "no", "off" -> false
                    else -> null
                }
            }
            else -> null
        }
    }

    fun getPackageLoadErrors(): Map<String, String> {
        ensureInitialized()
        return packageLoadErrors.toMap()
    }

    /** 验证JavaScript文件中是否存在指定的函数 这确保了我们可以在运行时调用该函数 */
    private fun validateToolFunctionExists(jsContent: String, toolName: String): Boolean {
        // 各种函数声明模式
        val patterns =
            listOf(
                """async\s+function\s+$toolName\s*\(""",
                """function\s+$toolName\s*\(""",
                """exports\.$toolName\s*=\s*(?:async\s+)?function""",
                """(?:const|let|var)\s+$toolName\s*=\s*(?:async\s+)?\(""",
                """exports\.$toolName\s*=\s*(?:async\s+)?\(?"""
            )

        for (pattern in patterns) {
            if (pattern.toRegex().find(jsContent) != null) {
                return true
            }
        }

        AppLogger.w(TAG, "Could not find function '$toolName' in JavaScript file")
        return false
    }

    /** Extracts the metadata from JS comments at the top of the file */
    private fun extractMetadataFromJs(jsContent: String): String {
        val metadataPattern = """/\*\s*METADATA\s*([\s\S]*?)\*/""".toRegex()
        val match = metadataPattern.find(jsContent)

        return if (match != null) {
            match.groupValues[1].trim()
        } else {
            // If no metadata block is found, return empty metadata
            "{}"
        }
    }

    /**
     * Returns the path to the external packages directory This can be used to show the user where
     * the packages are stored for manual editing
     */
    fun getExternalPackagesPath(): String {
        // 为了更易读，改成Android/data/包名/files/packages的形式
        return "Android/data/${context.packageName}/files/packages"
    }

    /**
     * Imports a package from external storage path.
     * Supports legacy JS/TS/HJSON files and .toolpkg containers.
     */
    fun importPackageFromExternalStorage(filePath: String): String {
        try {
            ensureInitialized()

            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                return "Cannot access file at path: $filePath"
            }

            val lowerPath = filePath.lowercase()
            val isToolPkg = lowerPath.endsWith(TOOLPKG_EXTENSION)
            val isJsLike = lowerPath.endsWith(".js") || lowerPath.endsWith(".ts")
            val isHjson = lowerPath.endsWith(".hjson")

            if (!isToolPkg && !isJsLike && !isHjson) {
                return "Only .toolpkg, HJSON, JavaScript (.js) and TypeScript (.ts) package files are supported"
            }

            if (isToolPkg) {
                val preview = loadToolPkgFromExternalFile(file)
                    ?: return "Failed to parse toolpkg file"
                val containerName = preview.containerPackage.name
                if (availablePackages.containsKey(containerName)) {
                    return "A package with name '$containerName' already exists in available packages"
                }

                val conflictSubpackages =
                    preview.subpackagePackages
                        .map { it.name }
                        .filter { availablePackages.containsKey(it) }
                if (conflictSubpackages.isNotEmpty()) {
                    return "Subpackage name conflict: ${conflictSubpackages.joinToString(", ")}"
                }

                val destinationFile = File(externalPackagesDir, file.name)
                if (file.absolutePath != destinationFile.absolutePath) {
                    file.inputStream().use { input ->
                        destinationFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }

                val loadedFromDestination = loadToolPkgFromExternalFile(destinationFile)
                    ?: return "Failed to parse copied toolpkg file"
                if (!registerToolPkg(loadedFromDestination)) {
                    return "Failed to register toolpkg '$containerName' due to naming conflict"
                }

                return "Successfully imported toolpkg: $containerName\nStored at: ${destinationFile.absolutePath}"
            }

            val packageMetadata =
                if (isHjson) {
                    val hjsonContent = file.readText()
                    val jsonString = JsonValue.readHjson(hjsonContent).toString()
                    val jsonConfig = Json { ignoreUnknownKeys = true }
                    jsonConfig.decodeFromString<ToolPackage>(jsonString)
                } else {
                    loadPackageFromJsFile(file)
                        ?: return "Failed to parse ${if (lowerPath.endsWith(".ts")) "TypeScript" else "JavaScript"} package file"
                }

            if (availablePackages.containsKey(packageMetadata.name)) {
                return "A package with name '${packageMetadata.name}' already exists in available packages"
            }

            val destinationFile = File(externalPackagesDir, file.name)
            if (file.absolutePath != destinationFile.absolutePath) {
                file.inputStream().use { input ->
                    destinationFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            availablePackages[packageMetadata.name] = packageMetadata.copy(isBuiltIn = false)

            AppLogger.d(TAG, "Successfully imported external package to: ${destinationFile.absolutePath}")
            return "Successfully imported package: ${packageMetadata.name}\nStored at: ${destinationFile.absolutePath}"
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error importing package from external storage", e)
            return "Error importing package: ${e.message}"
        }
    }

    /**
     * Import a package by name, adding it to the user's imported packages list.
     * For toolpkg containers this may also activate default-enabled subpackages.
     */
    fun importPackage(packageName: String): String {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)

        if (!availablePackages.containsKey(normalizedPackageName)) {
            return "Package not found in available packages: $normalizedPackageName"
        }

        val importedPackages = LinkedHashSet(getImportedPackages())
        val subpackageStates = getToolPkgSubpackageStatesInternal().toMutableMap()

        val containerRuntime = toolPkgContainers[normalizedPackageName]
        if (containerRuntime != null) {
            val containerAlreadyImported = importedPackages.contains(normalizedPackageName)
            importedPackages.add(normalizedPackageName)

            containerRuntime.subpackages.forEach { subpackage ->
                val shouldEnable =
                    subpackageStates[subpackage.packageName] ?: subpackage.enabledByDefault
                subpackageStates.putIfAbsent(subpackage.packageName, shouldEnable)

                if (shouldEnable) {
                    importedPackages.add(subpackage.packageName)
                } else {
                    importedPackages.remove(subpackage.packageName)
                }
            }

            saveImportedPackages(importedPackages.toList())
            saveToolPkgSubpackageStates(subpackageStates)
            removeFromDisabledPackages(normalizedPackageName)

            val message =
                if (containerAlreadyImported) {
                    "Toolpkg container '$normalizedPackageName' is already enabled"
                } else {
                    "Successfully enabled toolpkg container: $normalizedPackageName"
                }
            AppLogger.d(TAG, message)
            return message
        }

        val subpackageRuntime = toolPkgSubpackageByPackageName[normalizedPackageName]
        if (subpackageRuntime != null) {
            importedPackages.add(subpackageRuntime.containerPackageName)
            importedPackages.add(normalizedPackageName)
            subpackageStates[normalizedPackageName] = true

            saveImportedPackages(importedPackages.toList())
            saveToolPkgSubpackageStates(subpackageStates)
            removeFromDisabledPackages(subpackageRuntime.containerPackageName)

            val message = "Successfully enabled toolpkg subpackage: $normalizedPackageName"
            AppLogger.d(TAG, message)
            return message
        }

        if (importedPackages.contains(normalizedPackageName)) {
            return "Package '$normalizedPackageName' is already imported"
        }

        importedPackages.add(normalizedPackageName)
        saveImportedPackages(importedPackages.toList())
        removeFromDisabledPackages(normalizedPackageName)

        AppLogger.d(TAG, "Successfully imported package: $normalizedPackageName")
        return "Successfully imported package: $normalizedPackageName"
    }

    /**
     * Activates and loads a package for use in the current AI session This loads the full package
     * data and registers its tools with AIToolHandler
     * @param packageName The name of the imported package to use
     * @return Package description and tools for AI prompt enhancement, or error message
     */
    fun usePackage(packageName: String): String {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)

        val containerRuntime = toolPkgContainers[normalizedPackageName]
        if (containerRuntime != null) {
            return "Toolpkg container '$normalizedPackageName' is not a package and cannot be activated."
        }

        // First check if packageName is a standard imported package (priority)
        val importedPackages = getImportedPackages()
        val subpackageRuntime = toolPkgSubpackageByPackageName[normalizedPackageName]
        if (subpackageRuntime != null &&
            !importedPackages.contains(subpackageRuntime.containerPackageName)
        ) {
            return "Toolpkg container '${subpackageRuntime.containerPackageName}' is not enabled. Package '$normalizedPackageName' is inactive."
        }
        if (importedPackages.contains(normalizedPackageName)) {
            // Load the full package data for a standard package
            val toolPackage =
                getPackageTools(normalizedPackageName)
                    ?: return "Failed to load package data for: $normalizedPackageName"

            // Validate required environment variables, if any
            if (toolPackage.env.isNotEmpty()) {
                val missingRequiredEnv = mutableListOf<String>()
                val missingOptionalEnv = mutableListOf<Pair<String, String>>() // env name, default value

                toolPackage.env.forEach { envVar ->
                    val envName = envVar.name.trim()
                    if (envName.isEmpty()) return@forEach

                    val value = try {
                        envPreferences.getEnv(envName)
                    } catch (e: Exception) {
                        AppLogger.e(
                            TAG,
                            "Error reading environment variable '$envName' for package '$normalizedPackageName'",
                            e
                        )
                        null
                    }

                    if (envVar.required) {
                        // Check required environment variables
                        if (value.isNullOrEmpty()) {
                            missingRequiredEnv.add(envName)
                        }
                    } else {
                        // Check optional environment variables
                        if (value.isNullOrEmpty()) {
                            if (envVar.defaultValue != null) {
                                // Use default value for optional env vars
                                missingOptionalEnv.add(envName to envVar.defaultValue)
                                AppLogger.d(
                                    TAG,
                                    "Optional env var '$envName' not set for package '$normalizedPackageName', using default value: ${envVar.defaultValue}"
                                )
                            } else {
                                // Optional env var without default value is acceptable
                                AppLogger.d(
                                    TAG,
                                    "Optional env var '$envName' not set for package '$normalizedPackageName' (no default value)"
                                )
                            }
                        }
                    }
                }

                // Only fail if required environment variables are missing
                if (missingRequiredEnv.isNotEmpty()) {
                    val msg =
                        buildString {
                            append("Package '")
                            append(normalizedPackageName)
                            append("' requires environment variable")
                            if (missingRequiredEnv.size > 1) append("s")
                            append(": ")
                            append(missingRequiredEnv.joinToString(", "))
                            append(". Please set them before using this package.")
                        }
                    AppLogger.w(TAG, msg)
                    return msg
                }

                // Log info about optional env vars using defaults
                if (missingOptionalEnv.isNotEmpty()) {
                    AppLogger.i(
                        TAG,
                        "Package '$normalizedPackageName' will use default values for optional env vars: ${missingOptionalEnv.map { it.first }.joinToString(", ")}"
                    )
                }
            }

            // Register the package tools with AIToolHandler
            val selectedPackage = selectToolPackageState(toolPackage)
            registerPackageTools(selectedPackage)

            AppLogger.d(TAG, "Successfully loaded and activated package: $normalizedPackageName")

            // Generate and return the system prompt enhancement
            return generatePackageSystemPrompt(selectedPackage)
        }

        // Then check if it's a Skill package
        if (skillManager.getAvailableSkills().containsKey(normalizedPackageName) &&
            !skillVisibilityPreferences.isSkillVisibleToAi(normalizedPackageName)
        ) {
            return "Skill '$normalizedPackageName' is set to not show to AI"
        }

        val skillPrompt = skillManager.getSkillSystemPrompt(normalizedPackageName)
        if (skillPrompt != null) {
            return skillPrompt
        }

        // Next check if it's an MCP server by checking with MCPManager
        if (isRegisteredMCPServer(normalizedPackageName)) {
            return useMCPServer(normalizedPackageName)
        }

        return "Package not found: $normalizedPackageName. Please import it first or register it as an MCP server."
    }

    fun getActivePackageStateId(packageName: String): String? {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)
        return activePackageStateIds[normalizedPackageName]
    }

    /**
     * Wrapper for tool execution: builds ToolResult for the 'use_package' tool.
     * Keeps registration site minimal by centralizing result construction here.
     */
    fun executeUsePackageTool(toolName: String, packageName: String): ToolResult {
        if (packageName.isBlank()) {
            return ToolResult(
                toolName = toolName,
                success = false,
                result = StringResultData(""),
                error = "Missing required parameter: package_name"
            )
        }

        val normalizedPackageName = normalizePackageName(packageName)
        if (isToolPkgContainer(normalizedPackageName)) {
            return ToolResult(
                toolName = toolName,
                success = false,
                result = StringResultData(""),
                error = "Toolpkg container '$normalizedPackageName' is not a package and cannot be activated."
            )
        }

        if (skillManager.getAvailableSkills().containsKey(normalizedPackageName) &&
            !skillVisibilityPreferences.isSkillVisibleToAi(normalizedPackageName)
        ) {
            return ToolResult(
                toolName = toolName,
                success = false,
                result = StringResultData(""),
                error = "Skill '$normalizedPackageName' is set to not show to AI"
            )
        }

        val text = usePackage(normalizedPackageName)
        return ToolResult(
            toolName = toolName,
            success = true,
            result = StringResultData(text)
        )
    }

    /**
     * 检查是否是已注册的MCP服务器
     *
     * @param serverName 服务器名称
     * @return 如果是已注册的MCP服务器则返回true
     */
    private fun isRegisteredMCPServer(serverName: String): Boolean {
        return mcpManager.isServerRegistered(serverName)
    }

    /**
     * 获取所有可用的MCP服务器包
     *
     * @return MCP服务器列表
     */
    fun getAvailableServerPackages(): Map<String, MCPServerConfig> {
        return mcpManager.getRegisteredServers()
    }

    // Helper function to determine if a package is an MCP server
    private fun isMCPServerPackage(toolPackage: ToolPackage): Boolean {
        // Check if any tool has MCP script placeholder
        return if (toolPackage.tools.isNotEmpty()) {
            val script = toolPackage.tools[0].script
            script.contains("/* MCPJS") // Check for MCP script marker
        } else {
            false
        }
    }

    /** Registers all tools in a package with the AIToolHandler */
    private fun registerPackageTools(toolPackage: ToolPackage) {
        val packageToolExecutor = PackageToolExecutor(toolPackage, context, this)
        val executableTools = toolPackage.tools.filter { !it.advice }
        val newToolNames = executableTools.map { packageTool -> "${toolPackage.name}:${packageTool.name}" }.toSet()
        val oldToolNames = activePackageToolNames[toolPackage.name] ?: emptySet()
        (oldToolNames - newToolNames).forEach { toolName ->
            aiToolHandler.unregisterTool(toolName)
        }
        activePackageToolNames[toolPackage.name] = newToolNames

        // Register each tool with the format packageName:toolName
        executableTools.forEach { packageTool ->
            val toolName = "${toolPackage.name}:${packageTool.name}"
            aiToolHandler.registerTool(toolName) { tool ->
                packageToolExecutor.invoke(tool)
            }
        }
    }

    private fun selectToolPackageState(toolPackage: ToolPackage): ToolPackage {
        if (toolPackage.states.isEmpty()) {
            activePackageStateIds.remove(toolPackage.name)
            return toolPackage
        }

        val capabilities = buildConditionCapabilitiesSnapshot()
        val selectedState = toolPackage.states.firstOrNull { state ->
            ConditionEvaluator.evaluate(state.condition, capabilities)
        }

        if (selectedState == null) {
            activePackageStateIds.remove(toolPackage.name)
            return toolPackage
        }

        activePackageStateIds[toolPackage.name] = selectedState.id

        val mergedTools = mergeToolsForState(toolPackage.tools, selectedState)
        return toolPackage.copy(tools = mergedTools)
    }

    private fun mergeToolsForState(baseTools: List<PackageTool>, state: ToolPackageState): List<PackageTool> {
        val toolMap = linkedMapOf<String, PackageTool>()
        if (state.inheritTools) {
            baseTools.forEach { toolMap[it.name] = it }
        }
        state.excludeTools.forEach { toolMap.remove(it) }
        state.tools.forEach { toolMap[it.name] = it }
        return toolMap.values.toList()
    }

    private fun buildConditionCapabilitiesSnapshot(): Map<String, Any?> {
        val level = try {
            androidPermissionPreferences.getPreferredPermissionLevel() ?: AndroidPermissionLevel.STANDARD
        } catch (_: Exception) {
            AndroidPermissionLevel.STANDARD
        }

        val shizukuAvailable = try {
            ShizukuAuthorizer.isShizukuServiceRunning() && ShizukuAuthorizer.hasShizukuPermission()
        } catch (_: Exception) {
            false
        }

        val experimentalEnabled = try {
            DisplayPreferencesManager.getInstance(context).isExperimentalVirtualDisplayEnabled()
        } catch (_: Exception) {
            true
        }

        val adbOrHigher = when (level) {
            AndroidPermissionLevel.DEBUGGER,
            AndroidPermissionLevel.ADMIN,
            AndroidPermissionLevel.ROOT -> true
            else -> false
        }

        val virtualDisplayCapable = adbOrHigher && experimentalEnabled && (level != AndroidPermissionLevel.DEBUGGER || shizukuAvailable)

        return mapOf(
            "ui.virtual_display" to virtualDisplayCapable,
            "android.permission_level" to level,
            "android.shizuku_available" to shizukuAvailable,
            "ui.shower_display" to (try { ShowerController.getDisplayId("default") != null } catch (_: Exception) { false })
        )
    }

    /** Generates a system prompt enhancement for the imported package */
    private fun generatePackageSystemPrompt(toolPackage: ToolPackage): String {
        val sb = StringBuilder()

        sb.appendLine("Using package: ${toolPackage.name}")
        sb.appendLine("Use Time: ${java.time.LocalDateTime.now()}")
        sb.appendLine("Description: ${toolPackage.description.resolve(context)}")
        sb.appendLine()
        sb.appendLine("Available tools in this package:")

        toolPackage.tools.forEach { tool ->
            val toolLabel =
                if (tool.advice) {
                    "- (advice): ${tool.description.resolve(context)}"
                } else {
                    "- ${toolPackage.name}:${tool.name}: ${tool.description.resolve(context)}"
                }
            sb.appendLine(toolLabel)
            if (tool.parameters.isNotEmpty()) {
                sb.appendLine("  Parameters:")
                tool.parameters.forEach { param ->
                    val requiredText = if (param.required) "(required)" else "(optional)"
                    sb.appendLine("  - ${param.name} ${requiredText}: ${param.description.resolve(context)}")
                }
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Gets a list of all available packages for discovery (the "market").
     *
     * By default this returns the in-memory cache to avoid re-scanning assets/external storage
     * on every call (which is expensive and can spam logs).
     *
     * @param forceRefresh Set to true to explicitly rescan package sources.
     * @return A map of package name to description
     */
    fun getAvailablePackages(forceRefresh: Boolean = false): Map<String, ToolPackage> {
        ensureInitialized()
        if (forceRefresh) {
            loadAvailablePackages()
        }
        return availablePackages
    }

    /**
     * Get a list of all imported packages
     * @return A list of imported package names
     */
    fun getImportedPackages(): List<String> {
        ensureInitialized()
        return getImportedPackagesInternal()
    }

    private fun getImportedPackagesInternal(): List<String> {
        val prefs = context.getSharedPreferences(PACKAGE_PREFS, Context.MODE_PRIVATE)
        val packagesJson = prefs.getString(IMPORTED_PACKAGES_KEY, "[]")
        return try {
            val jsonConfig = Json { ignoreUnknownKeys = true }
            val rawPackages = jsonConfig.decodeFromString<List<String>>(packagesJson ?: "[]")
            val normalizedPackages = normalizeImportedPackageNames(rawPackages)
            cleanupNonExistentPackages(normalizedPackages)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error decoding imported packages", e)
            emptyList()
        }
    }

    /**
     * 清理导入列表中不存在的包。
     * 自动移除那些已经被删除但仍然在导入列表中的包。
     */
    private fun cleanupNonExistentPackages(currentPackages: List<String>): List<String> {
        // Serialize cleanup with package reload to avoid transient map states
        // (e.g. during forceRefresh) causing accidental removal of valid imports.
        synchronized(initLock) {
            val normalizedPackages = normalizeImportedPackageNames(currentPackages)
            val cleanedPackages = normalizedPackages.filter { packageName ->
                availablePackages.containsKey(packageName)
            }

            if (cleanedPackages.size != currentPackages.size || cleanedPackages != currentPackages) {
                val removed = currentPackages.filter { !cleanedPackages.contains(it) }
                AppLogger.d(
                    TAG,
                    "Found ${removed.size} non-existent packages in imported list: $removed"
                )
                saveImportedPackages(cleanedPackages)
                AppLogger.d(TAG, "Cleaned up imported packages list. Removed: $removed")
            }

            val states = getToolPkgSubpackageStatesInternal()
            val cleanedStates =
                states.filterKeys { packageName ->
                    toolPkgSubpackageByPackageName.containsKey(packageName)
                }

            if (cleanedStates.size != states.size) {
                saveToolPkgSubpackageStates(cleanedStates)
            }

            return cleanedPackages
        }
    }

    /**
     * Get a list of all disabled packages
     * @return A list of disabled package names
     */
    fun getDisabledPackages(): List<String> {
        ensureInitialized()
        return getDisabledPackagesInternal()
    }

    private fun getDisabledPackagesInternal(): List<String> {
        val prefs = context.getSharedPreferences(PACKAGE_PREFS, Context.MODE_PRIVATE)
        val packagesJson = prefs.getString(DISABLED_PACKAGES_KEY, "[]")
        return try {
            val jsonConfig = Json { ignoreUnknownKeys = true }
            jsonConfig.decodeFromString<List<String>>(packagesJson ?: "[]")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error decoding disabled packages", e)
            emptyList()
        }
    }

    /** Helper to save disabled packages */
    private fun saveDisabledPackages(disabledPackages: List<String>) {
        val prefs = context.getSharedPreferences(PACKAGE_PREFS, Context.MODE_PRIVATE)
        val updatedJson = Json.encodeToString(disabledPackages)
        prefs.edit().putString(DISABLED_PACKAGES_KEY, updatedJson).apply()
    }

    private fun saveImportedPackages(importedPackages: List<String>) {
        val prefs = context.getSharedPreferences(PACKAGE_PREFS, Context.MODE_PRIVATE)
        val updatedJson = Json.encodeToString(importedPackages)
        prefs.edit().putString(IMPORTED_PACKAGES_KEY, updatedJson).apply()
    }

    private fun getToolPkgSubpackageStatesInternal(): Map<String, Boolean> {
        val prefs = context.getSharedPreferences(PACKAGE_PREFS, Context.MODE_PRIVATE)
        val statesJson = prefs.getString(TOOLPKG_SUBPACKAGE_STATES_KEY, "{}")
        return try {
            val jsonConfig = Json { ignoreUnknownKeys = true }
            val rawStates = jsonConfig.decodeFromString<Map<String, Boolean>>(statesJson ?: "{}")
            val normalizedStates = normalizeToolPkgSubpackageStates(rawStates)
            if (normalizedStates != rawStates) {
                saveToolPkgSubpackageStates(normalizedStates)
            }
            normalizedStates
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error decoding toolpkg subpackage states", e)
            emptyMap()
        }
    }

    private fun saveToolPkgSubpackageStates(states: Map<String, Boolean>) {
        val prefs = context.getSharedPreferences(PACKAGE_PREFS, Context.MODE_PRIVATE)
        val updatedJson = Json.encodeToString(states)
        prefs.edit().putString(TOOLPKG_SUBPACKAGE_STATES_KEY, updatedJson).apply()
    }

    private fun removeFromDisabledPackages(packageName: String) {
        val disabledPackages = getDisabledPackages().toMutableList()
        if (disabledPackages.remove(packageName)) {
            saveDisabledPackages(disabledPackages)
            AppLogger.d(TAG, "Removed package from disabled list: $packageName")
        }
    }

    private fun addToDisabledIfDefaultEnabled(packageName: String) {
        val toolPackage = availablePackages[packageName]
        if (toolPackage != null && toolPackage.isBuiltIn && toolPackage.enabledByDefault) {
            val disabledPackages = getDisabledPackages().toMutableList()
            if (!disabledPackages.contains(packageName)) {
                disabledPackages.add(packageName)
                saveDisabledPackages(disabledPackages)
                AppLogger.d(TAG, "Added default package to disabled list: $packageName")
            }
        }
    }

    private fun unregisterPackageTools(packageName: String) {
        val activeTools = activePackageToolNames.remove(packageName).orEmpty()
        activeTools.forEach { toolName ->
            aiToolHandler.unregisterTool(toolName)
        }
        activePackageStateIds.remove(packageName)
    }

    /**
     * Get the tools for a loaded package
     * @param packageName The name of the loaded package
     * @return The ToolPackage object or null if the package is not loaded
     */
    fun getPackageTools(packageName: String): ToolPackage? {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)
        return availablePackages[normalizedPackageName]
    }

    fun getEffectivePackageTools(packageName: String): ToolPackage? {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)
        val toolPackage = availablePackages[normalizedPackageName] ?: return null
        return selectToolPackageState(toolPackage)
    }

    /** Checks if a package is imported */
    fun isPackageImported(packageName: String): Boolean {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)
        val importedPackages = getImportedPackages()
        if (!importedPackages.contains(normalizedPackageName)) {
            return false
        }
        val subpackageRuntime = toolPkgSubpackageByPackageName[normalizedPackageName]
        if (subpackageRuntime != null) {
            return importedPackages.contains(subpackageRuntime.containerPackageName)
        }
        return true
    }

    /**
     * Remove an imported package.
     * For toolpkg containers this also disables/removes all internal subpackages.
     */
    fun removePackage(packageName: String): String {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)

        val currentPackages = LinkedHashSet(getImportedPackages())
        val subpackageStates = getToolPkgSubpackageStatesInternal().toMutableMap()
        var packageWasRemoved = false

        val containerRuntime = toolPkgContainers[normalizedPackageName]
        if (containerRuntime != null) {
            packageWasRemoved = currentPackages.remove(normalizedPackageName) || packageWasRemoved
            unregisterPackageTools(normalizedPackageName)

            containerRuntime.subpackages.forEach { subpackage ->
                packageWasRemoved = currentPackages.remove(subpackage.packageName) || packageWasRemoved
                unregisterPackageTools(subpackage.packageName)
            }

            saveImportedPackages(currentPackages.toList())
            saveToolPkgSubpackageStates(subpackageStates)
            addToDisabledIfDefaultEnabled(normalizedPackageName)

            return if (packageWasRemoved) {
                "Successfully disabled toolpkg container: $normalizedPackageName"
            } else {
                "Toolpkg container is already disabled: $normalizedPackageName"
            }
        }

        if (toolPkgSubpackageByPackageName.containsKey(normalizedPackageName)) {
            packageWasRemoved = currentPackages.remove(normalizedPackageName)
            subpackageStates[normalizedPackageName] = false
            unregisterPackageTools(normalizedPackageName)

            saveImportedPackages(currentPackages.toList())
            saveToolPkgSubpackageStates(subpackageStates)

            return if (packageWasRemoved) {
                "Successfully removed package: $normalizedPackageName"
            } else {
                "Package not found in imported list: $normalizedPackageName"
            }
        }

        packageWasRemoved = currentPackages.remove(normalizedPackageName)
        unregisterPackageTools(normalizedPackageName)
        addToDisabledIfDefaultEnabled(normalizedPackageName)

        return if (packageWasRemoved) {
            saveImportedPackages(currentPackages.toList())
            AppLogger.d(TAG, "Removed package from imported list: $normalizedPackageName")
            "Successfully removed package: $normalizedPackageName"
        } else {
            AppLogger.d(TAG, "Package not found in imported list: $normalizedPackageName")
            "Package not found in imported list: $normalizedPackageName"
        }
    }

    /**
     * Get the script content for a package by name
     * @param packageName The name of the package
     * @return The full JavaScript content of the package or null if not found
     */
    fun getPackageScript(packageName: String): String? {
        ensureInitialized()
        val normalizedPackageName = normalizePackageName(packageName)
        val toolPackage = availablePackages[normalizedPackageName] ?: return null

        // Load script based on whether it's built-in or external
        // All tools in a package share the same script, so we can get it from any tool
        return if (toolPackage.tools.isNotEmpty()) {
            toolPackage.tools[0].script
        } else {
            null
        }
    }

    /**
     * 使用MCP服务器
     *
     * @param serverName 服务器名称
     * @return 成功或失败的消息
     */
    fun useMCPServer(serverName: String): String {
        // 检查服务器是否已注册
        if (!mcpManager.isServerRegistered(serverName)) {
            return "MCP server '$serverName' does not exist or is not registered."
        }

        // 获取服务器配置
        val serverConfig =
            mcpManager.getRegisteredServers()[serverName]
                ?: return "Cannot get MCP server configuration: $serverName"

        // 创建MCP包
        val mcpPackage =
            MCPPackage.fromServer(context, serverConfig)
                ?: return "Cannot connect to MCP server: $serverName"

        // 转换为标准工具包
        val toolPackage = mcpPackage.toToolPackage()

        // 获取或创建MCP工具执行器
        val mcpToolExecutor = MCPToolExecutor(context, mcpManager)

        // 注册包中的每个工具 - 使用 serverName:toolName 格式
        toolPackage.tools.forEach { packageTool ->
            val toolName = "$serverName:${packageTool.name}"

            // 使用MCP特定的执行器注册工具
            aiToolHandler.registerTool(
                name = toolName,
                executor = mcpToolExecutor
            )

            AppLogger.d(TAG, "Registered MCP tool: $toolName")
        }

        return generateMCPSystemPrompt(toolPackage, serverName)
    }

    /** 为MCP服务器生成系统提示 */
    private fun generateMCPSystemPrompt(toolPackage: ToolPackage, serverName: String): String {
        val sb = StringBuilder()

        sb.appendLine("Using MCP server: $serverName")
        sb.appendLine("Time: ${java.time.LocalDateTime.now()}")
        sb.appendLine("Description: ${toolPackage.description.resolve(context)}")
        sb.appendLine()
        sb.appendLine("Available tools:")

        toolPackage.tools.forEach { tool ->
            // 使用 serverName:toolName 格式
            sb.appendLine("- $serverName:${tool.name}: ${tool.description.resolve(context)}")
            if (tool.parameters.isNotEmpty()) {
                sb.appendLine("  Parameters:")
                tool.parameters.forEach { param ->
                    val requiredText = if (param.required) "(required)" else "(optional)"
                    sb.appendLine("  - ${param.name} ${requiredText}: ${param.description.resolve(context)}")
                }
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Deletes a package file from external storage and removes it from the in-memory cache.
     * This action is permanent and cannot be undone.
     */
    fun deletePackage(packageName: String): Boolean {
        val normalizedPackageName = normalizePackageName(packageName)
        AppLogger.d(TAG, "Attempting to delete package: $normalizedPackageName")
        ensureInitialized()

        if (toolPkgSubpackageByPackageName.containsKey(normalizedPackageName)) {
            // Subpackage is part of a toolpkg archive; only remove enable state.
            removePackage(normalizedPackageName)
            return true
        }

        val packageFile = findPackageFile(normalizedPackageName)

        if (packageFile == null || !packageFile.exists()) {
            AppLogger.w(
                TAG,
                "Package file not found for deletion: $normalizedPackageName. It might be already deleted or never existed."
            )
            removePackage(normalizedPackageName)
            removeFromCachesAfterDelete(normalizedPackageName)
            return true
        }

        AppLogger.d(TAG, "Found package file to delete: ${packageFile.absolutePath}")

        val fileDeleted = packageFile.delete()

        if (fileDeleted) {
            AppLogger.d(TAG, "Successfully deleted package file: ${packageFile.absolutePath}")
            removePackage(normalizedPackageName)
            removeFromCachesAfterDelete(normalizedPackageName)
            AppLogger.d(TAG, "Package '$normalizedPackageName' fully deleted.")
            return true
        }

        AppLogger.e(TAG, "Failed to delete package file: ${packageFile.absolutePath}")
        return false
    }

    private fun removeFromCachesAfterDelete(packageName: String) {
        if (toolPkgContainers.containsKey(packageName)) {
            val container = toolPkgContainers.remove(packageName)
            availablePackages.remove(packageName)

            val states = getToolPkgSubpackageStatesInternal().toMutableMap()
            container?.subpackages?.forEach { subpackage ->
                availablePackages.remove(subpackage.packageName)
                toolPkgSubpackageByPackageName.remove(subpackage.packageName)
                states.remove(subpackage.packageName)
            }
            saveToolPkgSubpackageStates(states)
            return
        }

        availablePackages.remove(packageName)
        toolPkgSubpackageByPackageName.remove(packageName)
    }

    /**
     * Finds the File object for a given package name in external storage.
     */
    private fun findPackageFile(packageName: String): File? {
        val normalizedPackageName = normalizePackageName(packageName)
        val externalPackagesDir = File(context.getExternalFilesDir(null), PACKAGES_DIR)
        if (!externalPackagesDir.exists()) return null

        val containerRuntime = toolPkgContainers[normalizedPackageName]
        if (containerRuntime != null && containerRuntime.sourceType == ToolPkgSourceType.EXTERNAL) {
            val candidate = File(containerRuntime.sourcePath)
            if (candidate.exists()) {
                return candidate
            }
        }

        val jsFile = File(externalPackagesDir, "$normalizedPackageName.js")
        if (jsFile.exists()) return jsFile

        externalPackagesDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach

            if (file.name.endsWith(".js", ignoreCase = true)) {
                val loadedPackage = loadPackageFromJsFile(file)
                if (loadedPackage?.name == normalizedPackageName) {
                    return file
                }
            }

            if (file.name.endsWith(TOOLPKG_EXTENSION, ignoreCase = true)) {
                val loadedToolPkg = loadToolPkgFromExternalFile(file)
                if (loadedToolPkg?.containerPackage?.name == normalizedPackageName) {
                    return file
                }
            }
        }

        return null
    }

    /**
     * 将 ToolPackage 转换为 PackageToolPromptCategory
     * 用于生成结构化的包工具提示词
     *
     * @param toolPackage 要转换的工具包
     * @return PackageToolPromptCategory 对象
     */
    fun toPromptCategory(toolPackage: ToolPackage): PackageToolPromptCategory {
        val toolPrompts = toolPackage.tools.map { packageTool ->
            // 将 PackageTool 转换为 ToolPrompt
            val parametersString = if (packageTool.parameters.isNotEmpty()) {
                packageTool.parameters.joinToString(", ") { param ->
                    val required = if (param.required) "required" else "optional"
                    "${param.name} (${param.type}, $required)"
                }
            } else {
                ""
            }

            ToolPrompt(
                name = packageTool.name,
                description = packageTool.description.resolve(context),
                parameters = parametersString
            )
        }

        return PackageToolPromptCategory(
            packageName = toolPackage.name,
            packageDescription = toolPackage.description.resolve(context),
            tools = toolPrompts
        )
    }

    /**
     * 获取所有已导入包的提示词分类列表
     *
     * @return 已导入包的 PackageToolPromptCategory 列表
     */
    fun getImportedPackagesPromptCategories(): List<PackageToolPromptCategory> {
        ensureInitialized()
        val importedPackageNames = getImportedPackages()
        return importedPackageNames.mapNotNull { packageName ->
            getPackageTools(packageName)
                ?.takeIf { it.tools.isNotEmpty() }
                ?.let { toolPackage ->
                    toPromptCategory(toolPackage)
                }
        }
    }

    /** Clean up resources when the manager is no longer needed */
    fun destroy() {
        jsEngine.destroy()
        mcpManager.shutdown()
    }
}
