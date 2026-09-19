package com.simpleledger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.simpleledger.app.data.settings.AppSettings
import com.simpleledger.app.data.settings.LocalHideAmounts
import com.simpleledger.app.ui.AppRoot
import com.simpleledger.app.ui.theme.SimpleLedgerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = (application as LedgerApp).container.settings

        setContent {
            val themeMode by settings.themeMode.collectAsState()
            val dynamicColor by settings.dynamicColor.collectAsState()
            val hideAmounts by settings.hideAmounts.collectAsState()

            val darkTheme = when (themeMode) {
                AppSettings.ThemeMode.LIGHT -> false
                AppSettings.ThemeMode.DARK -> true
                else -> isSystemInDarkTheme()
            }

            CompositionLocalProvider(LocalHideAmounts provides hideAmounts) {
                SimpleLedgerTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                    AppRoot()
                }
            }
        }
    }
}
