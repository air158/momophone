package com.andforce.andclaw.agent

import com.andforce.andclaw.model.ApiConfig

object HistoryBudget {

    private const val RESPONSE_RESERVE_TOKENS = 2_000
    private const val MIN_HISTORY_TOKENS = 1_000
    private const val DEFAULT_WINDOW_TOKENS = 16_000

    fun estimateTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var ascii = 0
        var other = 0
        for (c in text) {
            if (c.code < 128) ascii++ else other++
        }
        return ascii / 4 + (other * 2 / 3) + 1
    }

    fun screenshotTokens(base64: String?): Int {
        if (base64.isNullOrEmpty()) return 0
        return base64.length / 4 + 500
    }

    fun contextWindowFor(config: ApiConfig): Int {
        config.contextWindow?.takeIf { it > 0 }?.let { return it }
        val m = config.model.lowercase()
        return when {
            m.contains("128k") -> 128_000
            m.contains("64k") -> 64_000
            m.contains("32k") -> 32_000
            m.contains("16k") -> 16_000
            m.contains("8k") -> 8_000
            m.startsWith("gpt-4o") -> 128_000
            m.startsWith("gpt-4") -> 8_000
            m.startsWith("gpt-3.5") -> 16_000
            m.startsWith("claude") -> 200_000
            m.startsWith("moonshot") -> 32_000
            m.startsWith("momo") -> 32_000
            else -> DEFAULT_WINDOW_TOKENS
        }
    }

    fun historyBudget(
        config: ApiConfig,
        systemPromptTokens: Int,
        screenDataTokens: Int,
        screenshotTokens: Int,
    ): Int {
        val window = contextWindowFor(config)
        val used = systemPromptTokens + screenDataTokens + screenshotTokens + RESPONSE_RESERVE_TOKENS
        return (window - used).coerceAtLeast(MIN_HISTORY_TOKENS)
    }
}
