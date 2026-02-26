package com.example.localllmapp.geofence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.localllmapp.WorkflowExecutor
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object GeofenceRegistrar {
    private const val TAG = "GeofenceRegistrar"

    /**
     * Immediately evaluates geofence workflows at the user's current location by simulating a transition.
     * Requires location permission; otherwise it is a no-op.
     */
    fun evaluateNow(context: Context, transitionName: String = "DWELL") {
        try {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                Log.w(TAG, "Location permission not granted; skipping evaluateNow")
                return
            }

            val fused = LocationServices.getFusedLocationProviderClient(context)
            fused.lastLocation
                .addOnSuccessListener { loc ->
                    if (loc == null) return@addOnSuccessListener
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            WorkflowExecutor(context).processGeofenceEvent(loc.latitude, loc.longitude, transitionName)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Error invoking processGeofenceEvent", t)
                        }
                    }
                }
                .addOnFailureListener { e -> Log.w(TAG, "Failed to get lastLocation", e) }
        } catch (t: Throwable) {
            Log.w(TAG, "evaluateNow error", t)
        }
    }
}



