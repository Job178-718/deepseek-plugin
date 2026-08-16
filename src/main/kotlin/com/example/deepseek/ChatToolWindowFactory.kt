package com.example.deepseek

import com.example.deepseek.service.DeepSeekClient
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import java.util.concurrent.atomic.AtomicLong

/**
 * 侧边栏聊天工具窗口工厂。
 */
class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, "DeepSeek", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun init(toolWindow: ToolWindow) {
        toolWindow.component.preferredSize = Dimension(440, 660)
    }
}

/**
 * 聊天面板：卡片式对话流。
 * 每条消息（用户问题 / AI 回答）各占一整行，做成圆角卡片，内容完整展开不截断。
 */
class ChatPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private enum class Role { USER, ASSISTANT }

    private data class ChatMsg(val role: Role, val content: String)

    private val LOG = Logger.getInstance(ChatPanel::class.java)

    private val messages = mutableListOf<ChatMsg>()

    private val messagesContainer = JPanel()
    private val scrollPane = JBScrollPane(messagesContainer)
    private val inputField = EditorTextField()
    private val sendButton = RoundSendButton()

    /** 卡片可用宽度，随窗口缩放更新 */
    private var availableWidth = 380

    // 流式输出缓冲与节流（后台线程累积，EDT 按 80ms 节流刷新）
    private val streamBuf = StringBuilder()
    private val lastStreamFlush = AtomicLong(0)

    private val sendAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            send()
        }
    }

    init {
        layout = BorderLayout()

        // ── 顶部标题栏 ──
        val header = buildHeader()

        // ── 中部消息区（卡片流）──
        messagesContainer.layout = BoxLayout(messagesContainer, BoxLayout.Y_AXIS)
        messagesContainer.isOpaque = true
        messagesContainer.background = JBColor(Color(0xFFFFFF), Color(0x1E1F22))
        scrollPane.border = null
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER

        // 跟随窗口宽度更新卡片可用宽度
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val newWidth = scrollPane.viewport.width - 28
                if (newWidth != availableWidth) {
                    availableWidth = newWidth.coerceAtLeast(120)
                    reflowAll()
                }
            }
        })

        showWelcome()

        // ── 底部输入区 ──
        inputField.setOneLineMode(false)
        inputField.setPlaceholder("Ctrl+Enter 发送")
        inputField.preferredSize = Dimension(400, 60)

        val actionManager = ActionManager.getInstance()
        if (actionManager.getAction(SEND_ACTION_ID) == null) {
            actionManager.registerAction(SEND_ACTION_ID, sendAction)
        }
        inputField.addSettingsProvider { _ ->
            sendAction.registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)),
                inputField
            )
        }

        sendButton.addActionListener { send() }

        val inputWrap = RoundedPanel(BorderLayout(), 12)
        inputWrap.isOpaque = true
        inputWrap.background = JBColor(Color(0xF7F8FA), Color(0x2B2D30))
        inputWrap.add(inputField, BorderLayout.CENTER)
        val sendWrap = JPanel(BorderLayout())
        sendWrap.isOpaque = false
        sendWrap.border = JBUI.Borders.empty(0, 8, 0, 0)
        sendWrap.add(sendButton, BorderLayout.SOUTH)
        inputWrap.add(sendWrap, BorderLayout.EAST)
        inputWrap.border = JBUI.Borders.empty(10, 12)

        val bottom = JPanel(BorderLayout())
        bottom.isOpaque = true
        bottom.background = JBColor(Color(0xF7F8FA), Color(0x2B2D30))
        bottom.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0),
            JBUI.Borders.empty(10, 10, 10, 10)
        )
        bottom.add(inputWrap, BorderLayout.CENTER)

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
    }

    private fun buildHeader(): JPanel {
        val header = JPanel(BorderLayout())
        header.isOpaque = true
        header.background = JBColor(Color(0xF7F8FA), Color(0x2B2D30))

        val title = JBLabel("DeepSeek", SwingConstants.LEFT)
        title.font = JBUI.Fonts.label().deriveFont(Font.BOLD, 14f)

        val clearBtn = LinkButton("清空会话")
        clearBtn.addActionListener {
            messages.clear()
            messagesContainer.removeAll()
            messagesContainer.revalidate()
            messagesContainer.repaint()
            showWelcome()
        }

        header.add(title, BorderLayout.WEST)
        header.add(clearBtn, BorderLayout.EAST)
        header.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(12, 14)
        )
        return header
    }

    private fun showWelcome() {
        messages.add(
            ChatMsg(Role.ASSISTANT, "你好！我是 DeepSeek 助手。\n\n输入问题，按 **Ctrl+Enter** 或点击「发送」开始对话。")
        )
        rebuildCards()
    }

    /** 追加一条消息卡片，返回卡片对象以便后续流式更新内容 */
    private fun appendCard(role: Role, content: String): MessageCard {
        val card = MessageCard(role, content)
        card.alignmentX = Component.LEFT_ALIGNMENT
        card.maximumSize = Dimension(Integer.MAX_VALUE, card.preferredSize.height)
        messagesContainer.add(card)
        messagesContainer.add(Box.createVerticalStrut(8))
        reflowAll()
        scrollToBottom()
        return card
    }

    /** 全部重绘（清空后重建） */
    private fun rebuildCards() {
        messagesContainer.removeAll()
        messages.forEach { m ->
            val card = MessageCard(m.role, m.content)
            card.alignmentX = Component.LEFT_ALIGNMENT
            card.maximumSize = Dimension(Integer.MAX_VALUE, card.preferredSize.height)
            messagesContainer.add(card)
            messagesContainer.add(Box.createVerticalStrut(8))
        }
        reflowAll()
    }

    private fun reflowAll() {
        messagesContainer.revalidate()
        messagesContainer.repaint()
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val sb = scrollPane.verticalScrollBar
            sb.value = sb.maximum
        }
    }

    /** 发送用户输入到 DeepSeek，并异步更新结果（AI 回复走流式，边生成边显示） */
    private fun send() {
        val text = inputField.text.trim()
        if (text.isEmpty()) return

        messages.add(ChatMsg(Role.USER, text))
        inputField.text = ""
        appendCard(Role.USER, text)

        val apiKey = DeepSeekClient.getApiKey()
        if (apiKey.isNullOrBlank()) {
            val tip = "⚠️ 未设置 DEEPSEEK_API_KEY 环境变量。请设置后重启 Android Studio。"
            messages.add(ChatMsg(Role.ASSISTANT, tip))
            appendCard(Role.ASSISTANT, tip)
            return
        }

        sendButton.isEnabled = false

        // 先放一张空的 AI 卡片，流式过程中局部追加文本，生成完成后才渲染 Markdown
        val aiCard = appendCard(Role.ASSISTANT, "")
        streamBuf.setLength(0)
        lastStreamFlush.set(0)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val reply = DeepSeekClient.chatStream(toApiMessages()) { delta ->
                    synchronized(streamBuf) { streamBuf.append(delta) }
                    // 节流：80ms 内只刷一次
                    val now = System.currentTimeMillis()
                    if (now - lastStreamFlush.get() >= 80) {
                        lastStreamFlush.set(now)
                        ApplicationManager.getApplication().invokeLater {
                            val partial = synchronized(streamBuf) { streamBuf.toString() }
                            aiCard.appendPlain(partial, markdown = false)
                        }
                    }
                }
                ApplicationManager.getApplication().invokeLater {
                    messages.add(ChatMsg(Role.ASSISTANT, reply))
                    aiCard.setMarkdownContent(reply)
                    sendButton.isEnabled = true
                }
            } catch (t: Throwable) {
                LOG.error("DeepSeek 聊天请求失败", t)
                ApplicationManager.getApplication().invokeLater {
                    val msg = "❌ 请求失败：${t.message}"
                    messages.add(ChatMsg(Role.ASSISTANT, msg))
                    aiCard.setMarkdownContent(msg)
                    sendButton.isEnabled = true
                }
            }
        }
    }

    /** 将内部消息模型转换为 API 所需的 Pair(role, content) */
    private fun toApiMessages(): List<Pair<String, String>> =
        messages.map { m ->
            val role = if (m.role == Role.USER) "user" else "assistant"
            role to m.content
        }

    /**
     * 单条消息卡片：圆角背景 + 左上角标签 + 内容（完整展开）。
     * 流式生成期间用 appendPlain 纯文本局部追加（不重解析、不闪），
     * 生成完成后用 setMarkdownContent 一次性渲染 Markdown。
     */
    private inner class MessageCard(private val role: Role, private var content: String) : JPanel(BorderLayout()) {

        private val tagLabel = JBLabel()
        private val textPane = JTextPane()

        // 记录当前 textPane 内容：null=未初始化/纯文本模式；Markdown 字符串=已渲染模式
        private var renderedMarkdown: String? = null

        init {
            isOpaque = false
            layout = BorderLayout()

            tagLabel.text = if (role == Role.USER) "你" else "DeepSeek"
            tagLabel.font = JBUI.Fonts.label(11f).deriveFont(Font.BOLD)
            tagLabel.foreground = if (role == Role.USER) {
                JBColor(Color(0x3574F0), Color(0x8AB4F8))
            } else {
                JBColor(Color(0x5F6368), Color(0x9AA0A6))
            }
            tagLabel.border = JBUI.Borders.empty(6, 12, 2, 12)

            textPane.isEditable = false
            textPane.isOpaque = false
            textPane.contentType = "text/html"
            textPane.border = JBUI.Borders.empty(0, 12, 10, 12)
            renderText()

            add(tagLabel, BorderLayout.NORTH)
            add(textPane, BorderLayout.CENTER)
        }

        /** 纯文本局部追加（流式生成期间用，不触发 Markdown 重解析，避免闪烁） */
        fun appendPlain(text: String, markdown: Boolean) {
            if (markdown) {
                setMarkdownContent(text)
                return
            }
            val doc = textPane.document
            try {
                // 纯文本模式：把 html 里的 <br/> 还原成换行追加
                doc.insertString(doc.length, escapeHtml(text).replace("\n", "<br/>"), null)
                textPane.caretPosition = doc.length
            } catch (_: Exception) {
            }
            reflowAfterUpdate()
        }

        /** 生成完成后：一次性渲染完整 Markdown */
        fun setMarkdownContent(md: String) {
            content = md
            renderedMarkdown = md
            textPane.text = if (role == Role.USER) buildUserHtml(md) else buildAssistantHtml(md)
            reflowAfterUpdate()
        }

        /** 更新内容（兼容旧调用） */
        fun updateContent(newContent: String) {
            setMarkdownContent(newContent)
        }

        private fun reflowAfterUpdate() {
            preferredSize = computePreferredSize()
            maximumSize = Dimension(Integer.MAX_VALUE, preferredSize.height)
            revalidate()
            repaint()
            reflowAll()
            scrollToBottom()
        }

        private fun renderText() {
            val display = if (content.isBlank()) {
                "<i>思考中…</i>"
            } else {
                if (role == Role.USER) buildUserHtml(content) else buildAssistantHtml(content)
            }
            textPane.text = display
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = cardBackground()
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.dispose()
            super.paintComponent(g)
        }

        private fun cardBackground(): Color =
            if (role == Role.USER) {
                JBColor(Color(0xE8F0FE), Color(0x2E4A6B))
            } else {
                JBColor(Color(0xF5F6F7), Color(0x2B2D30))
            }

        override fun getPreferredSize(): Dimension = computePreferredSize()

        /**
         * 精确计算卡片所需高度：用 JTextPane 的根 view 在目标宽度下测量内容真实高度。
         * 这样渲染后（换行/代码块/列表增多）高度能正确撑开。
         */
        private fun computePreferredSize(): Dimension {
            val textW = (availableWidth - 24).coerceAtLeast(40)
            // 标准 view 测量法：给 view 目标宽度 + 无限高度，取 Y 轴真实跨度
            val rootView = textPane.ui.getRootView(textPane)
            rootView.setSize(textW.toFloat(), Integer.MAX_VALUE.toFloat())
            val contentH = rootView.getPreferredSpan(javax.swing.text.View.Y_AXIS).toInt()
            val tagH = tagLabel.preferredSize.height + 2
            val height = tagH + contentH + 12
            return Dimension(availableWidth, height)
        }
    }

    private fun buildUserHtml(text: String): String {
        val fg = hex(JBColor(Color(0x202124), Color(0xE8EAED)))
        return "<html><body style='margin:0'>" +
            "<font color='$fg'>${escapeHtml(text).replace("\n", "<br/>")}</font>" +
            "</body></html>"
    }

    private fun buildAssistantHtml(md: String): String {
        val bodyHtml = postProcessHtml(renderMarkdown(md))
        val fg = hex(JBColor(Color(0x202124), Color(0xE8EAED)))
        val codeBg = hex(JBColor(Color(0xF6F8FA), Color(0x2D3038)))
        val codeFg = hex(JBColor(Color(0x24292F), Color(0xE8EAED)))
        val border = hex(JBColor(Color(0xD0D7DE), Color(0x3C3F41)))
        val link = hex(JBColor(Color(0x0969DA), Color(0x8AB4F8)))

        // 注意：Swing HTMLEditorKit 只支持 HTML 3.2 的极窄 CSS 子集，
        // 不能使用 line-height / border-radius / overflow-x / border-collapse 等现代属性。
        return """
            <html><head><style>
            body { color: $fg; font-family: Dialog; font-size: 13px; }
            p { margin: 4px 0; }
            h1,h2,h3,h4 { font-weight: bold; margin: 10px 0 4px; }
            h1 { font-size: 18px; }
            h2 { font-size: 16px; }
            h3 { font-size: 15px; }
            h4 { font-size: 14px; }
            ul, ol { margin: 4px 0; }
            li { margin: 2px 0; }
            code { font-family: Monospaced; background: $codeBg; color: $codeFg; font-size: 12px; }
            pre { background: $codeBg; padding: 10px; margin: 6px 0; }
            pre code { background: transparent; }
            table { margin: 6px 0; }
            th, td { border: 1px solid $border; padding: 5px 9px; }
            th { font-weight: bold; background: $codeBg; }
            blockquote { margin: 6px 0; color: #808080; }
            a { color: $link; }
            hr { margin: 8px 0; }
            </style></head><body>$bodyHtml</body></html>
        """.trimIndent()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Swing HTMLEditorKit 对 <ol>/<ul> 列表支持很差（不显示编号、挤成一团），
     * 这里把 commonmark 渲染出的列表手动转成显式编号 + 换行：
     * <ol><li>a</li><li>b</li></ol>  →  1. a<br/>2. b<br/>
     * <ul><li>a</li><li>b</li></ul>  →  • a<br/>• b<br/>
     */
    private fun postProcessHtml(html: String): String {
        var out = html
        // 有序列表：逐项编号
        out = Regex("<ol[^>]*>([\\s\\S]*?)</ol>").replace(out) { m ->
            val items = Regex("<li[^>]*>([\\s\\S]*?)</li>").findAll(m.groupValues[1])
                .map { it.groupValues[1].trim() }
                .toList()
            if (items.isEmpty()) ""
            else items.mapIndexed { i, it -> "<p style='margin:2px 0'>${i + 1}. $it</p>" }.joinToString("")
        }
        // 无序列表：圆点前缀
        out = Regex("<ul[^>]*>([\\s\\S]*?)</ul>").replace(out) { m ->
            val items = Regex("<li[^>]*>([\\s\\S]*?)</li>").findAll(m.groupValues[1])
                .map { it.groupValues[1].trim() }
                .toList()
            if (items.isEmpty()) ""
            else items.joinToString("") { "<p style='margin:2px 0'>• $it</p>" }
        }
        return out
    }

    companion object {
        private const val SEND_ACTION_ID = "com.example.deepseek.Chat.Send"

        private val ACCENT = JBColor(Color(0x3574F0), Color(0x3574F0))

        /** 将 Color 转为 CSS 可用的 #RRGGBB 字符串 */
        private fun hex(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

        // ── Markdown 渲染 ──
        private val mdParser: Parser = Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .build()

        private val mdRenderer: HtmlRenderer = HtmlRenderer.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .build()

        private fun renderMarkdown(md: String): String = mdRenderer.render(mdParser.parse(md))
    }

    /** 圆角容器（输入框用） */
    private class RoundedPanel(layout: java.awt.LayoutManager, private val radius: Int) : JPanel(layout) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = background
            g2.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
            g2.dispose()
        }
    }

    /** 链接样式按钮 */
    private class LinkButton(text: String) : JButton(text) {
        init {
            isContentAreaFilled = false
            isFocusPainted = false
            isBorderPainted = false
            foreground = ACCENT
            font = font.deriveFont(Font.PLAIN, 12f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    /** 圆形发送按钮（带向上箭头） */
    private class RoundSendButton : JButton() {
        init {
            isContentAreaFilled = false
            isFocusPainted = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(34, 34)
            minimumSize = Dimension(34, 34)
            maximumSize = Dimension(34, 34)
            toolTipText = "发送 (Ctrl+Enter)"
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = repaint()
                override fun mouseExited(e: MouseEvent) = repaint()
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val d = 34
            val bg = when {
                model.isPressed -> ACCENT.darker()
                !isEnabled -> JBColor(Color(0xC0C4CC), Color(0x5F6368))
                else -> ACCENT
            }
            val cx = (width - d) / 2
            val cy = (height - d) / 2
            g2.color = bg
            g2.fillOval(cx, cy, d, d)

            g2.color = Color.WHITE
            g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val ax = width / 2
            val yTop = cy + 9
            val yBottom = cy + d - 9
            g2.drawLine(ax, yBottom, ax, yTop)
            g2.drawLine(ax, yTop, ax - 4, yTop + 4)
            g2.drawLine(ax, yTop, ax + 4, yTop + 4)
            g2.dispose()
        }
    }
}
