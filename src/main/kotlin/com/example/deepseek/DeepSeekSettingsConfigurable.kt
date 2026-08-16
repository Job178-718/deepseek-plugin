package com.example.deepseek

import com.example.deepseek.service.DeepSeekClient
import com.intellij.openapi.options.Configurable
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * 设置页：展示当前 API Key 状态和使用的模型。
 * API Key 本身通过环境变量 DEEPSEEK_API_KEY 提供，这里只读展示。
 */
class DeepSeekSettingsConfigurable : Configurable {

    private var rootPanel: JPanel? = null

    override fun getDisplayName(): String = "DeepSeek Assistant"

    override fun createComponent(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // API Key 状态
        val key = DeepSeekClient.getApiKey()
        val keyLabel = JLabel(
            if (key.isNullOrBlank()) "API Key：❌ 未设置（环境变量 DEEPSEEK_API_KEY）"
            else "API Key：✅ 已设置（${mask(key)}）"
        )
        panel.add(keyLabel)
        panel.add(Box.createVerticalStrut(8))

        // 当前模型
        panel.add(JLabel("当前模型：${DeepSeekClient.getModel()}"))
        panel.add(Box.createVerticalStrut(16))

        // 说明
        val help = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = null
            text = """
                如何设置 API Key：
                1. Windows：setx DEEPSEEK_API_KEY "sk-你的Key"
                2. 重启 Android Studio 生效
                3. 可选：用环境变量 DEEPSEEK_MODEL 指定模型（默认 deepseek-chat）
            """.trimIndent()
        }
        panel.add(help)

        rootPanel = panel
        return panel
    }

    override fun isModified(): Boolean = false
    override fun apply() {}
    override fun reset() {}

    private fun mask(key: String): String {
        return if (key.length <= 8) "****" else "${key.take(4)}...${key.takeLast(4)}"
    }
}
