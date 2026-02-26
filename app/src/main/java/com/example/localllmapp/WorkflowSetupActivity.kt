package com.example.localllmapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import java.io.File
import org.json.JSONObject
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.example.localllmapp.geofence.LocationGeofenceManager
import com.google.android.gms.location.Geofence
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.util.Date
import android.content.ComponentName
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.example.localllmapp.geofence.GeofenceRegistrar
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.example.localllmapp.photos.FaceEmbeddingHelper
import android.graphics.BitmapFactory

class WorkflowSetupActivity : ComponentActivity() {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
   // private val scope = rememberCoroutineScope()
   private val scope = MainScope()
    private var pendingRegisterOnPermission: Boolean = false
    private var lastSavedWorkflowFileName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()

            MaterialTheme {
                Surface {
                    WorkflowSetupScreen(
                        onSaveClick = { source, sourceAccount, destination, destinationAccount, instructions ->
                            scope.launch {
                                saveWorkflow(source, sourceAccount, destination, destinationAccount, instructions)
                            }
                        },
                        onBackClick = { finish() },
                        isNotificationServiceEnabled = { isNotificationServiceEnabled() },
                        onRequestNotificationAccess = { requestNotificationAccess() },
                        onClearLocal = {
                            val deleted = clearLocalWorkflows()
                            Toast.makeText(
                                this@WorkflowSetupActivity,
                                "Deleted $deleted local workflow(s)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            // Pass picked geofence data into our activity intent so saveWorkflow can read it
            intent.putExtra("geoLatitude", data.getDoubleExtra("geoLatitude", Double.NaN))
            intent.putExtra("geoLongitude", data.getDoubleExtra("geoLongitude", Double.NaN))
            intent.putExtra("geoRadiusMeters", data.getFloatExtra("geoRadiusMeters", Float.NaN))
        } else if (requestCode == 404 && resultCode == RESULT_OK && data != null) {
            // Person enrollment result for Photos source
            val fileName = lastSavedWorkflowFileName ?: return
            val personName = data.getStringExtra("personName") ?: return
            val uriStrings = data.getStringArrayListExtra("imageUris") ?: arrayListOf()
            val embeds = mutableListOf<FloatArray>()
            // Ensure model is loaded once
            FaceEmbeddingHelper.loadModel(this)
            uriStrings.forEach { s ->
                runCatching {
                    val uri = android.net.Uri.parse(s)
                    contentResolver.openInputStream(uri)?.use { input ->
                        val bmp = BitmapFactory.decodeStream(input)
                        val done = java.util.concurrent.CountDownLatch(1)
                        var vec: FloatArray? = null
                        FaceEmbeddingHelper.detectFaces(bmp, highAccuracy = false) { faces ->
                            // choose the largest face
                            val best = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                            if (best != null) {
                                vec = FaceEmbeddingHelper.embedCroppedFace(this, bmp, best.boundingBox)
                            }
                            done.countDown()
                        }
                        done.await()
                        vec?.let { embeds += it }
                    }
                }
            }
            if (embeds.isNotEmpty()) {
                // write into workflow json
                runCatching {
                    val file = filesDir.listFiles { f -> f.name == fileName }?.firstOrNull() ?: return@runCatching
                    val json = org.json.JSONObject(file.readText())
                    json.put("photoPersonName", personName)
                    val arr = org.json.JSONArray()
                    embeds.forEach { v ->
                        val inner = org.json.JSONArray()
                        v.forEach { inner.put(it.toDouble()) }
                        arr.put(inner)
                    }
                    json.put("photoPersonEmbeddings", arr)
                    file.writeText(json.toString())
                    android.widget.Toast.makeText(this, "Saved ${embeds.size} embeddings for $personName", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                android.widget.Toast.makeText(this, "No faces detected in selected photos", android.widget.Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private suspend fun saveWorkflow(
        source: String,
        sourceAccount: String,
        destination: String,
        destinationAccount: String,
        instructions: String
    ) {
        try {
            // Resolve Telegram phone numbers to chat IDs before saving
            var resolvedSourceAccount = sourceAccount
            var resolvedDestinationAccount = destinationAccount
            if (source == "Telegram" && sourceAccount.isNotBlank() && sourceAccount != "Any") {
                runCatching {
                    val chatId = getChatIdFromPhoneNumber(sourceAccount)
                    if (!chatId.isNullOrBlank()) resolvedSourceAccount = chatId
                }
            }
            if (destination == "Telegram" && destinationAccount.isNotBlank() && destinationAccount != "Any") {
                runCatching {
                    val chatId = getChatIdFromPhoneNumber(destinationAccount)
                    if (!chatId.isNullOrBlank()) resolvedDestinationAccount = chatId
                }
            }
            // Save to local JSON file (for backward compatibility)
            val workflow = JSONObject().apply {
                put("source", source)
                put("sourceAccount", resolvedSourceAccount)
                put("destination", destination)
                put("destinationAccount", resolvedDestinationAccount)
                put("instructions", instructions)
                if (source == "Photos") {
                    put("photoBaselineTs", System.currentTimeMillis())
                    put("photoLastProcessedTs", 0L)
                    put("photoBootstrapCount", 10) // process last 10 recent images once
                }
                // Optional geofence fields if Maps source was used
                // These will be filled by MapPickerActivity via intent extras before save
                intent?.getDoubleExtra("geoLatitude", Double.NaN)?.let { if (!it.isNaN()) put("geoLatitude", it) }
                intent?.getDoubleExtra("geoLongitude", Double.NaN)?.let { if (!it.isNaN()) put("geoLongitude", it) }
                intent?.getFloatExtra("geoRadiusMeters", Float.NaN)?.let { if (!it.isNaN()) put("geoRadiusMeters", it.toDouble()) }
                put("timestamp", System.currentTimeMillis())
                put("active", true)
            }

            // Debug log what we're about to save
            Log.d(
                "WorkflowSetup",
                "Saving workflow: src=$source dest=$destination lat=${workflow.optDouble("geoLatitude", Double.NaN)} lng=${workflow.optDouble("geoLongitude", Double.NaN)} radius=${workflow.optDouble("geoRadiusMeters", Double.NaN)} instructions='${instructions.take(40)}'"
            )

            val fileName = "workflow_${System.currentTimeMillis()}.json"
            val file = File(filesDir, fileName)
            file.writeText(workflow.toString())
            lastSavedWorkflowFileName = fileName

            // Also save to Firestore for cloud sync
            saveWorkflowToFirestore(workflow)

            // If Maps source, attempt to register a geofence immediately
            if (source == "Maps") {
                val lat = workflow.optDouble("geoLatitude", Double.NaN)
                val lng = workflow.optDouble("geoLongitude", Double.NaN)
                val radius = workflow.optDouble("geoRadiusMeters", Double.NaN)
                if (!lat.isNaN() && !lng.isNaN() && !radius.isNaN()) {
                    if (hasLocationPermissions()) {
                        registerGeofenceFromIntent(fileName, lat, lng, radius.toFloat())
                    } else {
                        pendingRegisterOnPermission = true
                        requestLocationPermissions()
                        Toast.makeText(this, "Grant location permissions to enable geofence", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Pick a location and radius for Maps source", Toast.LENGTH_LONG).show()
                }
            }

            Toast.makeText(this, "Workflow saved successfully!", Toast.LENGTH_SHORT).show()
            if (source == "Photos") {
                // Kick off photos monitoring once saved
                runCatching { com.example.localllmapp.photos.PhotosWorkflowManager.refresh(this) }
                // If instructions likely require person matching, open enrollment
                if (instructionsRequirePersonEnrollment(instructions)) {
                    val intent = android.content.Intent(this, com.example.localllmapp.photos.PersonEnrollmentActivity::class.java)
                    startActivityForResult(intent, 404)
                    return
                }
            }
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving workflow: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun instructionsRequirePersonEnrollment(text: String): Boolean {
        val t = text.lowercase()
        val keywords = listOf("my son", "my kid", "my daughter", "my wife", "my husband", "my father", "my mother", "photos of my", "picture of my")
        return keywords.any { t.contains(it) }
    }

    private fun saveWorkflowToFirestore(workflow: JSONObject) {
        val userId = auth.currentUser?.uid ?: return

        val workflowData = hashMapOf(
            "userId" to userId,
            "source" to workflow.getString("source"),
            "sourceAccount" to workflow.getString("sourceAccount"),
            "destination" to workflow.getString("destination"),
            "destinationAccount" to workflow.getString("destinationAccount"),
            "instructions" to workflow.getString("instructions"),
            "geoLatitude" to workflow.optDouble("geoLatitude", Double.NaN).let { if (it.isNaN()) null else it },
            "geoLongitude" to workflow.optDouble("geoLongitude", Double.NaN).let { if (it.isNaN()) null else it },
            "geoRadiusMeters" to workflow.optDouble("geoRadiusMeters", Double.NaN).let { if (it.isNaN()) null else it },
            "active" to workflow.getBoolean("active"),
            "createdAt" to Date(),
            "lastModified" to Date()
        )

        firestore.collection("workflows")
            .add(workflowData)
            .addOnSuccessListener {
                android.util.Log.d("WorkflowSetup", "Workflow saved to Firestore")
            }
            .addOnFailureListener { e ->
                android.util.Log.e("WorkflowSetup", "Error saving to Firestore", e)
            }
    }

    // Look up a Telegram chat ID from a phone number mapping in Firestore
    private suspend fun getChatIdFromPhoneNumber(phoneNumber: String): String? {
        return suspendCancellableCoroutine { cont ->
            try {
                firestore.collection("telegram_users_by_phone").document(phoneNumber)
                    .get()
                    .addOnSuccessListener { doc ->
                        val chatIdLong = doc.getLong("user_id")
                        cont.resume(chatIdLong?.toString())
                    }
                    .addOnFailureListener { cont.resume(null) }
            } catch (_: Throwable) { cont.resume(null) }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val packageName = packageName
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":").toTypedArray()
            for (name in names) {
                val componentName = ComponentName.unflattenFromString(name)
                if (componentName != null && TextUtils.equals(packageName, componentName.packageName)) {
                    return true
                }
            }
        }
        return false
    }

    private fun requestNotificationAccess() {
        startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun registerGeofenceFromIntent(fileName: String, lat: Double, lng: Double, radiusMeters: Float) {
        if (!hasLocationPermissions()) return
        try {
            val manager = LocationGeofenceManager(this)
            try {
                manager.addGeofence(
                    id = fileName,
                    latitude = lat,
                    longitude = lng,
                    radiusMeters = radiusMeters,
                    transitions = Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL
                )
            } catch (se: SecurityException) {
                Log.w("WorkflowSetup", "SecurityException adding geofence: ${se.message}")
                return
            }
            // Immediately evaluate once to simulate DWELL if already inside
            try {
                GeofenceRegistrar.evaluateNow(this, transitionName = "DWELL")
            } catch (se: SecurityException) {
                Log.w("WorkflowSetup", "SecurityException evaluating location: ${se.message}")
            }
            Toast.makeText(this, "Geofence registered", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Failed to add geofence: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bgNeeded = android.os.Build.VERSION.SDK_INT >= 29
        val bgOk = !bgNeeded || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return (fine || coarse) && bgOk
    }

    private fun requestLocationPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (android.os.Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 202)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 202) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted && pendingRegisterOnPermission) {
                pendingRegisterOnPermission = false
                val lat = intent?.getDoubleExtra("geoLatitude", Double.NaN) ?: Double.NaN
                val lng = intent?.getDoubleExtra("geoLongitude", Double.NaN) ?: Double.NaN
                val radius = intent?.getFloatExtra("geoRadiusMeters", Float.NaN) ?: Float.NaN
                val fileName = lastSavedWorkflowFileName ?: ("workflow_" + System.currentTimeMillis() + ".json")
                if (!lat.isNaN() && !lng.isNaN() && !radius.isNaN() && hasLocationPermissions()) {
                    registerGeofenceFromIntent(fileName, lat, lng, radius)
                }
            }
        }
    }

    private fun clearLocalWorkflows(): Int {
        return try {
            val files = filesDir.listFiles { f ->
                f.name.startsWith("workflow_") && f.name.endsWith(".json")
            } ?: emptyArray()
            var count = 0
            files.forEach { file -> if (file.delete()) count++ }
            // Ensure any observers are stopped if no workflows remain
            runCatching { com.example.localllmapp.photos.PhotosWorkflowManager.refresh(this) }
            count
        } catch (_: Exception) {
            0
        }
    }
}

@Composable
fun WorkflowSetupScreen(
    onSaveClick: (String, String, String, String, String) -> Unit,
    onBackClick: () -> Unit,
    isNotificationServiceEnabled: () -> Boolean,
    onRequestNotificationAccess: () -> Unit,
    onClearLocal: () -> Unit
) {
    var selectedSource by remember { mutableStateOf("") }
    var sourceAccount by remember { mutableStateOf(TextFieldValue("")) }
    var selectedDestination by remember { mutableStateOf("") }
    var destinationAccount by remember { mutableStateOf(TextFieldValue("")) }
    var instructions by remember { mutableStateOf(TextFieldValue("")) }
    var showSourceDropdown by remember { mutableStateOf(false) }
    var showDestinationDropdown by remember { mutableStateOf(false) }
    var showConfirmationDialog by remember { mutableStateOf(false) }
    var showNotificationPermissionDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }

    // Check notification permission on launch
    LaunchedEffect(Unit) {
        if (!isNotificationServiceEnabled()) {
            showNotificationPermissionDialog = true
        }
    }

    // Validation functions (same as before)
    fun isValidEmail(email: String): Boolean = android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()

    fun isValidPhoneNumber(phone: String): Boolean {
        return phone.isNotEmpty() &&
                phone.matches(Regex("^[+]?[1-9]\\d{6,14}$"))
    }

    fun validateInput(): String? {
        if (selectedSource != "Photos" && !isNotificationServiceEnabled()) return "Please enable notification access first"
        if (selectedSource.isEmpty()) return "Please select a source app"
        if (selectedDestination.isEmpty()) return "Please select a destination app"
        if (instructions.text.isEmpty()) return "Please enter instructions"

        // Validate source account if provided
        if (sourceAccount.text.isNotEmpty()) {
            if (selectedSource == "Google" && !isValidEmail(sourceAccount.text)) {
                return "Please enter a valid Gmail address"
            }
            if (selectedSource == "Telegram" && !isValidPhoneNumber(sourceAccount.text)) {
                return "Please enter a valid phone number"
            }
        }

        // Validate destination account (mandatory)
        if (destinationAccount.text.isEmpty()) {
            return "Please enter destination ${if (selectedDestination == "Google") "Gmail account" else "phone number"}"
        }
        if (selectedDestination == "Google" && !isValidEmail(destinationAccount.text)) {
            return "Please enter a valid Gmail address"
        }
        if (selectedDestination == "Telegram" && !isValidPhoneNumber(destinationAccount.text)) {
            return "Please enter a valid phone number"
        }

        return null
    }

    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF191919), Color(0xFF333639))
    )
    val chatPanelColor = Color(0xFF23272A)
    val appBarColor = Color(0xFF282C34)
    val inputBackgroundColor = Color(0xFF23272A)

    val sourceOptions = listOf("Google", "Telegram", "Maps", "Photos")
    val destinationOptions = listOf("Google", "Telegram")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(chatPanelColor)
        ) {
            // Top app bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(appBarColor)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Workflow Setup",
                    color = Color(0xFF8AB4F8),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { showClearDialog = true }) {
                    Text("Clear", color = Color(0xFFEA3838))
                }
            }

            // Notification Service Status Card
            if (!isNotificationServiceEnabled()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFF4444).copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Notification Access Required",
                                color = Color(0xFFFF4444),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Enable to detect Gmail/Telegram notifications",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = onRequestNotificationAccess,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF4444)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Enable", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Scrollable content (rest of the UI remains the same)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {

                // Select Source Section
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Select Source",
                            color = Color(0xFF8AB4F8),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = " *",
                            color = Color(0xFFFF4444),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Source Dropdown
                    Box {
                        OutlinedTextField(
                            value = selectedSource,
                            onValueChange = { },
                            readOnly = true,
                            placeholder = { Text("Choose source app", color = Color(0xFFB0B0B0)) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Dropdown",
                                    tint = Color.White
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF8AB4F8),
                                unfocusedBorderColor = Color(0xFF666666),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF8AB4F8)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Invisible clickable overlay
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { showSourceDropdown = !showSourceDropdown }
                        )

                        DropdownMenu(
                            expanded = showSourceDropdown,
                            onDismissRequest = { showSourceDropdown = false },
                            modifier = Modifier.background(inputBackgroundColor)
                        ) {
                            sourceOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option, color = Color.White) },
                                    onClick = {
                                        selectedSource = option
                                        sourceAccount = TextFieldValue("") // Clear input when source changes
                                        showSourceDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    // Source Account / Maps Picker
                    if (selectedSource.isNotEmpty()) {
                        Text(
                            text = when (selectedSource) {
                                "Google" -> "Gmail Account (Optional)"
                                "Telegram" -> "Phone Number (Optional)"
                                "Maps" -> "Choose Location"
                                else -> ""
                            },
                            color = Color(0xFFB0B0B0),
                            fontSize = 14.sp
                        )
                        if (selectedSource == "Maps") {
                            Button(
                                onClick = {
                                    // Launch MapPickerActivity to choose a location and radius
                                    val intent = android.content.Intent(context, com.example.localllmapp.maps.MapPickerActivity::class.java)
                                    // Defaults
                                    intent.putExtra("defaultRadiusMeters", 200f)
                                    (context as? android.app.Activity)?.startActivityForResult(intent, 101)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Pick Location on Map", color = Color.Black)
                            }
                        } else if (selectedSource == "Google" || selectedSource == "Telegram") {
                            OutlinedTextField(
                                value = sourceAccount,
                                onValueChange = { sourceAccount = it },
                                placeholder = {
                                    Text(
                                        if (selectedSource == "Google") "Enter Gmail account" else "Enter phone number",
                                        color = Color(0xFFB0B0B0)
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = if (sourceAccount.text.isNotEmpty() &&
                                        ((selectedSource == "Google" && !isValidEmail(sourceAccount.text)) ||
                                                (selectedSource == "Telegram" && !isValidPhoneNumber(sourceAccount.text))))
                                        Color(0xFFFF4444) else Color(0xFF8AB4F8),
                                    unfocusedBorderColor = if (sourceAccount.text.isNotEmpty() &&
                                        ((selectedSource == "Google" && !isValidEmail(sourceAccount.text)) ||
                                                (selectedSource == "Telegram" && !isValidPhoneNumber(sourceAccount.text))))
                                        Color(0xFFFF4444) else Color(0xFF666666),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = Color(0xFF8AB4F8)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (selectedSource == "Photos") {
                            val act = context as? android.app.Activity
                            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.READ_MEDIA_IMAGES
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (!hasPermission) {
                                Button(
                                    onClick = {
                                        act?.let {
                                            androidx.core.app.ActivityCompat.requestPermissions(
                                                it,
                                                arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES),
                                                303
                                            )
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8AB4F8)),
                                    shape = RoundedCornerShape(8.dp)
                                ) { Text("Grant Photos Permission", color = Color.Black) }
                            } else {
                                Text("Photos access granted", color = Color(0xFF8AB4F8), fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Select Destination Section
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Select Destination",
                            color = Color(0xFF8AB4F8),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = " *",
                            color = Color(0xFFFF4444),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Destination Dropdown
                    Box {
                        OutlinedTextField(
                            value = selectedDestination,
                            onValueChange = { },
                            readOnly = true,
                            placeholder = { Text("Choose destination app", color = Color(0xFFB0B0B0)) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Dropdown",
                                    tint = Color.White
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF8AB4F8),
                                unfocusedBorderColor = Color(0xFF666666),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF8AB4F8)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Invisible clickable overlay
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable { showDestinationDropdown = !showDestinationDropdown }
                        )

                        DropdownMenu(
                            expanded = showDestinationDropdown,
                            onDismissRequest = { showDestinationDropdown = false },
                            modifier = Modifier.background(inputBackgroundColor)
                        ) {
                            destinationOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option, color = Color.White) },
                                    onClick = {
                                        selectedDestination = option
                                        destinationAccount = TextFieldValue("") // Clear input when destination changes
                                        showDestinationDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    // Destination Account Input - Only show if destination is selected
                    if (selectedDestination.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (selectedDestination == "Google") "Gmail Account" else "Phone Number",
                                color = Color(0xFFB0B0B0),
                                fontSize = 14.sp
                            )
                            Text(
                                text = " *",
                                color = Color(0xFFFF4444),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedTextField(
                            value = destinationAccount,
                            onValueChange = { destinationAccount = it },
                            placeholder = {
                                Text(
                                    if (selectedDestination == "Google") "Enter Gmail account" else "Enter phone number",
                                    color = Color(0xFFB0B0B0)
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (destinationAccount.text.isNotEmpty() &&
                                    ((selectedDestination == "Google" && !isValidEmail(destinationAccount.text)) ||
                                            (selectedDestination == "Telegram" && !isValidPhoneNumber(destinationAccount.text))))
                                    Color(0xFFFF4444) else Color(0xFF8AB4F8),
                                unfocusedBorderColor = if (destinationAccount.text.isNotEmpty() &&
                                    ((selectedDestination == "Google" && !isValidEmail(destinationAccount.text)) ||
                                            (selectedDestination == "Telegram" && !isValidPhoneNumber(destinationAccount.text))))
                                    Color(0xFFFF4444) else Color(0xFF666666),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF8AB4F8)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Instructions Section
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Instructions",
                            color = Color(0xFF8AB4F8),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = " *",
                            color = Color(0xFFFF4444),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        placeholder = {
                            Text(
                                "Enter your instructions here",
                                color = Color(0xFFB0B0B0)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF8AB4F8),
                            unfocusedBorderColor = Color(0xFF666666),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF8AB4F8)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 6,
                        singleLine = false
                    )
                }

                // Save Button
                Button(
                    onClick = {
                        val validationError = validateInput()
                        if (validationError == null) {
                            showConfirmationDialog = true
                        } else {
                            Toast.makeText(context, validationError, Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3EECAC),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "SAVE WORKFLOW",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Confirmation Dialog
        if (showConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmationDialog = false },
                containerColor = Color(0xFF23272A),
                title = {
                    Text(
                        "Confirm Workflow",
                        color = Color(0xFF8AB4F8),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Source: $selectedSource", color = Color.White)
                        Text(
                            "${if (selectedSource == "Google") "Gmail" else "Number"}: ${if (sourceAccount.text.isEmpty()) "Any" else sourceAccount.text}",
                            color = Color.White
                        )
                        Text("Destination: $selectedDestination", color = Color.White)
                        Text(
                            "${if (selectedDestination == "Google") "Gmail" else "Number"}: ${destinationAccount.text}",
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Instructions:", color = Color(0xFF8AB4F8), fontWeight = FontWeight.SemiBold)
                        Text(instructions.text, color = Color.White)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirmationDialog = false
                            onSaveClick(
                                selectedSource,
                                if (sourceAccount.text.isEmpty()) "Any" else sourceAccount.text,
                                selectedDestination,
                                destinationAccount.text,
                                instructions.text
                            )
                        }
                    ) {
                        Text("CONFIRM", color = Color(0xFF3EECAC), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showConfirmationDialog = false }
                    ) {
                        Text("CANCEL", color = Color(0xFFEA3838), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Clear Local Workflows Dialog
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                containerColor = Color(0xFF23272A),
                title = {
                    Text(
                        "Clear Local Workflows",
                        color = Color(0xFF8AB4F8),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "This will delete all locally saved workflows on this device. Firestore workflows are unaffected.",
                        color = Color.White
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showClearDialog = false
                        onClearLocal()
                    }) {
                        Text("DELETE", color = Color(0xFFEA3838), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("CANCEL", color = Color(0xFF8AB4F8), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Notification Permission Dialog
        if (showNotificationPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showNotificationPermissionDialog = false },
                containerColor = Color(0xFF23272A),
                title = {
                    Text(
                        "Enable Notification Access",
                        color = Color(0xFF8AB4F8),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "To monitor Gmail and Telegram notifications, this app needs notification access permission. This allows the app to detect when notifications arrive and trigger your workflows automatically.",
                        color = Color.White
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showNotificationPermissionDialog = false
                            onRequestNotificationAccess()
                        }
                    ) {
                        Text("OPEN SETTINGS", color = Color(0xFF3EECAC), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showNotificationPermissionDialog = false }
                    ) {
                        Text("LATER", color = Color(0xFFEA3838), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}