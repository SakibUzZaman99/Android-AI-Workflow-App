package com.example.localllmapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import com.example.localllmapp.helpers.LlmInferenceHelper
import kotlinx.coroutines.Job

class InstructionModelActivity : ComponentActivity() {

    private var inferenceJob: Job? = null
    private var isModelInitialized by mutableStateOf(false)
    private var initMessage by mutableStateOf("Welcome, I will be your text only LLM")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface {
                    InstructionChatScreen(
                        onSend = { userMessage, onResult ->
                            runTextGeneration(userMessage, onResult)
                        },
                        onBackClick = { finish() },
                        initMessage = initMessage
                    )
                }
            }
        }

        // Initialize model in background
        lifecycleScope.launch(Dispatchers.IO) {
            val success = LlmInferenceHelper.initInstructionModel(this@InstructionModelActivity)
            withContext(Dispatchers.Main) {
                isModelInitialized = success
                initMessage = if (success) {
                    "Model ready! Start chatting..."
                } else {
                    "Failed to initialize model. Check if model files are in /data/local/tmp/llm/"
                }
            }
        }
    }

    private fun runTextGeneration(prompt: String, onResult: (String) -> Unit) {
        if (!isModelInitialized) {
            onResult("Error: Model not initialized yet. Please wait...")
            return
        }

        inferenceJob?.cancel()
        inferenceJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
//                withContext(Dispatchers.Main) {
//                    onResult("Generating...") // Show loading state
//                }

                val response = LlmInferenceHelper.generateResponse(prompt)

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

@Composable
fun InstructionChatScreen(
    onSend: (String, (String) -> Unit) -> Unit,
    onBackClick: () -> Unit,
    initMessage: String
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val chatHistory = remember { mutableStateListOf<Pair<String, Boolean>>() }
    var isLoading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Add initialization message
    LaunchedEffect(initMessage) {
        if (chatHistory.isEmpty()) {
            chatHistory.add(initMessage to false)
        }
    }

    LaunchedEffect(chatHistory.size) {
        if (chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(index = chatHistory.size - 1)
        }
    }

    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF191919), Color(0xFF333639))
    )
    val chatPanelColor = Color(0xFF23272A)
    val appBarColor = Color(0xFF282C34)

    Column(modifier = Modifier.fillMaxSize().background(backgroundGradient)) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(appBarColor)
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                "IntraAI (Instruction Model)",
                color = Color(0xFF8AB4F8),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(chatHistory) { (message, isUser) ->
                ChatBubble(text = message, isUser = isUser)
            }
        }

        // Input panel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF181A1B))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .background(chatPanelColor, shape = RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, color = Color.White),
                decorationBox = { innerTextField ->
                    if (input.text.isEmpty()) {
                        Text("Type a message...", color = Color.Gray)
                    }
                    innerTextField()
                }
            )
            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    val prompt = input.text.trim()
                    if (prompt.isNotBlank() && !isLoading) {
                        isLoading = true
                        chatHistory.add(prompt to true)
                        input = TextFieldValue("")

                        onSend(prompt) { response ->
                            chatHistory.add(response to false)
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && input.text.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isLoading && input.text.isNotBlank()) Color(0xFF8AB4F8) else Color(0xFFB0B0B0)
                )
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun ChatBubble(text: String, isUser: Boolean) {
    val bubbleColor = if (isUser) Color(0xFF3A89FF) else Color(0xFF36393F)
    val textColor = if (isUser) Color.White else Color(0xFFECECEC)
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(bubbleColor, shape = RoundedCornerShape(16.dp))
                .padding(12.dp)
                .widthIn(max = 300.dp)
        ) {
            Text(text = text, fontSize = 15.sp, color = textColor)
        }
    }
}