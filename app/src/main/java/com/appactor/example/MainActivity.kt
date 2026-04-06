package com.appactor.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.appactor.example.ui.ExampleDashboardScreen
import com.appactor.example.ui.theme.AppActorExampleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppActorExampleTheme {
                ExampleDashboardScreen(activity = this)
            }
        }
    }
}
