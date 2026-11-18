package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ApkReportDao {
    @Query("SELECT * FROM apk_reports ORDER BY inspectedAtMillis DESC")
    fun observeAllReports(): Flow<List<ApkReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: ApkReportEntity): Long

    @Query("DELETE FROM apk_reports WHERE id = :id")
    suspend fun deleteReportById(id: Long)

    @Query("DELETE FROM apk_reports")
    suspend fun clearAll()
}
