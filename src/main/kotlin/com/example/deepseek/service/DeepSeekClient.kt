package com.example.deepseek.service

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * DeepSeek API 客户端。
 *
 * 使用 JDK 自带的 java.net.http.HttpClient + IDE 自带的 Gson，零额外依赖。
 * API Key 从环境变量 DEEPSEEK_API_KEY 读取。
 */
object DeepSeekClient {

    private val LOG = Logger.getInstance(DeepSeekClient::class.java)

    private const val BASE_URL = "https://api.deepseek.com"
    private const val CHAT_ENDPOINT = "$BASE_URL/chat/completions"

    // 可通过环境变量 DEEPSEEK_MODEL 覆盖默认模型
    private const val DEFAULT_MODEL = "deepseek-chat"

    private val gson = Gson()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    /** 从环境变量读取 API Key */
    fun getApiKey(): String? = System.getenv("DEEPSEEK_API_KEY")

    fun getModel(): String =
        System.getenv("DEEPSEEK_MODEL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /**
     * 调用 DeepSeek 对话接口。
     *
     * @param messages 消息列表，每项为 Pair(role, content)
     * @return 模型回复文本
     */
    @Throws(Exception::class)
    fun chat(messages: List<Pair<String, String>>): String {
        val apiKey = getApiKey()
            ?: throw IllegalStateException(
                "未找到 DEEPSEEK_API_KEY 环境变量。\n请在系统环境变量中设置后重启 Android Studio。"
            )

        val requestBody = buildRequestBody(messages)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(CHAT_ENDPOINT))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw IllegalStateException(buildError(response.statusCode(), response.body()))
        }

        return parseResponse(response.body())
    }

    /**
     * 流式调用 DeepSeek 对话接口（SSE）。
     * 每收到一个增量内容块就回调一次 [onDelta]，适合长回答边生成边显示。
     *
     * @return 完整的回复文本
     */
    @Throws(Exception::class)
    fun chatStream(messages: List<Pair<String, String>>, onDelta: (String) -> Unit): String {
        val apiKey = getApiKey()
            ?: throw IllegalStateException(
                "未找到 DEEPSEEK_API_KEY 环境变量。\n请在系统环境变量中设置后重启 Android Studio。"
            )

        val requestBody = buildRequestBody(messages, stream = true)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(CHAT_ENDPOINT))
            .timeout(Duration.ofSeconds(300))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines())

        if (response.statusCode() != 200) {
            val errorBody = response.body().use { lines ->
                lines.collect(java.util.stream.Collectors.joining("\n"))
            }
            throw IllegalStateException(buildError(response.statusCode(), errorBody))
        }

        val full = StringBuilder()
        response.body().use { lines ->
            lines.forEach { line ->
                if (!line.startsWith("data:")) return@forEach
                val data = line.substring(5).trim()
                if (data == "[DONE]" || data.isEmpty()) return@forEach
                try {
                    val json = gson.fromJson(data, JsonObject::class.java)
                    val delta = json?.getAsJsonArray("choices")
                        ?.takeIf { !it.isEmpty }
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("delta")
                        ?.get("content")?.asString
                    if (!delta.isNullOrEmpty()) {
                        full.append(delta)
                        onDelta(delta)
                    }
                } catch (_: Exception) {
                    // 忽略无法解析的行
                }
            }
        }
        return full.toString()
    }

    private fun buildError(status: Int, body: String): String {
        val errorMsg = try {
            val json = gson.fromJson(body, JsonObject::class.java)
            json?.getAsJsonObject("error")?.get("message")?.asString ?: body
        } catch (e: Exception) {
            body
        }
        return "DeepSeek API 错误 ($status): $errorMsg"
    }

    private fun buildRequestBody(messages: List<Pair<String, String>>, stream: Boolean = false): String {
        val messagesArray = JsonArray()
        messages.forEach { (role, content) ->
            val msg = JsonObject()
            msg.addProperty("role", role)
            msg.addProperty("content", content)
            messagesArray.add(msg)
        }

        val root = JsonObject()
        root.addProperty("model", getModel())
        root.add("messages", messagesArray)
        root.addProperty("temperature", 0.7)
        root.addProperty("stream", stream)
        return gson.toJson(root)
    }

    private fun parseResponse(body: String): String {
        val json = gson.fromJson(body, JsonObject::class.java)
        val choices = json?.getAsJsonArray("choices")
        if (choices == null || choices.isEmpty) {
            throw IllegalStateException("DeepSeek 返回空结果")
        }
        val message = choices[0].asJsonObject.getAsJsonObject("message")
        return message?.get("content")?.asString ?: ""
    }
}
