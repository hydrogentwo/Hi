package com.example.cluster.ai

import com.example.cluster.model.AiCoreStatus
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class AiCoreLocalState(
    val status: AiCoreStatus = AiCoreStatus.UNKNOWN,
    val detail: String = "Checking Android AICore / Gemini Nano availability...",
    val downloadedBytes: Long = 0L,
    val isWarmedUp: Boolean = false
)

data class AiCoreGenerationResult(
    val success: Boolean,
    val outputText: String,
    val elapsedMs: Long,
    val status: AiCoreStatus,
    val errorMessage: String? = null
)

class AiCoreEngine(
    private val modelFactory: () -> GenerativeModel? = {
        runCatching { Generation.getClient() }.getOrNull()
    }
) {
    @Volatile
    private var model: GenerativeModel? = null

    private val _state = MutableStateFlow(AiCoreLocalState())
    val state: StateFlow<AiCoreLocalState> = _state.asStateFlow()

    private fun getOrCreateModel(): GenerativeModel? {
        model?.let { return it }
        return synchronized(this) {
            model ?: modelFactory()?.also { model = it }
        }
    }

    suspend fun refreshStatus(): AiCoreStatus = withContext(Dispatchers.IO) {
        val client = getOrCreateModel()
        if (client == null) {
            _state.update {
                it.copy(
                    status = AiCoreStatus.UNAVAILABLE,
                    detail = "AICore system service unavailable on this device runtime."
                )
            }
            return@withContext AiCoreStatus.UNAVAILABLE
        }

        try {
            val featureStatus = client.checkStatus()
            val mapped = when (featureStatus) {
                FeatureStatus.AVAILABLE -> AiCoreStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> AiCoreStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> AiCoreStatus.DOWNLOADING
                else -> AiCoreStatus.UNAVAILABLE
            }
            val detail = when (mapped) {
                AiCoreStatus.AVAILABLE -> "Gemini Nano is ready in AICore for offline on-device inference."
                AiCoreStatus.DOWNLOADABLE -> "Device supports AICore. Tap Download to fetch Gemini Nano weights."
                AiCoreStatus.DOWNLOADING -> "AICore is downloading Gemini Nano model weights..."
                AiCoreStatus.UNAVAILABLE -> "Gemini Nano via AICore is unsupported on this hardware or Android build."
                AiCoreStatus.UNKNOWN -> "Checking AICore availability..."
            }
            _state.update { it.copy(status = mapped, detail = detail) }
            mapped
        } catch (e: GenAiException) {
            _state.update {
                it.copy(
                    status = AiCoreStatus.UNAVAILABLE,
                    detail = e.message ?: "AICore status check failed."
                )
            }
            AiCoreStatus.UNAVAILABLE
        } catch (t: Throwable) {
            _state.update {
                it.copy(
                    status = AiCoreStatus.UNAVAILABLE,
                    detail = t.message ?: "AICore unavailable in this environment."
                )
            }
            AiCoreStatus.UNAVAILABLE
        }
    }

    suspend fun downloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        val client = getOrCreateModel()
            ?: return@withContext Result.failure(IllegalStateException("AICore client unavailable"))

        runCatching {
            _state.update {
                it.copy(
                    status = AiCoreStatus.DOWNLOADING,
                    detail = "Starting Gemini Nano download via AICore...",
                    downloadedBytes = 0L
                )
            }
            client.download().collect { downloadStatus ->
                when (downloadStatus) {
                    is DownloadStatus.DownloadStarted -> {
                        _state.update {
                            it.copy(
                                status = AiCoreStatus.DOWNLOADING,
                                detail = "Downloading Gemini Nano (${downloadStatus.bytesToDownload / (1024 * 1024)} MB)..."
                            )
                        }
                    }

                    is DownloadStatus.DownloadProgress -> {
                        val mb = downloadStatus.totalBytesDownloaded / (1024 * 1024)
                        _state.update {
                            it.copy(
                                status = AiCoreStatus.DOWNLOADING,
                                downloadedBytes = downloadStatus.totalBytesDownloaded,
                                detail = "Downloaded $mb MB via Private Compute Services..."
                            )
                        }
                    }

                    DownloadStatus.DownloadCompleted -> {
                        _state.update {
                            it.copy(
                                status = AiCoreStatus.AVAILABLE,
                                detail = "Gemini Nano download complete and ready in AICore."
                            )
                        }
                    }

                    is DownloadStatus.DownloadFailed -> {
                        val msg = downloadStatus.e.message ?: "Gemini Nano download failed"
                        _state.update {
                            it.copy(
                                status = AiCoreStatus.DOWNLOADABLE,
                                detail = msg
                            )
                        }
                        throw downloadStatus.e
                    }
                }
            }
        }
    }

    suspend fun warmup(): Result<Unit> = withContext(Dispatchers.IO) {
        val client = getOrCreateModel()
            ?: return@withContext Result.failure(IllegalStateException("AICore client unavailable"))

        runCatching {
            client.warmup()
            _state.update {
                it.copy(
                    isWarmedUp = true,
                    detail = "Gemini Nano warmed up in AICore memory."
                )
            }
        }
    }

    fun generateStream(
        prompt: String,
        systemInstruction: String = "",
        temperature: Float = 0.2f,
        maxOutputTokens: Int = 512
    ): Flow<String> = flow {
        val client = getOrCreateModel()
            ?: throw IllegalStateException("AICore is unavailable on this device.")
        val currentStatus = refreshStatus()
        if (currentStatus != AiCoreStatus.AVAILABLE) {
            throw IllegalStateException("AICore status is ${currentStatus.label}. Download or select an AICore-ready peer.")
        }

        val request = buildRequest(prompt, systemInstruction, temperature, maxOutputTokens)
        client.generateContentStream(request).collect { chunk ->
            val piece = chunk.candidates.firstOrNull()?.text.orEmpty()
            if (piece.isNotEmpty()) {
                emit(piece)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generate(
        prompt: String,
        systemInstruction: String = "",
        temperature: Float = 0.2f,
        maxOutputTokens: Int = 512
    ): AiCoreGenerationResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val client = getOrCreateModel()
            ?: return@withContext AiCoreGenerationResult(
                success = false,
                outputText = "",
                elapsedMs = System.currentTimeMillis() - startMs,
                status = AiCoreStatus.UNAVAILABLE,
                errorMessage = "AICore is unavailable on this device."
            )

        val currentStatus = refreshStatus()
        if (currentStatus != AiCoreStatus.AVAILABLE) {
            return@withContext AiCoreGenerationResult(
                success = false,
                outputText = "",
                elapsedMs = System.currentTimeMillis() - startMs,
                status = currentStatus,
                errorMessage = "AICore model is ${currentStatus.label} on target node."
            )
        }

        try {
            val request = buildRequest(prompt, systemInstruction, temperature, maxOutputTokens)
            val response = client.generateContent(request)
            val text = response.candidates.firstOrNull()?.text.orEmpty().trim()
            AiCoreGenerationResult(
                success = text.isNotEmpty(),
                outputText = text,
                elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                status = AiCoreStatus.AVAILABLE,
                errorMessage = if (text.isEmpty()) "AICore returned an empty response." else null
            )
        } catch (e: GenAiException) {
            AiCoreGenerationResult(
                success = false,
                outputText = "",
                elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                status = _state.value.status,
                errorMessage = e.message ?: "AICore inference failed."
            )
        } catch (t: Throwable) {
            AiCoreGenerationResult(
                success = false,
                outputText = "",
                elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L),
                status = _state.value.status,
                errorMessage = t.message ?: "Unexpected AICore error."
            )
        }
    }

    private fun buildRequest(
        prompt: String,
        systemInstructionText: String,
        temperatureValue: Float,
        maxTokensValue: Int
    ) = generateContentRequest(TextPart(prompt.trim())) {
        if (systemInstructionText.isNotBlank()) {
            systemInstruction = SystemInstruction(systemInstructionText.trim())
        }
        temperature = temperatureValue
        maxOutputTokens = maxTokensValue
    }

    fun close() {
        runCatching { model?.close() }
        model = null
    }
}
