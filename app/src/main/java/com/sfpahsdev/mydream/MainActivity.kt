package com.sfpahsdev.mydream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.sfpahsdev.mydream.lab.MyDreamLabRoute
import com.sfpahsdev.mydream.ui.theme.MyDreamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyDreamTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MyDreamLabRoute(
                        activity = this,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
