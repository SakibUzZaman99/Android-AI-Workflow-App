package com.example.localllmapp.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return
        try {
            val files = context.filesDir.listFiles { f ->
                f.name.startsWith("workflow_") && f.name.endsWith(".json")
            } ?: return

            val manager = LocationGeofenceManager(context)
            files.forEach { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    if (json.optString("source") == "Maps") {
                        val lat = json.optDouble("geoLatitude", Double.NaN)
                        val lng = json.optDouble("geoLongitude", Double.NaN)
                        val radius = json.optDouble("geoRadiusMeters", Double.NaN)
                        if (!lat.isNaN() && !lng.isNaN() && !radius.isNaN()) {
                            manager.addGeofence(
                                id = file.name,
                                latitude = lat,
                                longitude = lng,
                                radiusMeters = radius.toFloat()
                            )
                        }
                    }
                }.onFailure { t ->
                    Log.w("BootCompletedReceiver", "Failed to restore geofence from ${file.name}", t)
                }
            }
        } catch (t: Throwable) {
            Log.w("BootCompletedReceiver", "Error restoring geofences on boot", t)
        }
    }
}



