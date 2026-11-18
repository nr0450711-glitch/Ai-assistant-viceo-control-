package com.example.data.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class ApkMetadata(
    val sourceLabel: String,
    val appLabel: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val isDebuggable: Boolean,
    val apkSizeBytes: Long,
    val sha256: String,
    val permissions: List<String>,
    val activityCount: Int,
    val serviceCount: Int,
    val receiverCount: Int
)

object ApkParser {

    @Suppress("DEPRECATION")
    private val packageFlags: Int
        get() = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS

    suspend fun inspectRunningApp(context: Context): ApkMetadata = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val pkgName = context.packageName
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(packageFlags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkgName, packageFlags)
        }

        val apkPath = info.applicationInfo?.sourceDir
        val apkFile = apkPath?.let { File(it) }
        val sizeBytes = if (apkFile != null && apkFile.exists()) apkFile.length() else 0L
        val sha256 = if (apkFile != null && apkFile.exists()) computeSha256(apkFile) else "Unavailable"

        buildMetadata(
            pm = pm,
            packageInfo = info,
            sourceLabel = apkFile?.name ?: "Installed Build",
            sizeBytes = sizeBytes,
            sha256 = sha256
        )
    }

    suspend fun inspectFromUri(context: Context, uri: Uri): ApkMetadata = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolveDisplayName(context, uri) ?: "selected-app.apk"
        val tempFile = File.createTempFile("inspect_", ".apk", context.cacheDir)

        try {
            resolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Unable to read selected APK stream.")

            if (tempFile.length() == 0L) {
                throw IllegalStateException("The selected file is empty.")
            }

            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    tempFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(packageFlags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(tempFile.absolutePath, packageFlags)
            } ?: throw IllegalArgumentException("Could not parse valid AndroidManifest from $displayName.")

            info.applicationInfo?.apply {
                sourceDir = tempFile.absolutePath
                publicSourceDir = tempFile.absolutePath
            }

            val sha256 = computeSha256(tempFile)
            buildMetadata(
                pm = pm,
                packageInfo = info,
                sourceLabel = displayName,
                sizeBytes = tempFile.length(),
                sha256 = sha256
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun buildMetadata(
        pm: PackageManager,
        packageInfo: PackageInfo,
        sourceLabel: String,
        sizeBytes: Long,
        sha256: String
    ): ApkMetadata {
        val appInfo = packageInfo.applicationInfo
        val label = appInfo?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
            ?: packageInfo.packageName
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val minSdk = appInfo?.minSdkVersion ?: 21
        val targetSdk = appInfo?.targetSdkVersion ?: 0
        val isDebuggable = ((appInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val permissions = packageInfo.requestedPermissions?.toList().orEmpty().sorted()

        return ApkMetadata(
            sourceLabel = sourceLabel,
            appLabel = label,
            packageName = packageInfo.packageName.orEmpty(),
            versionName = packageInfo.versionName ?: "1.0",
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            isDebuggable = isDebuggable,
            apkSizeBytes = sizeBytes,
            sha256 = sha256,
            permissions = permissions,
            activityCount = packageInfo.activities?.size ?: 0,
            serviceCount = packageInfo.services?.size ?: 0,
            receiverCount = packageInfo.receivers?.size ?: 0
        )
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(file).use { stream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
