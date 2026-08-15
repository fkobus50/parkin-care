package com.example.parkincare

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AlarmData(
    val medicineList: String,
    val scheduledDate: String,
    val idsList: String,
    val medicineIdsList: String,
    val doseList: String
)

object AlarmStorage {
    private const val PREFS_NAME = "alarms_prefs"
    private const val KEY_ALARMS = "alarms_json"

    fun saveAlarm(context: Context, alarm: AlarmData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alarms = getAlarms(context).toMutableList()
        alarms.add(alarm)
        val jsonArray = JSONArray()
        alarms.forEach {
            val obj = JSONObject()
            obj.put("medicineList", it.medicineList)
            obj.put("scheduledDate", it.scheduledDate)
            obj.put("idsList", it.idsList)
            obj.put("medicineIdsList", it.medicineIdsList)
            obj.put("doseList", it.doseList)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_ALARMS, jsonArray.toString()).apply()
    }

    fun getAlarms(context: Context): List<AlarmData> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        val jsonArray = JSONArray(json)
        val result = mutableListOf<AlarmData>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            result.add(
                AlarmData(
                    obj.getString("medicineList"),
                    obj.getString("scheduledDate"),
                    obj.getString("idsList"),
                    obj.optString("medicineIdsList", ""),
                    obj.optString("doseList", "")
                )
            )
        }
        return result
    }

    fun removeAlarm(context: Context, scheduledDate: String, idsList: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alarms = getAlarms(context).filterNot {
            it.scheduledDate == scheduledDate && it.idsList == idsList
        }
        val jsonArray = JSONArray()
        alarms.forEach {
            val obj = JSONObject()
            obj.put("medicineList", it.medicineList)
            obj.put("scheduledDate", it.scheduledDate)
            obj.put("idsList", it.idsList)
            obj.put("medicineIdsList", it.medicineIdsList)
            obj.put("doseList", it.doseList)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_ALARMS, jsonArray.toString()).apply()
    }
}
