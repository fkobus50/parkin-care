package com.example.parkincare.data.local

import androidx.room.*

@Dao
interface MedicineHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: MedicineHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(history: MedicineHistoryEntity): Long // insert ignorujący konflikt (nie nadpisuje istniejącego rekordu)

    @Query("UPDATE medicine_history SET notificationDate = :notificationDate WHERE id = :id AND taken = 0 AND shown = 0 AND (executionDate IS NULL OR executionDate = '')")
    suspend fun updateNotificationDateIfUnHandled(id: String, notificationDate: String): Int

    @Query("UPDATE medicine_history SET notificationDate = :notificationDate, scheduledDate = :scheduledDate WHERE id = :id AND taken = 0 AND shown = 0 AND (executionDate IS NULL OR executionDate = '')")
    suspend fun updateNotificationAndScheduledIfUnHandled(id: String, notificationDate: String, scheduledDate: String): Int

    @Query("SELECT * FROM medicine_history WHERE sent = 0 AND patientId = :patientId")
    suspend fun getUnsentForPatient(patientId: String): List<MedicineHistoryEntity>

    @Query("SELECT * FROM medicine_history WHERE patientId = :patientId ORDER BY scheduledDate DESC")
    suspend fun getAllForPatient(patientId: String): List<MedicineHistoryEntity>

    @Query("UPDATE medicine_history SET sent = 1 WHERE id = :id")
    suspend fun markAsSent(id: String)

    @Query("DELETE FROM medicine_history WHERE id = :id")
    suspend fun deleteById(id: String)

    // Pobierz wpisy po liście id
    @Query("SELECT * FROM medicine_history WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MedicineHistoryEntity>

    // Zaktualizuj pola statusu dla listy id leków
    @Query("UPDATE medicine_history SET taken = :taken, shown = :shown, executionDate = :executionDate, notificationDate = :notificationDate, sent = :sent WHERE id IN (:ids)")
    suspend fun updateStatusForIds(
        ids: List<String>,
        taken: Boolean,
        shown: Boolean,
        executionDate: String,
        notificationDate: String,
        sent: Boolean
    )

}
