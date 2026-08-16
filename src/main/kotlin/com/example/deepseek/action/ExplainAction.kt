package com.example.deepseek.action

/**
 * "解释代码" Action。
 */
class ExplainAction : BaseCodeAction() {
    override fun systemPrompt(): String =
        "你是一名资深程序员。请用简洁清晰的中文解释下面这段代码的作用、核心逻辑和关键点。"
    override fun resultTitle(): String = "DeepSeek - 代码解释"
}
