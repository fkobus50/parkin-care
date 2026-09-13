// ...new file...
package com.example.parkincare.util

import android.content.Context
import com.example.parkincare.data.local.ApiTokenHistory
import com.example.parkincare.data.local.DatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.parkincare.util.ParkinLogger as Log

object TokenHistoryRecorder {

    fun record(context: Context, apiToken: String, patientId: String) {
        val now = System.currentTimeMillis()
        try {
            val db = DatabaseProvider.getDatabase(context)
            val dao = db.apiTokenHistoryDao()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    dao.closeOpenEntriesForToken(apiToken, now - 1)
                    dao.insert(ApiTokenHistory(apiToken = apiToken, patientId = patientId, fromTs = now, toTs = null))
                } catch (e: Exception) {
                    Log.e("TokenHistoryRecorder", "Błąd podczas zapisu historii tokena", e)
                }
            }
        } catch (e: Exception) {
            Log.e("TokenHistoryRecorder", "Nie udało się uzyskać dostępu do bazy danych lub DAO", e)
        }
    }
}
