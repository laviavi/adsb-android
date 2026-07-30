package com.laviavi.adsbandroid.data

import android.content.Context
import com.laviavi.adsbandroid.aircraft.IcaoEntry
import org.json.JSONObject

object IcaoLookup {

    fun load(context: Context): Map<String, IcaoEntry> = try {
        val json = context.assets.open("icao_db.json").bufferedReader().readText()
        parse(json)
    } catch (e: Exception) {
        emptyMap()
    }

    fun parse(json: String): Map<String, IcaoEntry> {
        val result = HashMap<String, IcaoEntry>()
        val obj = JSONObject(json)
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key  = keys.next()
            val icao = key.uppercase()
            val e    = obj.getJSONObject(key)
            result[icao] = IcaoEntry(
                registration = e.optString("reg").presentOrNull(),
                operator     = e.optString("op").presentOrNull(),
                aircraftType = e.optString("type").presentOrNull(),
            )
        }
        return result
    }

    /**
     * The source database spells a missing field as the literal string `Null` in
     * some rows, which reached the Live list as an operator named "Null". Absent is
     * absent however it is spelled.
     */
    private fun String.presentOrNull(): String? =
        trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}
