package com.example.localllmapp.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.localllmapp.WorkflowExecutor
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e("GeofenceReceiver", "Geofencing event error: ${event.errorCode}")
            return
        }

        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT && transition != Geofence.GEOFENCE_TRANSITION_DWELL) {
            return
        }

        val transitionName = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            else -> "UNKNOWN"
        }
        val triggering = event.triggeringLocation
        val geofenceIds = event.triggeringGeofences?.mapNotNull { it.requestId } ?: emptyList()
        Log.d("GeofenceReceiver", "Received transition=${transition} ids=${geofenceIds} loc=${triggering}")
        // Prefer ID-based matching (reliable for EXIT); fallback to location proximity if no IDs
        GlobalScope.launch(Dispatchers.IO) {
            val executor = WorkflowExecutor(context)
            if (geofenceIds.isNotEmpty()) {
                executor.processGeofenceEventByIds(geofenceIds, transitionName)
            } else if (triggering != null) {
                executor.processGeofenceEvent(
                    triggering.latitude,
                    triggering.longitude,
                    transitionName
                )
            } else {
                Log.w("GeofenceReceiver", "No ids or location to process geofence event")
            }
        }
    }
}


