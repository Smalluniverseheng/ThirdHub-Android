package com.thirdhub.app.data

import com.thirdhub.app.R

/* 厂商官方品牌图标（Simple Icons 官方字形，CC0） */
object BrandIcons {

    fun resFor(vendorId: String): Int? = when (vendorId) {
        "openai" -> R.drawable.ic_brand_openai
        "deepseek" -> R.drawable.ic_brand_deepseek
        "moonshot" -> R.drawable.ic_brand_kimi
        "aliyun" -> R.drawable.ic_brand_qwen
        "openrouter" -> R.drawable.ic_brand_openrouter
        "anthropic" -> R.drawable.ic_brand_anthropic
        "google" -> R.drawable.ic_brand_google
        "meta" -> R.drawable.ic_brand_meta
        "mistral" -> R.drawable.ic_brand_mistralai
        "xiaomi" -> R.drawable.ic_brand_xiaomi
        "minimax" -> R.drawable.ic_brand_minimax
        "baidu" -> R.drawable.ic_brand_baidu
        "bytedance" -> R.drawable.ic_brand_bytedance
        "perplexity" -> R.drawable.ic_brand_perplexity
        else -> null
    }

    fun colorFor(vendorId: String): Int = when (vendorId) {
        "zhipu" -> 0xFF2B5CE6.toInt()
        "siliconflow" -> 0xFF6D28D9.toInt()
        "custom" -> 0xFF64748B.toInt()
        else -> 0xFF3B5BFD.toInt()
    }

    fun letterFor(vendorId: String): String = when (vendorId) {
        "zhipu" -> "GLM"
        "siliconflow" -> "SF"
        "custom" -> "…"
        else -> "AI"
    }
}
