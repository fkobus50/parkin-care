/*
Klasa docelowo może zostać wykorzystana do jednoczesnej obsługi wielu urządzeń typu wearable.
 */
package com.example.parkincare.data

import com.example.parkincare.data.local.WatchPairingDao
import com.example.parkincare.data.local.WatchPairingEntity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.parkincare.util.ParkinLogger as Log
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Locale

class WatchPairingRepository(private val dao: WatchPairingDao) {

    suspend fun createPairing(watchId: String?, patientId: String, fromTs: Long = System.currentTimeMillis()): String {
        return withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            if (!watchId.isNullOrBlank()) {
                val active = dao.findActivePairingForWatch(watchId)
                if (active != null) {
                    try {
                        dao.endPairing(active.id, fromTs - 1)
                        try { Log.i("WATCH_PAIRING", "Closed previous pairing id=${active.id} watch=${active.watchId} patient=${active.patientId} toTs=${active.toTs} -> closedAt=${fromTs-1}") } catch (_: Exception) {}
                    } catch (_: Exception) {}
                }
            }
            val pairing = WatchPairingEntity(
                id = id,
                watchId = watchId,
                patientId = patientId,
                fromTs = fromTs,
                toTs = null
            )
            dao.insertPairing(pairing)
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val fromTxt = sdf.format(java.util.Date(fromTs))
                Log.i("WATCH_PAIRING", "Created pairing id=$id watch=${watchId} patient=${patientId} from=$fromTxt")
            } catch (_: Exception) {}
            id
        }
    }

    suspend fun endPairing(pairingId: String, toTs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        dao.endPairing(pairingId, toTs)
        try {
            Log.i("WATCH_PAIRING", "Ended pairing id=$pairingId toTs=$toTs")
        } catch (_: Exception) {}
    }

    suspend fun endActivePairingForWatch(watchId: String, toTs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val active = dao.findActivePairingForWatch(watchId) ?: return@withContext
        dao.endPairing(active.id, toTs)
        try { Log.i("WATCH_PAIRING", "Ended active pairing id=${active.id} watch=${watchId} patient=${active.patientId} toTs=${toTs}") } catch (_: Exception) {}
    }

    suspend fun getPatientForWatchAt(watchId: String, ts: Long): String? = withContext(Dispatchers.IO) {
        if (watchId.isBlank()) return@withContext null
        val p = dao.getPairingForTime(watchId, ts)
        p?.patientId
    }

    suspend fun getPatientForTimeAny(ts: Long): String? = withContext(Dispatchers.IO) {
        val p = dao.getPairingForTimeAny(ts)
        p?.patientId
    }

    suspend fun getPairingsForPatient(patientId: String) = withContext(Dispatchers.IO) {
        dao.getPairingsForPatient(patientId)
    }

    fun logAllPairingsSync() {
        try {
            runBlocking {
                val all = dao.getPairingsForPatient("")
            }
        } catch (_: Exception) {
        }
    }

    suspend fun logAllPairings() = withContext(Dispatchers.IO) {
        try {
            val all = dao.getAll()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            Log.i("WATCH_PAIRING", "Found ${all.size} pairings:")
            all.forEach { p ->
                val fromTxt = try { sdf.format(java.util.Date(p.fromTs)) } catch (_: Exception) { p.fromTs.toString() }
                val toTxt = try { if (p.toTs != null) sdf.format(java.util.Date(p.toTs)) else "(active)" } catch (_: Exception) { p.toTs?.toString() ?: "(active)" }
                Log.i("WATCH_PAIRING", "id=${p.id} watch=${p.watchId} patient=${p.patientId} from=$fromTxt to=$toTxt")
            }
        } catch (e: Exception) {
            Log.e("WATCH_PAIRING", "logAllPairings error: ${e.message}")
        }
    }
}
