package com.example.data.local

import kotlinx.coroutines.flow.Flow

class ApkReportRepository(private val dao: ApkReportDao) {
    val reports: Flow<List<ApkReportEntity>> = dao.observeAllReports()

    suspend fun saveReport(report: ApkReportEntity): Long = dao.insertReport(report)

    suspend fun deleteReport(id: Long) = dao.deleteReportById(id)

    suspend fun clearAll() = dao.clearAll()
}
