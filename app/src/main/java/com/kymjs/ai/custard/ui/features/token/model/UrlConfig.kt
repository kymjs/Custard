package com.kymjs.ai.custard.ui.features.token.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.kymjs.ai.custard.R
import com.kymjs.ai.custard.core.application.CustardApplication
import kotlinx.serialization.Serializable

@Serializable
data class TabConfig(
    val title: String,
    val url: String
)

@Serializable
data class UrlConfig(
    val name: String = "MiniMax",
    val signInUrl: String = "https://platform.minimaxi.com/subscribe/coding-plan?code=GnTlr4ouNf&source=link",
    val tabs: List<TabConfig> = listOf(
        TabConfig(CustardApplication.instance.getString(R.string.url_config_api_key), "https://platform.minimaxi.com/user-center/payment/coding-plan"),
        TabConfig(CustardApplication.instance.getString(R.string.url_config_top_up), "https://platform.minimaxi.com/user-center/payment/balance"),
        TabConfig(CustardApplication.instance.getString(R.string.url_config_profile), "https://platform.minimaxi.com/user-center/basic-information")
    )
)

// 导航目标数据类
data class NavDestination(
    val title: String, 
    val url: String, 
    val icon: ImageVector
)

// 获取导航目标的图标
fun getIconForIndex(index: Int): ImageVector = when (index) {
    0 -> Icons.Default.Key
    2 -> Icons.Default.CreditCard
    3 -> Icons.Default.Person
    else -> Icons.Default.Key
} 