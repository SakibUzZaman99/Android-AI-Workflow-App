package com.example.localllmapp.maps

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.android.gms.maps.model.LatLng
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

class MapPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defaultRadius = intent.getFloatExtra("defaultRadiusMeters", 200f)

        setContent {
            MaterialTheme {
                Surface(color = Color(0xFF23272A)) {
                    MapPickerScreen(
                        defaultRadius = defaultRadius,
                        onCancel = { finish() },
                        onConfirm = { lat, lng, radius ->
                            val data = intent
                            data.putExtra("geoLatitude", lat)
                            data.putExtra("geoLongitude", lng)
                            data.putExtra("geoRadiusMeters", radius)
                            setResult(Activity.RESULT_OK, data)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MapPickerScreen(
    defaultRadius: Float,
    onCancel: () -> Unit,
    onConfirm: (Double, Double, Float) -> Unit
) {
    var selected by remember { mutableStateOf<LatLng?>(null) }
    var radius by remember { mutableStateOf(defaultRadius) }

    val camera = rememberCameraPositionState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF23272A))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pick Location", color = Color(0xFF8AB4F8), fontSize = 20.sp, fontWeight = FontWeight.Bold)

        Box(modifier = Modifier.weight(1f)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                onMapClick = { latLng -> selected = latLng }
            ) {
                selected?.let { point ->
                    Marker(state = MarkerState(point))
                }
            }
        }

        // Radius control (simple)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Radius (m):", color = Color.White)
            Slider(
                value = radius,
                onValueChange = { radius = it },
                valueRange = 50f..1000f,
                steps = 18,
                modifier = Modifier.weight(1f)
            )
            Text(radius.toInt().toString(), color = Color.White)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Cancel", color = Color(0xFFEA3838)) }

            Button(
                onClick = { selected?.let { onConfirm(it.latitude, it.longitude, radius) } },
                enabled = selected != null,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3EECAC))
            ) { Text("Confirm", color = Color.Black) }
        }
    }
}



