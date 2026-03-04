package com.pokkerolli.codeagent.presentation.chat

import java.util.Locale

object TokenPricing {
    const val MAX_CONTEXT_LENGTH = 128_000

    const val PRICE_PER_1M_INPUT_TOKENS_CACHE_HIT_USD = 0.028
    const val PRICE_PER_1M_INPUT_TOKENS_CACHE_MISS_USD = 0.28
    const val PRICE_PER_1M_OUTPUT_TOKENS_USD = 0.42

    private const val TOKENS_PRICE_DIVISOR = 1_000_000.0

    fun inputCostCacheHitUsd(tokens: Int): Double {
        return tokens * PRICE_PER_1M_INPUT_TOKENS_CACHE_HIT_USD / TOKENS_PRICE_DIVISOR
    }

    fun inputCostCacheMissUsd(tokens: Int): Double {
        return tokens * PRICE_PER_1M_INPUT_TOKENS_CACHE_MISS_USD / TOKENS_PRICE_DIVISOR
    }

    fun outputCostUsd(tokens: Int): Double {
        return tokens * PRICE_PER_1M_OUTPUT_TOKENS_USD / TOKENS_PRICE_DIVISOR
    }

    fun formatTokens(tokens: Int): String {
        return String.format(Locale.US, "%,d", tokens)
    }

    fun formatUsd(value: Double): String {
        return String.format(Locale.US, "$%.6f", value)
    }
}
