package com.kymjs.ai.custard.ui.features.settings.viewmodels

import android.content.Context
import android.net.Uri
import com.kymjs.ai.custard.util.AppLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.data.model.CustomEmoji
import com.kymjs.ai.custard.data.preferences.CustomEmojiPreferences
import com.kymjs.ai.custard.data.repository.CustomEmojiRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 自定义表情管理 ViewModel
 * 
 * 管理自定义表情的UI状态和业务逻辑
 */
class CustomEmojiViewModel(context: Context) : ViewModel() {

    private val repository = CustomEmojiRepository.getInstance(context)
    private val TAG = "CustomEmojiViewModel"
    private val appContext = context.applicationContext

    // 当前选中的类别
    private val _selectedCategory = MutableStateFlow(CustomEmojiPreferences.BUILTIN_EMOTIONS.first())
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    // 所有类别（内置 + 自定义）
    val categories: StateFlow<List<String>> = repository.getAllCategories()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CustomEmojiPreferences.BUILTIN_EMOTIONS
        )

    // 当前类别的表情列表
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val emojisInCategory: StateFlow<List<CustomEmoji>> = _selectedCategory
        .flatMapLatest { category ->
            repository.getEmojisForCategory(category)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 加载状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 错误消息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 成功消息
    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    /**
     * 选择类别
     */
    fun selectCategory(category: String) {
        _selectedCategory.value = category
    }

    /**
     * 批量添加表情
     * 
     * @param category 类别名称
     * @param uris 图片URI列表
     */
    fun addEmojis(category: String, uris: List<Uri>) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _successMessage.value = null

            var successCount = 0
            var failCount = 0

            uris.forEach { uri ->
                val result = repository.addCustomEmoji(category, uri)
                if (result.isSuccess) {
                    successCount++
                } else {
                    failCount++
                    result.exceptionOrNull()?.let { ex ->
                        AppLogger.e(TAG, "Failed to add emoji from URI: $uri", ex)
                    } ?: AppLogger.e(TAG, "Failed to add emoji from URI: $uri")
                }
            }

            _isLoading.value = false

            if (successCount > 0) {
                _successMessage.value = if (failCount > 0) {
                    appContext.getString(R.string.emoji_added_partial, successCount, failCount)
                } else {
                    appContext.getString(R.string.emoji_added_success, successCount)
                }
            }
            if (failCount > 0 && successCount == 0) {
                _errorMessage.value = appContext.getString(R.string.emoji_add_failed)
            }
        }
    }

    /**
     * 删除表情
     * 
     * @param emojiId 表情ID
     */
    fun deleteEmoji(emojiId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _successMessage.value = null

            val result = repository.deleteCustomEmoji(emojiId)
            _isLoading.value = false

            if (result.isSuccess) {
                _successMessage.value = appContext.getString(R.string.emoji_deleted_success)
            } else {
                result.exceptionOrNull()?.let { ex ->
                    AppLogger.e(TAG, "Failed to delete emoji: $emojiId", ex)
                } ?: AppLogger.e(TAG, "Failed to delete emoji: $emojiId")
                _errorMessage.value = appContext.getString(R.string.emoji_delete_failed)
            }
        }
    }

    /**
     * 删除类别
     * 
     * @param category 类别名称
     */
    fun deleteCategory(category: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _successMessage.value = null

            val result = repository.deleteCategory(category)
            _isLoading.value = false

            if (result.isSuccess) {
                _successMessage.value = appContext.getString(R.string.category_deleted)
                // 切换到第一个内置类别
                _selectedCategory.value = CustomEmojiPreferences.BUILTIN_EMOTIONS.first()
            } else {
                result.exceptionOrNull()?.let { ex ->
                    AppLogger.e(TAG, "Failed to delete category: $category", ex)
                } ?: AppLogger.e(TAG, "Failed to delete category: $category")
                _errorMessage.value = appContext.getString(R.string.category_delete_failed)
            }
        }
    }

    /**
     * 创建新类别
     * 
     * @param categoryName 类别名称
     */
    suspend fun createCategory(categoryName: String): Boolean {
        if (!repository.isValidCategoryName(categoryName)) {
            _errorMessage.value = appContext.getString(R.string.invalid_category_name)
            return false
        }

        if (repository.categoryExists(categoryName)) {
            _errorMessage.value = appContext.getString(R.string.category_already_exists)
            return false
        }

        repository.addCategory(categoryName)

        // 切换到新创建的类别
        _selectedCategory.value = categoryName
        _successMessage.value = appContext.getString(R.string.category_created)
        return true
    }

    /**
     * 获取表情的URI（用于UI显示）
     */
    fun getEmojiUri(emoji: CustomEmoji): Uri {
        return repository.getEmojiUri(emoji)
    }

    /**
     * 检查类别是否为自定义类别
     * 允许删除内置类别
     */
    fun isCustomCategory(_category: String): Boolean {
        return true
    }

    /**
     * 清除错误消息
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /**
     * 清除成功消息
     */
    fun clearSuccessMessage() {
        _successMessage.value = null
    }

    /**
     * 重置为默认表情
     */
    fun resetToDefault() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _successMessage.value = null

            try {
                repository.resetToDefault()
                _isLoading.value = false
                _successMessage.value = appContext.getString(R.string.emoji_reset_success)
                // 切换到第一个内置类别
                _selectedCategory.value = CustomEmojiPreferences.BUILTIN_EMOTIONS.first()
            } catch (e: Exception) {
                _isLoading.value = false
                AppLogger.e(TAG, "Failed to reset emojis", e)
                _errorMessage.value = appContext.getString(R.string.emoji_reset_failed, e.message ?: "Unknown error")
            }
        }
    }
}

