package com.example.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.example.data.apk.ApkMetadata
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class ApkAiAudit(
    val riskLevel: String,
    val summary: String,
    val keyFindings: List<String>,
    val recommendations: List<String>
)

enum class GeminiModelOption(val modelId: String, val displayLabel: String) {
    FLASH("gemini-3.8-flash", "Gemini 3.8 Flash"),
    FLASH_LITE("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite")
}

class GeminiAiService {

    companion object {
        private const val TAG = "GeminiAiService"
        private const val AI_TIMEOUT_MS = 60_000L
        private const val MAX_IMAGE_DIM = 1280
    }

    private val systemPrompt = content {
        text(
            "You are APK AI, an expert Android engineer, APK security analyst, and Jetpack Compose assistant. " +
                "Give concise, accurate, practical answers tailored to modern Android (Kotlin, Jetpack Compose, Material 3, Gradle, Manifest permissions, ProGuard/R8, and Play Store compliance)."
        )
    }

    private val flashModel by lazy {
        Firebase.ai.generativeModel(
            modelName = GeminiModelOption.FLASH.modelId,
            systemInstruction = systemPrompt
        )
    }

    private val flashLiteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = GeminiModelOption.FLASH_LITE.modelId,
            systemInstruction = systemPrompt
        )
    }

    private val auditSchema = Schema.obj(
        properties = mapOf(
            "riskLevel" to Schema.enumeration(
                values = listOf("LOW", "MEDIUM", "HIGH"),
                description = "Overall security and release-readiness risk level of the APK"
            ),
            "summary" to Schema.string(
                description = "2 to 3 sentence executive summary of the APK's configuration, permissions, and build posture"
            ),
            "keyFindings" to Schema.array(
                items = Schema.string(),
                description = "3 to 5 concise findings covering permissions, debuggable flag, SDK targets, and component surface"
            ),
            "recommendations" to Schema.array(
                items = Schema.string(),
                description = "3 to 5 actionable Android engineering recommendations"
            )
        )
    )

    private val auditModel by lazy {
        Firebase.ai.generativeModel(
            modelName = GeminiModelOption.FLASH.modelId,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = auditSchema
            },
            systemInstruction = systemPrompt
        )
    }

    fun streamChatMessage(
        modelOption: GeminiModelOption,
        history: List<Content>,
        prompt: String,
        attachedBitmap: Bitmap?
    ): Flow<String> {
        val model = when (modelOption) {
            GeminiModelOption.FLASH -> flashModel
            GeminiModelOption.FLASH_LITE -> flashLiteModel
        }

        val chat = model.startChat(history = history)
        val userContent = content("user") {
            if (attachedBitmap != null) {
                image(attachedBitmap)
            }
            text(prompt)
        }

        return chat.sendMessageStream(userContent).map { chunk ->
            chunk.text.orEmpty()
        }
    }

    suspend fun auditApk(metadata: ApkMetadata): ApkAiAudit {
        val permissionsList = if (metadata.permissions.isEmpty()) {
            "None declared"
        } else {
            metadata.permissions.joinToString(", ")
        }

        val prompt = buildString {
            appendLine("Audit this Android APK manifest and build profile:")
            appendLine("- App Name: ${metadata.appLabel}")
            appendLine("- Source File: ${metadata.sourceLabel}")
            appendLine("- Package Name: ${metadata.packageName}")
            appendLine("- Version: ${metadata.versionName} (code ${metadata.versionCode})")
            appendLine("- minSdk: ${metadata.minSdk}, targetSdk: ${metadata.targetSdk}")
            appendLine("- Debuggable Build Flag: ${metadata.isDebuggable}")
            appendLine("- APK Size: ${metadata.apkSizeBytes} bytes")
            appendLine("- Components: ${metadata.activityCount} activities, ${metadata.serviceCount} services, ${metadata.receiverCount} receivers")
            appendLine("- Requested Permissions: $permissionsList")
        }

        val response = try {
            withTimeout(AI_TIMEOUT_MS) {
                auditModel.generateContent(prompt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "APK audit generation failed", e)
            throw e
        }

        val rawJson = response.text ?: throw IllegalStateException("Model returned an empty audit response.")
        val json = JSONObject(rawJson)

        val findingsArray = json.optJSONArray("keyFindings")
        val findings = buildList {
            if (findingsArray != null) {
                for (i in 0 until findingsArray.length()) {
                    add(findingsArray.getString(i))
                }
            }
        }

        val recArray = json.optJSONArray("recommendations")
        val recommendations = buildList {
            if (recArray != null) {
                for (i in 0 until recArray.length()) {
                    add(recArray.getString(i))
                }
            }
        }

        return ApkAiAudit(
            riskLevel = json.optString("riskLevel", "MEDIUM"),
            summary = json.optString("summary", "Audit completed."),
            keyFindings = findings,
            recommendations = recommendations
        )
    }

    suspend fun decodeScaledBitmap(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }

        val maxDim = maxOf(raw.width, raw.height)
        if (maxDim <= MAX_IMAGE_DIM) {
            raw
        } else {
            val scale = MAX_IMAGE_DIM.toFloat() / maxDim.toFloat()
            val targetW = (raw.width * scale).toInt().coerceAtLeast(1)
            val targetH = (raw.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(raw, targetW, targetH, true)
        }
    }
}
