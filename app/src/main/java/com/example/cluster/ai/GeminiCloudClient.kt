package com.example.cluster.ai

import com.example.BuildConfig
import com.example.cluster.model.AiCoreStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiCloudClient(
    private val apiKeyProvider: () -> String = { resolveConfiguredApiKey() },
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val modelName: String = "gemini-2.5-flash",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val PLACEHOLDER_KEY = "MY_GEMINI_API_KEY"

        fun resolveConfiguredApiKey(): String {
            val raw = runCatching { BuildConfig.GEMINI_API_KEY }.getOrDefault("").trim()
            return if (raw.isEmpty() || raw == PLACEHOLDER_KEY || raw == "null") "" else raw
        }
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean {
        val key = apiKeyProvider().trim()
        return key.isNotEmpty() && key != PLACEHOLDER_KEY && key != "null"
    }

    suspend fun generate(
        prompt: String,
        systemInstruction: String = "",
        temperature: Float = 0.2f,
        maxOutputTokens: Int = 512
    ): AiCoreGenerationResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty() || apiKey == PLACEHOLDER_KEY || apiKey == "null") {
            return@withContext AiCoreGenerationResult(
                success = false,
                outputText = "",
                elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                status = AiCoreStatus.UNAVAILABLE,
                errorMessage = "Gemini Cloud API key is not configured."
            )
        }

        runCatching {
            val payload = buildGeneratePayload(
                prompt = prompt,
                systemInstruction = systemInstruction,
                temperature = temperature,
                maxOutputTokens = maxOutputTokens
            )
            val req = Request.Builder()
                .url("$baseUrl/models/$modelName:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .post(payload.toRequestBody(jsonMedia))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                if (!resp.isSuccessful) {
                    val errDetail = parseErrorMessage(body) ?: "Gemini API HTTP ${resp.code}"
                    return@withContext AiCoreGenerationResult(
                        success = false,
                        outputText = "",
                        elapsedMs = elapsed,
                        status = AiCoreStatus.CLOUD_READY,
                        errorMessage = errDetail
                    )
                }
                val outputText = extractCandidateText(body).trim()
                AiCoreGenerationResult(
                    success = outputText.isNotEmpty(),
                    outputText = outputText,
                    elapsedMs = elapsed,
                    status = AiCoreStatus.CLOUD_READY,
                    errorMessage = if (outputText.isEmpty()) "Gemini API returned an empty response." else null
                )
            }
        }.getOrElse { err ->
            AiCoreGenerationResult(
                success = false,
                outputText = "",
                elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                status = AiCoreStatus.CLOUD_READY,
                errorMessage = err.message ?: "Gemini Cloud request failed."
            )
        }
    }

    fun generateStream(
        prompt: String,
        systemInstruction: String = "",
        temperature: Float = 0.2f,
        maxOutputTokens: Int = 512
    ): Flow<String> = flow {
        val result = generate(
            prompt = prompt,
            systemInstruction = systemInstruction,
            temperature = temperature,
            maxOutputTokens = maxOutputTokens
        )
        if (!result.success) {
            throw IllegalStateException(result.errorMessage ?: "Gemini Cloud inference failed.")
        }
        emit(result.outputText)
    }.flowOn(Dispatchers.IO)

    internal fun buildGeneratePayload(
        prompt: String,
        systemInstruction: String,
        temperature: Float,
        maxOutputTokens: Int
    ): String {
        val root = JSONObject()
        if (systemInstruction.isNotBlank()) {
            root.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction.trim()))
                )
            )
        }
        root.put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt.trim())))
            )
        )
        root.put(
            "generationConfig",
            JSONObject()
                .put("temperature", temperature.toDouble())
                .put("maxOutputTokens", maxOutputTokens)
        )
        return root.toString()
    }

    internal fun extractCandidateText(rawJson: String): String {
        val root = JSONObject(rawJson)
        val candidates = root.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (text.isNotEmpty()) sb.append(text)
        }
        return sb.toString()
    }

    private fun parseErrorMessage(rawJson: String): String? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val root = JSONObject(rawJson)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
