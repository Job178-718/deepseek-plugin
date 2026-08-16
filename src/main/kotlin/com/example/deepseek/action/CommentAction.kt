package com.example.deepseek.action

/**
 * "添加注释" Action。
 */
class CommentAction : BaseCodeAction() {
    override fun systemPrompt(): String =
        "你是一名代码注释专家。请为下面这段代码添加清晰、准确的中文注释（保留原代码不变，只加注释），遵循项目惯用风格。"
    override fun resultTitle(): String = "DeepSeek - 添加注释"
}
