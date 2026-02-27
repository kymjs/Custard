package com.ai.assistance.custard.data.collects

import com.ai.assistance.custard.data.model.ApiProviderType

data class ProviderEndpointOption(
    val endpoint: String,
    val label: String
)

data class ProviderApiConfig(
    val providerType: ApiProviderType,
    val defaultModelName: String = "",
    val defaultApiEndpoint: String = "",
    val endpointOptions: List<ProviderEndpointOption> = emptyList()
)

object ApiProviderConfigs {
    private val configs: Map<ApiProviderType, ProviderApiConfig> = listOf(
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI,
            defaultModelName = "gpt-4o",
            defaultApiEndpoint = "https://api.openai.com/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_RESPONSES,
            defaultModelName = "gpt-4o",
            defaultApiEndpoint = "https://api.openai.com/v1/responses"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENAI_GENERIC,
            defaultModelName = "",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ANTHROPIC,
            defaultModelName = "claude-3-opus-20240229",
            defaultApiEndpoint = "https://api.anthropic.com/v1/messages"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ANTHROPIC_GENERIC,
            defaultModelName = "",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.GOOGLE,
            defaultModelName = "gemini-2.0-flash",
            defaultApiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.GEMINI_GENERIC,
            defaultModelName = "gemini-2.0-flash",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.DEEPSEEK,
            defaultModelName = "deepseek-chat",
            defaultApiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.BAIDU,
            defaultModelName = "ernie-bot-4",
            defaultApiEndpoint = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ALIYUN,
            defaultModelName = "qwen-max",
            defaultApiEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.XUNFEI,
            defaultModelName = "spark3.5",
            defaultApiEndpoint = "https://spark-api-open.xf-yun.com/v2/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ZHIPU,
            defaultModelName = "glm-4.5",
            defaultApiEndpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                    label = "CN standard"
                ),
                ProviderEndpointOption(
                    endpoint = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions",
                    label = "CN coding"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.z.ai/api/paas/v4/chat/completions",
                    label = "International standard"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.z.ai/api/coding/paas/v4/chat/completions",
                    label = "International coding"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.BAICHUAN,
            defaultModelName = "baichuan4",
            defaultApiEndpoint = "https://api.baichuan-ai.com/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MOONSHOT,
            defaultModelName = "moonshot-v1-128k",
            defaultApiEndpoint = "https://api.moonshot.cn/v1/chat/completions",
            endpointOptions = listOf(
                ProviderEndpointOption(
                    endpoint = "https://api.moonshot.cn/v1/chat/completions",
                    label = "China (moonshot.cn)"
                ),
                ProviderEndpointOption(
                    endpoint = "https://api.moonshot.ai/v1/chat/completions",
                    label = "International (moonshot.ai)"
                )
            )
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MISTRAL,
            defaultModelName = "codestral-latest",
            defaultApiEndpoint = "https://codestral.mistral.ai/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.SILICONFLOW,
            defaultModelName = "yi-1.5-34b",
            defaultApiEndpoint = "https://api.siliconflow.cn/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OPENROUTER,
            defaultModelName = "google/gemini-pro",
            defaultApiEndpoint = "https://openrouter.ai/api/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.INFINIAI,
            defaultModelName = "infini-mini",
            defaultApiEndpoint = "https://cloud.infini-ai.com/maas/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.ALIPAY_BAILING,
            defaultModelName = "Ling-1T",
            defaultApiEndpoint = "https://api.tbox.cn/api/llm/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.DOUBAO,
            defaultModelName = "Doubao-pro-4k",
            defaultApiEndpoint = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.LMSTUDIO,
            defaultModelName = "meta-llama-3.1-8b-instruct",
            defaultApiEndpoint = "http://localhost:1234/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.MNN,
            defaultModelName = "",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.LLAMA_CPP,
            defaultModelName = "",
            defaultApiEndpoint = ""
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.PPINFRA,
            defaultModelName = "gpt-4o-mini",
            defaultApiEndpoint = "https://api.ppinfra.com/openai/v1/chat/completions"
        ),
        ProviderApiConfig(
            providerType = ApiProviderType.OTHER,
            defaultModelName = "",
            defaultApiEndpoint = ""
        )
    ).associateBy(ProviderApiConfig::providerType)

    fun get(providerType: ApiProviderType): ProviderApiConfig {
        return configs[providerType] ?: ProviderApiConfig(providerType = providerType)
    }

    fun getDefaultModelName(providerType: ApiProviderType): String {
        return get(providerType).defaultModelName
    }

    fun getDefaultApiEndpoint(providerType: ApiProviderType): String {
        return get(providerType).defaultApiEndpoint
    }

    fun getEndpointOptions(providerType: ApiProviderType): List<ProviderEndpointOption>? {
        return get(providerType).endpointOptions.takeIf { it.isNotEmpty() }
    }

    fun isDefaultModelName(modelName: String): Boolean {
        return configs.values.any { it.defaultModelName == modelName }
    }

    fun isDefaultApiEndpoint(endpoint: String): Boolean {
        return configs.values.any { it.defaultApiEndpoint == endpoint }
    }
}
