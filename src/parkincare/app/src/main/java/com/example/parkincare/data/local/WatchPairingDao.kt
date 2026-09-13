package com.example.parkincare.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WatchPairingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPairing(pairing: WatchPairingEntity)

    @Query("UPDATE watch_pairing SET toTs = :toTs WHERE id = :pairingId")
    suspend fun endPairing(pairingId: String, toTs: Long): Int

    @Query("SELECT * FROM watch_pairing WHERE watchId = :watchId AND fromTs <= :ts AND (toTs IS NULL OR toTs > :ts) ORDER BY fromTs DESC LIMIT 1")
    suspend fun getPairingForTime(watchId: String, ts: Long): WatchPairingEntity?

    @Query("SELECT * FROM watch_pairing WHERE fromTs <= :ts AND (toTs IS NULL OR toTs > :ts) ORDER BY fromTs DESC LIMIT 1")
    suspend fun getPairingForTimeAny(ts: Long): WatchPairingEntity?

    @Query("SELECT * FROM watch_pairing WHERE patientId = :patientId ORDER BY fromTs DESC")
    suspend fun getPairingsForPatient(patientId: String): List<WatchPairingEntity>

    @Query("SELECT * FROM watch_pairing ORDER BY fromTs DESC")
    suspend fun getAll(): List<WatchPairingEntity>

    @Query("SELECT * FROM watch_pairing WHERE watchId = :watchId AND toTs IS NULL LIMIT 1")
    suspend fun findActivePairingForWatch(watchId: String): WatchPairingEntity?

    @Query("SELECT * FROM watch_pairing WHERE watchId = :watchId AND fromTs <= :maxTs AND (toTs IS NULL OR toTs >= :minTs) ORDER BY fromTs ASC")
    suspend fun getPairingsForWatchInRange(watchId: String, minTs: Long, maxTs: Long): List<WatchPairingEntity>
}
