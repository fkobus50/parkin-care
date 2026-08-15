package com.example.parkincare.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ApiTokenHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ApiTokenHistory)

    @Query("SELECT * FROM api_token_history WHERE `fromTs` <= :ts AND (`toTs` IS NULL OR `toTs` > :ts) ORDER BY `fromTs` DESC LIMIT 1")
    suspend fun getForTime(ts: Long): ApiTokenHistory?

    @Query("SELECT * FROM api_token_history WHERE `patient_id` = :patientId AND `fromTs` <= :ts AND (`toTs` IS NULL OR `toTs` > :ts) ORDER BY `fromTs` DESC LIMIT 1")
    suspend fun getForPatientAtTime(patientId: String, ts: Long): ApiTokenHistory?

    @Query("UPDATE api_token_history SET `toTs` = :toTs WHERE `toTs` IS NULL AND `api_token` = :apiToken")
    suspend fun closeOpenEntriesForToken(apiToken: String, toTs: Long)
}
