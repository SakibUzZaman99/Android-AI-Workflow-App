package com.example.localllmapp.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import android.util.Log

class LocationGeofenceManager(private val context: Context) {
    companion object { private const val TAG = "GeofenceManager" }
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun addGeofence(id: String, latitude: Double, longitude: Double, radiusMeters: Float, transitions: Int = Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL) {
        val geofence = Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(latitude, longitude, radiusMeters)
            .setTransitionTypes(transitions)
            .setLoiteringDelay(1 * 60 * 1000) // 1 minute dwell for testing
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent())
            .addOnSuccessListener { Log.d(TAG, "Geofence added: $id ($latitude,$longitude r=$radiusMeters)") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to add geofence $id: ${e.message}", e) }
    }

    fun removeGeofence(id: String) {
        geofencingClient.removeGeofences(listOf(id))
            .addOnSuccessListener { Log.d(TAG, "Geofence removed: $id") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to remove geofence $id: ${e.message}", e) }
    }

    private fun geofencePendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java).apply { action = "com.example.localllmapp.GEOFENCE" }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}



