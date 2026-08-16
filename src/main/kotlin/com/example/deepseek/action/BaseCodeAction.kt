package com.example.deepseek.action

import com.example.deepseek.service.DeepSeekClient
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import javax.swing.JTextArea

/**
 * 基于"选中代码"调用 DeepSeek 的 Action 基类。
 *
 * 子类只需提供：systemPrompt()（系统提示词）和 resultTitle()（结果标题）。
 */
abstract class BaseCodeAction : AnAction() {

    private val LOG = Logger.getInstance(BaseCodeAction::class.java)

    /** 要发给 DeepSeek 的系统提示词，定义"解释/优化/注释"等任务 */
    abstract fun systemPrompt(): String

    /** 结果弹窗标题 */
    abstract fun resultTitle(): String

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText

        if (selectedText.isNullOrBlank()) {
            Messages.showInfoMessage(project, "请先选中一段代码，再使用该操作。", resultTitle())
            return
        }

        if (DeepSeekClient.getApiKey().isNullOrBlank()) {
            Messages.showWarningDialog(
                project,
                "未找到 DEEPSEEK_API_KEY 环境变量。\n请设置后重启 Android Studio。",
                resultTitle()
            )
            return
        }

        // 异步调用，避免阻塞 UI 线程
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val reply = DeepSeekClient.chat(
                    listOf("system" to systemPrompt(), "user" to selectedText)
                )
                ApplicationManager.getApplication().invokeLater {
                    showResult(project, reply)
                }
            } catch (t: Throwable) {
                LOG.error("DeepSeek 请求失败", t)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "请求失败：${t.message}", resultTitle())
                }
            }
        }
    }

    /** 用可滚动的对话框展示结果 */
    private fun showResult(project: Project, result: String) {
        val textArea = JTextArea(result).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val scroll = JBScrollPane(textArea).apply {
            preferredSize = Dimension(760, 560)
        }

        val dialog = object : DialogWrapper(project, true) {
            init {
                title = resultTitle()
                setOKButtonText("关闭")
                init()
            }

            override fun createCenterPanel() = scroll
        }
        dialog.show()
    }
}
