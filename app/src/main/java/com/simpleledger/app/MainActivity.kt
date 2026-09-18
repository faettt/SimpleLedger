package com.simpleledger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.simpleledger.app.ui.AppRoot
import com.simpleledger.app.ui.theme.SimpleLedgerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimpleLedgerTheme {
                AppRoot()
            }
        }
    }
}
