package com.vibe.app.presentation.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import com.vibe.app.presentation.common.LocalDynamicTheme
import com.vibe.app.presentation.common.LocalThemeMode
import com.vibe.app.presentation.common.SetupNavGraph
import com.vibe.app.presentation.common.ThemeSettingProvider
import com.vibe.app.presentation.theme.GPTMobileTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().apply {
            setKeepOnScreenCondition {
                !mainViewModel.isReady.value
            }
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Prevent keyboard from pushing the entire view up - composable handles insets via imePadding()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        setContent {
            val navController = rememberNavController()

            ThemeSettingProvider {
                GPTMobileTheme(
                    dynamicTheme = LocalDynamicTheme.current,
                    themeMode = LocalThemeMode.current
                ) {
                    SetupNavGraph(navController)
                }
            }
        }
    }
}
