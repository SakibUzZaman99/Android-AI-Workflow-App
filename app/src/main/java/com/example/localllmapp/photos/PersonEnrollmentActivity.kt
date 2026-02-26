package com.example.localllmapp.photos

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.database.getStringOrNull
import com.example.localllmapp.photos.FaceEmbeddingHelper
import org.json.JSONObject

class PersonEnrollmentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = Color(0xFF23272A)) {
                    EnrollmentScreen(
                        onDone = { name, uris ->
                            val data = Intent().apply {
                                putExtra("personName", name)
                                putStringArrayListExtra("imageUris", ArrayList(uris.map { it.toString() }))
                            }
                            setResult(Activity.RESULT_OK, data)
                            finish()
                        },
                        onCancel = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun EnrollmentScreen(onDone: (String, List<Uri>) -> Unit, onCancel: () -> Unit) {
    var personName by remember { mutableStateOf("") }
    val pickedUris = remember { mutableStateListOf<Uri>() }
    val picker = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (!uris.isNullOrEmpty()) {
            val limit = kotlin.math.min(5, uris.size)
            pickedUris.addAll(uris.subList(0, limit))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF23272A))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Enroll Person", color = Color(0xFF8AB4F8), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = personName,
            onValueChange = { personName = it },
            label = { Text("Person name (e.g., Son)") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { picker.launch("image/*") }) { Text("Pick up to 5 photos") }
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA3838))) { Text("Cancel") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (personName.isNotBlank() && pickedUris.isNotEmpty()) onDone(personName, pickedUris) },
            enabled = personName.isNotBlank() && pickedUris.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3EECAC))
        ) { Text("Save") }
    }
}


