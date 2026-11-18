package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apk_reports")
data class ApkReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val appLabel: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val isDebuggable: Boolean,
    val apkSizeBytes: Long,
    val sha256: String,
    val permissionsJoined: String,
    val activityCount: Int,
    val serviceCount: Int,
    val receiverCount: Int,
    val riskLevel: String,
    val aiSummary: String,
    val findingsJoined: String,
    val recommendationsJoined: String,
    val inspectedAtMillis: Long = System.currentTimeMillis()
)
