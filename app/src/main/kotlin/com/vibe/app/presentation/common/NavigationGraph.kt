package com.vibe.app.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.vibe.app.presentation.ui.chat.ChatScreen
import com.vibe.app.presentation.ui.home.HomeScreen
import com.vibe.app.presentation.ui.setting.AboutScreen
import com.vibe.app.presentation.ui.setting.AddPlatformScreen
import com.vibe.app.presentation.ui.setting.LicenseScreen
import com.vibe.app.presentation.ui.setting.PlatformSettingScreen
import com.vibe.app.presentation.ui.setting.SettingScreen
import com.vibe.app.presentation.ui.setting.SettingViewModelV2
import com.vibe.app.presentation.ui.setup.SetupCompleteScreen
import com.vibe.app.presentation.ui.setup.SetupPlatformTypeScreen
import com.vibe.app.presentation.ui.setup.SetupPlatformWizardScreen
import com.vibe.app.presentation.ui.setup.SetupViewModelV2
import com.vibe.app.presentation.ui.startscreen.StartScreen
import com.vibe.app.presentation.ui.startscreen.StartViewModel

@Composable
fun SetupNavGraph(navController: NavHostController) {
    NavHost(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        navController = navController,
        startDestination = Route.CHAT_LIST
    ) {
        homeScreenNavigation(navController)
        startScreenNavigation(navController)
        setupNavigation(navController)
        settingNavigation(navController)
        chatScreenNavigation(navController)
    }
}

fun NavGraphBuilder.startScreenNavigation(navController: NavHostController) {
    composable(Route.GET_STARTED) {
        val startViewModel: StartViewModel = hiltViewModel()
        val uiState by startViewModel.uiState.collectAsState()
        StartScreen(
            onStartClick = { navController.navigate(Route.SETUP_ROUTE) },
            isInitializing = uiState.isInitializing,
            statusMessage = uiState.statusMessage,
        )
    }
}

fun NavGraphBuilder.setupNavigation(
    navController: NavHostController
) {
    navigation(startDestination = Route.SETUP_PLATFORM_TYPE, route = Route.SETUP_ROUTE) {
        composable(route = Route.SETUP_PLATFORM_TYPE) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETUP_ROUTE)
            }
            val setupViewModel: SetupViewModelV2 = hiltViewModel(parentEntry)
            SetupPlatformTypeScreen(
                setupViewModel = setupViewModel,
                onPlatformTypeSelected = { navController.navigate(Route.SETUP_PLATFORM_WIZARD) },
                onBackAction = { navController.navigateUp() }
            )
        }
        composable(route = Route.SETUP_PLATFORM_WIZARD) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETUP_ROUTE)
            }
            val setupViewModel: SetupViewModelV2 = hiltViewModel(parentEntry)
            SetupPlatformWizardScreen(
                setupViewModel = setupViewModel,
                onComplete = {
                    navController.navigate(Route.SETUP_COMPLETE) {
                        popUpTo(Route.SETUP_ROUTE) { inclusive = false }
                    }
                },
                onBackAction = { navController.navigateUp() }
            )
        }
        composable(route = Route.SETUP_COMPLETE) {
            SetupCompleteScreen(
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo(Route.GET_STARTED) { inclusive = true }
                    }
                },
                onBackAction = { navController.navigateUp() }
            )
        }
    }
}

fun NavGraphBuilder.homeScreenNavigation(navController: NavHostController) {
    composable(Route.CHAT_LIST) {
        HomeScreen(
            settingOnClick = { navController.navigate(Route.SETTING_ROUTE) { launchSingleTop = true } },
            onProjectClick = { chatId, enabledPlatforms ->
                val enabledPlatformString = enabledPlatforms.joinToString(",")
                navController.navigate(
                    Route.CHAT_ROOM
                        .replace("{chatRoomId}", "$chatId")
                        .replace("{enabledPlatforms}", enabledPlatformString),
                )
            },
            navigateToChat = { chatId, enabledPlatforms ->
                val enabledPlatformString = enabledPlatforms.joinToString(",")
                navController.navigate(
                    Route.CHAT_ROOM
                        .replace("{chatRoomId}", "$chatId")
                        .replace("{enabledPlatforms}", enabledPlatformString),
                )
            },
        )
    }
}

fun NavGraphBuilder.chatScreenNavigation(navController: NavHostController) {
    composable(
        Route.CHAT_ROOM,
        arguments = listOf(
            navArgument("chatRoomId") { type = NavType.IntType },
            navArgument("enabledPlatforms") { defaultValue = "" }
        )
    ) {
        ChatScreen(
            onBackAction = { navController.navigateUp() }
        )
    }
}

fun NavGraphBuilder.settingNavigation(navController: NavHostController) {
    navigation(startDestination = Route.SETTINGS, route = Route.SETTING_ROUTE) {
        composable(Route.SETTINGS) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            SettingScreen(
                settingViewModel = settingViewModel,
                onNavigationClick = { navController.navigateUp() },
                onNavigateToAddPlatform = { navController.navigate(Route.ADD_PLATFORM) },
                onNavigateToPlatformSetting = { platformUid ->
                    navController.navigate(
                        Route.PLATFORM_SETTINGS.replace("{platformUid}", platformUid)
                    )
                },
                onNavigateToAboutPage = { navController.navigate(Route.ABOUT_PAGE) }
            )
        }
        composable(Route.ADD_PLATFORM) {
            val parentEntry = remember(it) {
                navController.getBackStackEntry(Route.SETTING_ROUTE)
            }
            val settingViewModel: SettingViewModelV2 = hiltViewModel(parentEntry)
            AddPlatformScreen(
                onNavigationClick = { navController.navigateUp() },
                onSave = { platform ->
                    settingViewModel.addPlatform(platform)
                    navController.navigateUp()
                }
            )
        }
        composable(
            Route.PLATFORM_SETTINGS,
            arguments = listOf(navArgument("platformUid") { type = NavType.StringType })
        ) {
            PlatformSettingScreen(
                onNavigationClick = { navController.navigateUp() }
            )
        }
        composable(Route.ABOUT_PAGE) {
            AboutScreen(
                onNavigationClick = { navController.navigateUp() },
                onNavigationToLicense = { navController.navigate(Route.LICENSE) }
            )
        }
        composable(Route.LICENSE) {
            LicenseScreen(onNavigationClick = { navController.navigateUp() })
        }
    }
}
