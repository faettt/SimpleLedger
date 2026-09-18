package com.simpleledger.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 品牌色：青绿（记账本的干净感）
private val BrandTeal = Color(0xFF0F766E)
private val BrandTealDark = Color(0xFF5EEAD4)
private val IncomeGreen = Color(0xFF16A34A)
private val IncomeGreenDark = Color(0xFF4ADE80)
private val ExpenseRed = Color(0xFFDC2626)
private val ExpenseRedDark = Color(0xFFF87171)

private val LightColors = lightColorScheme(
    primary = BrandTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF0E7490),
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF083344),
    tertiary = IncomeGreen,
    error = ExpenseRed,
)

private val DarkColors = darkColorScheme(
    primary = BrandTealDark,
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF0F766E),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF22D3EE),
    secondaryContainer = Color(0xFF155E75),
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = IncomeGreenDark,
    error = ExpenseRedDark,
)

/** 支出 / 收入语义色（随明暗模式切换） */
@Composable
fun expenseColor(): Color = if (isSystemInDarkTheme()) ExpenseRedDark else ExpenseRed

@Composable
fun incomeColor(): Color = if (isSystemInDarkTheme()) IncomeGreenDark else IncomeGreen

@Composable
fun SimpleLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
