package com.example.localllmapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.auth.api.signin.GoogleSignIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

class ModelSelectionActivity : ComponentActivity() {
    private lateinit var googleSignInClient: GoogleSignInClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Ensure Photos workflows (if any) start observing on app entry
        runCatching { com.example.localllmapp.photos.PhotosWorkflowManager.ensureStarted(this) }

        setContent {
            var showLogoutDialog by remember { mutableStateOf(false) }

            MaterialTheme {
                Surface(color = Color.Transparent) {
                    ModelSelectionScreen(
                        onInstructionModelClick = {
                            startActivity(Intent(this, InstructionModelActivity::class.java))
                        },
                        onMultiModalClick = {
                            startActivity(Intent(this, MultiModalActivity::class.java))
                        },
                        onWorkflowClick = {
                            startActivity(Intent(this, WorkflowSetupActivity::class.java))
                            runCatching { com.example.localllmapp.photos.PhotosWorkflowManager.ensureStarted(this) }
                        },
                        onNotificationDemoClick = {
                            //startActivity(Intent(this, NotificationDisplayActivity::class.java))
                        },
                        onLogoutClick = { showLogoutDialog = true }
                    )

                    if (showLogoutDialog) {
                        AlertDialog(
                            onDismissRequest = { showLogoutDialog = false },
                            title = { Text("Confirm Logout") },
                            text = { Text("Are you sure you want to log out?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showLogoutDialog = false
                                    googleSignInClient.signOut().addOnCompleteListener(this) {
                                        FirebaseAuth.getInstance().signOut()
                                        val intent = Intent(this, LoginActivity::class.java)
                                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        startActivity(intent)
                                        finish()
                                    }
                                }) { Text("Yes") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showLogoutDialog = false }) { Text("No") }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelSelectionScreen(
    onInstructionModelClick: () -> Unit,
    onMultiModalClick: () -> Unit,
    onWorkflowClick: () -> Unit,
    onNotificationDemoClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF191919), Color(0xFF333639))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        // Main Centered Panel (flat, no shadow)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xFF23272A).copy(alpha = 0.98f))
                .padding(horizontal = 34.dp, vertical = 38.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                Text(
                    text = "Choose Action",
                    fontSize = 28.sp,
                    color = Color(0xFF8AB4F8),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Workflow Setup Button
                WorkflowButton(onWorkflowClick)

                ModelOptionBox(
                    title = "Instruction Model",
                    onClick = onInstructionModelClick,
                    backgroundColor = Color(0xFF3EECAC)
                )

                ModelOptionBox(
                    title = "MultiModal Model",
                    onClick = onMultiModalClick,
                    backgroundColor = Color(0x6600FDFF)
                )

                // New Notification Demo Button
                NotificationDemoButton(onNotificationDemoClick)
            }
        }

        // Logout Button - bottom center (flat, not shadowed)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 38.dp),
            contentAlignment = Alignment.Center
        ) {
            LogoutButton(onLogoutClick)
        }
    }
}

@Composable
fun NotificationDemoButton(onNotificationDemoClick: () -> Unit) {
    Button(
        onClick = onNotificationDemoClick,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
        modifier = Modifier
            .height(56.dp)
            .width(200.dp)
    ) {
        Text(
            text = "NOTIFICATION DEMO",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun WorkflowButton(onWorkflowClick: () -> Unit) {
    Button(
        onClick = onWorkflowClick,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35)),
        modifier = Modifier
            .height(56.dp)
            .width(200.dp)
    ) {
        Text(
            text = "SET WORKFLOW",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun LogoutButton(onLogoutClick: () -> Unit) {
    Button(
        onClick = onLogoutClick,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA3838)),
        modifier = Modifier
            .height(48.dp)
            .width(128.dp)
    ) {
        Text("Logout", color = Color.White, fontSize = 17.sp)
    }
}

@Composable
fun ModelOptionBox(title: String, onClick: () -> Unit, backgroundColor: Color) {
    // For a more tactile/modern feel, you can add a slight ripple, but *no* shadow.
    Box(
        modifier = Modifier
            .size(width = 200.dp, height = 56.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor.copy(alpha = 0.96f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.Black,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(12.dp)
        )
    }
}