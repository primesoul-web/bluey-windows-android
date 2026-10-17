package dev.bluey.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Skeleton() }
    }
}

@Composable
private fun Skeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Bluey",
            color = Color(0xFFA9BCFF),
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Android client — skeleton build (M0)",
            color = Color(0xFFB9B2CC),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Protocol + pairing arrive in M1. See PARITY.md.",
            color = Color(0xFF5D5873),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
