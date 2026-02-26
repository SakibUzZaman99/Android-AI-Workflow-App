package com.example.localllmapp

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.localllmapp.helpers.LlmInferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MultiModalActivity : ComponentActivity() {

    private var inferenceJob: Job? = null
    private var isModelInitialized by mutableStateOf(false)
    private var initMessage by mutableStateOf("Welcome, I will be your Multimodal LLM")

    init {
        // Start model initialization as early as possible
        lifecycleScope.launch(Dispatchers.IO) {
            val success = LlmInferenceHelper.initMultiModalModel(applicationContext)
            withContext(Dispatchers.Main) {
                isModelInitialized = success
                initMessage = if (success) {
                    "Model ready! Send a message or image..."
                } else {
                    "Failed to initialize model. Please check model files."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
            val imagePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri != null) {
                    contentResolver.openInputStream(uri)?.readBytes()?.let { bytes ->
                        imageBytes = bytes
                    }
                }
            }

            MaterialTheme {
                Surface {
                    MultiModalChatScreen(
                        imageBytes = imageBytes,
                        onSelectImage = { imagePickerLauncher.launch("image/*") },
                        onRemoveImage = { imageBytes = null },
                        onSend = { prompt, imgBytes, onResult ->
                            runMultiModalInference(prompt, imgBytes, onResult)
                        },
                        onBackClick = { finish() },
                        initMessage = initMessage
                    )
                }
            }
        }
    }

    private fun runMultiModalInference(
        prompt: String,
        image: ByteArray?,
        onResult: (String) -> Unit
    ) {
        if (!isModelInitialized) {
            onResult("Error: Model not initialized yet. Please wait...")
            return
        }

        inferenceJob?.cancel()
        inferenceJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = if (image == null) {
                    LlmInferenceHelper.generateResponse(prompt)
                } else {
                    val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size)
                    if (bitmap != null) {
                        LlmInferenceHelper.generateMultiModalResponse(prompt, bitmap)
                    } else {
                        "Error: Could not decode image"
                    }
                }

                withContext(Dispatchers.Main) {
                    onResult(response)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult("Error: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inferenceJob?.cancel()
        LlmInferenceHelper.cleanup()
    }
}

data class MultiModalMessage(
    val text: String,
    val imageBytes: ByteArray? = null,
    val isUser: Boolean = false
)

@Composable
fun MultiModalChatScreen(
    imageBytes: ByteArray?,
    onSelectImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: (String, ByteArray?, (String) -> Unit) -> Unit,
    onBackClick: () -> Unit,
    initMessage: String
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val messages = remember { mutableStateListOf<MultiModalMessage>() }
    var isLoading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Add initialization message
    LaunchedEffect(initMessage) {
        if (messages.isEmpty()) {
            messages.add(MultiModalMessage(initMessage, null, false))
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(index = messages.size - 1)
        }
    }

    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF191919), Color(0xFF333639))
    )
    val chatPanelColor = Color(0xFF23272A)
    val appBarColor = Color(0xFF282C34)

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
                    "IntraAI (MultiModal Model)",
                    color = Color(0xFF8AB4F8),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Chat messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    MultiModalChatBubble(msg)
                }
            }

            // Preview selected image above input
            if (imageBytes != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF36393F), RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = remember(imageBytes) {
                        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.asImageBitmap()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Selected image",
                                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Selected Image", color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = onRemoveImage) {
                            Icon(
                                painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                                contentDescription = "Remove Image",
                                tint = Color.LightGray
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Input panel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181A1B))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (!isLoading) onSelectImage() },
                    enabled = !isLoading
                ) {
                    Icon(
                        imageVector = Icons.Filled.Photo,
                        contentDescription = "Select Image",
                        tint = if (!isLoading) Color(0xFF8AB4F8) else Color.Gray
                    )
                }
                Spacer(Modifier.width(4.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF23272A), shape = RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, color = Color.White),
                    enabled = !isLoading
                )
                Spacer(modifier = Modifier.width(8.dp))
                val buttonEnabled = !isLoading && (input.text.isNotBlank() || imageBytes != null)
                val buttonColor = if (buttonEnabled) Color(0xFF8AB4F8) else Color(0xFFB0B0B0)
                Button(
                    onClick = {
                        val prompt = input.text
                        val image = imageBytes
                        if (buttonEnabled) {
                            messages.add(MultiModalMessage(prompt, image, isUser = true))
                            input = TextFieldValue("")
                            if (image != null) onRemoveImage()
                            isLoading = true

                            onSend(prompt, image) { response ->
                                messages.add(MultiModalMessage(response, null, false))
                                isLoading = false
                            }
                        }
                    },
                    enabled = buttonEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        contentColor = Color.White,
                        disabledContainerColor = buttonColor,
                        disabledContentColor = Color.White
                    )
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
fun MultiModalChatBubble(msg: MultiModalMessage) {
    val bubbleColor = if (msg.isUser) Color(0xFF3A89FF) else Color(0xFF36393F)
    val textColor = if (msg.isUser) Color.White else Color(0xFFECECEC)
    val alignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .background(bubbleColor, shape = RoundedCornerShape(16.dp))
                .padding(12.dp)
                .widthIn(max = 320.dp),
            horizontalAlignment = if (msg.isUser) Alignment.End else Alignment.Start
        ) {
            if (msg.imageBytes != null) {
                val bitmap = remember(msg.imageBytes) {
                    BitmapFactory.decodeByteArray(msg.imageBytes, 0, msg.imageBytes.size)?.asImageBitmap()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Sent image",
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (msg.text.isNotBlank()) {
                Text(
                    text = msg.text,
                    fontSize = 15.sp,
                    color = textColor
                )
            }
        }
    }
}