package com.example.localllmapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.api.services.gmail.GmailScopes

@Suppress("DEPRECATION")
class LoginActivity : ComponentActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var firebaseAuth: FirebaseAuth
    private val RC_SIGN_IN = 1001

    private var isLoading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        firebaseAuth = FirebaseAuth.getInstance()

        // Check if user is already signed in
        if (firebaseAuth.currentUser != null) {
            // Check if they have Gmail permissions
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (account != null && hasGmailPermissions(account)) {
                // User has everything, go to main screen
                startActivity(Intent(this, ModelSelectionActivity::class.java))
                finish()
                return
            }
            // User is signed in but missing Gmail permissions
            // Continue to show login screen to request permissions
        }

        // Configure sign-in to request Gmail permissions from the start
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .requestScopes(
                Scope(GmailScopes.GMAIL_READONLY),
                Scope(GmailScopes.GMAIL_SEND),
                Scope(GmailScopes.GMAIL_MODIFY)
            )
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            CompanionAILoginScreen(
                isLoading = isLoading,
                onGoogleSignInClick = {
                    isLoading = true
                    signIn()
                }
            )
        }
    }

    private fun hasGmailPermissions(account: GoogleSignInAccount): Boolean {
        val requiredScopes = listOf(
            GmailScopes.GMAIL_READONLY,
            GmailScopes.GMAIL_SEND,
            GmailScopes.GMAIL_MODIFY
        )
        return requiredScopes.all { scope ->
            account.grantedScopes.any { it.scopeUri == scope }
        }
    }

    private fun signIn() {
        // Sign out first to ensure fresh permission request
        googleSignInClient.signOut().addOnCompleteListener {
            // Now sign in with Gmail permissions
            startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)!!

                // Check if Gmail permissions were granted
                if (!hasGmailPermissions(account)) {
                    isLoading = false
                    Toast.makeText(
                        this,
                        "Gmail permissions are required for workflow automation",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }

                val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                firebaseAuth.signInWithCredential(credential).addOnCompleteListener { authTask ->
                    isLoading = false
                    if (authTask.isSuccessful) {
                        startActivity(Intent(this, ModelSelectionActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this, "Login Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: ApiException) {
                isLoading = false
                Toast.makeText(this, "Google Sign In Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun CompanionAILoginScreen(isLoading: Boolean, onGoogleSignInClick: () -> Unit) {
    val gradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0F2027),
            Color(0xFF2C5364),
            Color(0xFF203A43)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 64.dp)
        ) {
            // AI Logo — replace with your own asset
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground), // Using default icon
                contentDescription = "AI Logo",
                modifier = Modifier
                    .size(120.dp)
                    .padding(top = 24.dp, bottom = 16.dp),
                contentScale = ContentScale.Fit
            )

            Text(
                text = "Welcome to IntraAI",
                fontSize = 24.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 64.dp)
            )

            // "Sign in:" label
            Text(
                text = "Sign in:",
                fontSize = 18.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            // Spacing
            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp)
                )
            } else {
                GoogleSignInButton(onClick = onGoogleSignInClick)
            }
        }
    }
}

@Composable
fun GoogleSignInButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White,
        shadowElevation = 8.dp,
        modifier = Modifier
            .height(56.dp)
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground), // Using default icon
                contentDescription = "Google Logo",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Continue with Google",
                fontSize = 16.sp,
                color = Color.Black
            )
        }
    }
}