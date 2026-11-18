package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.ApkAiAudit
import com.example.data.ai.GeminiAiService
import com.example.data.ai.GeminiModelOption
import com.example.data.apk.ApkMetadata
import com.example.data.apk.ApkParser
import com.example.data.local.ApkReportEntity
import com.example.data.local.ApkReportRepository
import com.example.data.local.AppDatabase
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val attachedImageUri: Uri? = null,
    val isStreaming: Boolean = false
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val selectedModel: GeminiModelOption = GeminiModelOption.FLASH,
    val pendingImageUri: Uri? = null,
    val isGenerating: Boolean = false,
    val errorMessage: String? = null
)

sealed interface AuditUiState {
    data object Idle : AuditUiState
    data object Analyzing : AuditUiState
    data class Success(val audit: ApkAiAudit, val savedId: Long?) : AuditUiState
    data class Error(val message: String) : AuditUiState
}

sealed interface InspectorUiState {
    data object Idle : InspectorUiState
    data object Parsing : InspectorUiState
    data class Ready(
        val metadata: ApkMetadata,
        val auditState: AuditUiState = AuditUiState.Idle
    ) : InspectorUiState
    data class Error(val message: String) : InspectorUiState
}

class ApkAiViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ApkAiViewModel"
        private const val STREAM_TIMEOUT_MS = 60_000L
        private const val DELIMITER = "\n|||\n"
    }

    private val aiService = GeminiAiService()
    private val repository: ApkReportRepository by lazy {
        val dao = AppDatabase.getInstance(application).apkReportDao()
        ApkReportRepository(dao)
    }

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _chatState = MutableStateFlow(ChatUiState())
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    private val _inspectorState = MutableStateFlow<InspectorUiState>(InspectorUiState.Idle)
    val inspectorState: StateFlow<InspectorUiState> = _inspectorState.asStateFlow()

    val savedReports: StateFlow<List<ApkReportEntity>> = repository.reports.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList()
    )

    private val _selectedReport = MutableStateFlow<ApkReportEntity?>(null)
    val selectedReport: StateFlow<ApkReportEntity?> = _selectedReport.asStateFlow()

    private var chatStreamJob: Job? = null
    private var auditJob: Job? = null

    fun toggleTheme() {
        _isDarkTheme.update { !it }
    }

    fun selectModel(option: GeminiModelOption) {
        _chatState.update { it.copy(selectedModel = option) }
    }

    fun attachImage(uri: Uri?) {
        _chatState.update { it.copy(pendingImageUri = uri, errorMessage = null) }
    }

    fun clearChatError() {
        _chatState.update { it.copy(errorMessage = null) }
    }

    fun resetChat() {
        chatStreamJob?.cancel()
        _chatState.update {
            ChatUiState(selectedModel = it.selectedModel)
        }
    }

    fun sendChatMessage(rawPrompt: String) {
        val prompt = rawPrompt.trim()
        val imageUri = _chatState.value.pendingImageUri
        if ((prompt.isEmpty() && imageUri == null) || _chatState.value.isGenerating) return

        val effectivePrompt = prompt.ifEmpty {
            "Inspect this Android screenshot or log capture and explain any issues or improvements."
        }

        val priorHistory: List<Content> = _chatState.value.messages
            .filter { it.text.isNotBlank() && !it.isStreaming }
            .map { msg ->
                content(role = if (msg.isUser) "user" else "model") {
                    text(msg.text)
                }
            }

        val userMsg = ChatMessage(
            isUser = true,
            text = effectivePrompt,
            attachedImageUri = imageUri
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val placeholderAiMsg = ChatMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            isStreaming = true
        )

        _chatState.update {
            it.copy(
                messages = it.messages + userMsg + placeholderAiMsg,
                pendingImageUri = null,
                isGenerating = true,
                errorMessage = null
            )
        }

        chatStreamJob = viewModelScope.launch {
            val accumulated = StringBuilder()
            try {
                val bitmap = imageUri?.let {
                    aiService.decodeScaledBitmap(getApplication(), it)
                }
                val modelOption = _chatState.value.selectedModel

                withTimeout(STREAM_TIMEOUT_MS) {
                    aiService.streamChatMessage(
                        modelOption = modelOption,
                        history = priorHistory,
                        prompt = effectivePrompt,
                        attachedBitmap = bitmap
                    ).collect { chunk ->
                        accumulated.append(chunk)
                        val updatedText = accumulated.toString()
                        _chatState.update { state ->
                            state.copy(
                                messages = state.messages.map { m ->
                                    if (m.id == assistantMsgId) m.copy(text = updatedText) else m
                                }
                            )
                        }
                    }
                }

                if (accumulated.isBlank()) {
                    throw IllegalStateException("Gemini returned an empty response.")
                }

                _chatState.update { state ->
                    state.copy(
                        isGenerating = false,
                        messages = state.messages.map { m ->
                            if (m.id == assistantMsgId) m.copy(isStreaming = false) else m
                        }
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Chat streaming timed out", e)
                failChatMessage(
                    assistantMsgId,
                    "Request timed out after 60 seconds. Check connectivity and try again."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chat streaming failed", e)
                failChatMessage(
                    assistantMsgId,
                    e.localizedMessage ?: "Failed to generate Gemini response."
                )
            }
        }
    }

    private fun failChatMessage(assistantMsgId: String, errorMsg: String) {
        _chatState.update { state ->
            state.copy(
                isGenerating = false,
                errorMessage = errorMsg,
                messages = state.messages.filterNot { it.id == assistantMsgId && it.text.isBlank() }
                    .map { m ->
                        if (m.id == assistantMsgId) m.copy(isStreaming = false) else m
                    }
            )
        }
    }

    fun inspectRunningApp() {
        auditJob?.cancel()
        viewModelScope.launch {
            _inspectorState.value = InspectorUiState.Parsing
            try {
                val metadata = ApkParser.inspectRunningApp(getApplication())
                _inspectorState.value = InspectorUiState.Ready(metadata)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inspect running APK", e)
                _inspectorState.value = InspectorUiState.Error(
                    e.localizedMessage ?: "Unable to inspect current APK."
                )
            }
        }
    }

    fun inspectApkUri(uri: Uri) {
        auditJob?.cancel()
        viewModelScope.launch {
            _inspectorState.value = InspectorUiState.Parsing
            try {
                val metadata = ApkParser.inspectFromUri(getApplication(), uri)
                _inspectorState.value = InspectorUiState.Ready(metadata)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse selected APK", e)
                _inspectorState.value = InspectorUiState.Error(
                    e.localizedMessage ?: "Failed to parse selected .apk file."
                )
            }
        }
    }

    fun runAiAuditOnCurrentApk() {
        val current = _inspectorState.value as? InspectorUiState.Ready ?: return
        if (current.auditState is AuditUiState.Analyzing) return

        auditJob?.cancel()
        auditJob = viewModelScope.launch {
            _inspectorState.value = current.copy(auditState = AuditUiState.Analyzing)
            try {
                val audit = aiService.auditApk(current.metadata)
                val entity = ApkReportEntity(
                    appLabel = current.metadata.appLabel,
                    packageName = current.metadata.packageName,
                    versionName = current.metadata.versionName,
                    versionCode = current.metadata.versionCode,
                    minSdk = current.metadata.minSdk,
                    targetSdk = current.metadata.targetSdk,
                    isDebuggable = current.metadata.isDebuggable,
                    apkSizeBytes = current.metadata.apkSizeBytes,
                    sha256 = current.metadata.sha256,
                    permissionsJoined = current.metadata.permissions.joinToString(DELIMITER),
                    activityCount = current.metadata.activityCount,
                    serviceCount = current.metadata.serviceCount,
                    receiverCount = current.metadata.receiverCount,
                    riskLevel = audit.riskLevel,
                    aiSummary = audit.summary,
                    findingsJoined = audit.keyFindings.joinToString(DELIMITER),
                    recommendationsJoined = audit.recommendations.joinToString(DELIMITER)
                )
                val savedId = repository.saveReport(entity)
                _inspectorState.value = current.copy(
                    auditState = AuditUiState.Success(audit = audit, savedId = savedId)
                )
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "APK audit timed out", e)
                _inspectorState.value = current.copy(
                    auditState = AuditUiState.Error("Audit timed out after 60 seconds. Please retry.")
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "APK audit failed", e)
                _inspectorState.value = current.copy(
                    auditState = AuditUiState.Error(
                        e.localizedMessage ?: "Failed to run Gemini APK audit."
                    )
                )
            }
        }
    }

    fun openReportDetail(report: ApkReportEntity) {
        _selectedReport.value = report
    }

    fun closeReportDetail() {
        _selectedReport.value = null
    }

    fun deleteReport(id: Long) {
        viewModelScope.launch {
            repository.deleteReport(id)
            if (_selectedReport.value?.id == id) {
                _selectedReport.value = null
            }
        }
    }

    fun splitJoinedList(joined: String): List<String> {
        if (joined.isBlank()) return emptyList()
        return joined.split(DELIMITER).filter { it.isNotBlank() }
    }
}
