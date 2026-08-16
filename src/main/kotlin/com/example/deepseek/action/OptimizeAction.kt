package com.example.deepseek.action

/**
 * "优化代码" Action。
 */
class OptimizeAction : BaseCodeAction() {
    override fun systemPrompt(): String =
        "你是一名代码优化专家。请优化下面这段代码，重点提升可读性、性能、健壮性，并说明改动了哪些地方以及为什么。输出优化后的完整代码。"
    override fun resultTitle(): String = "DeepSeek - 代码优化"
}
