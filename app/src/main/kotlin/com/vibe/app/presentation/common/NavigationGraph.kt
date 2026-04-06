package com.vibe.app.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.vibe.app.presentation.ui.diagnostic.DiagnosticScreen
import com.vibe.app.presentation.ui.home.HomeScreen
import com.vibe.app.presentation.ui.setting.AboutScreen
import com.vibe.app.presentation.ui.setting.LicenseScreen
import com.vibe.app.presentation.ui.setting.PlatformSettingScreen
import com.vibe.app.presentation.ui.setting.SettingScreen
import com.vibe.app.presentation.ui.setting.SettingViewModelV2
import com.vibe.app.presentation.ui.setup.SetupCompleteScreen
import com.vibe.app.presentation.ui.setup.SetupPlatformTypeScreen
import com.vibe.app.presentation.ui.setup.SetupPlatformWizardScreen
import com.vibe.app.presentation.ui.setup.SetupViewModelV2

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
        setupNavigation(navController)
        settingNavigation(navController)
        chatScreenNavigation(navController)
        diagnosticNavigation(navController)
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
                    val fromSettings = runCatching {
                        navController.getBackStackEntry(Route.SETTING_ROUTE)
                    }.isSuccess
                    val fromChat = runCatching {
                        navController.getBackStackEntry(Route.CHAT_ROOM)
                    }.isSuccess
                    if (fromSettings) {
                        navController.popBackStack(Route.SETTINGS, inclusive = false)
                    } else if (fromChat) {
                        // Return directly to the chat screen after adding API key
                        navController.popBackStack(Route.CHAT_ROOM, inclusive = false)
                    } else {
                        navController.navigate(Route.SETUP_COMPLETE) {
                            popUpTo(Route.SETUP_ROUTE) { inclusive = false }
                        }
                    }
                },
                onBackAction = { navController.navigateUp() }
            )
        }
        composable(route = Route.SETUP_COMPLETE) {
            SetupCompleteScreen(
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo(Route.SETUP_ROUTE) { inclusive = true }
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
    ) { backStackEntry ->
        val chatRoomId = backStackEntry.arguments?.getInt("chatRoomId") ?: return@composable
        ChatScreen(
            onNavigateToAddPlatform = {
                navController.navigate(Route.SETUP_ROUTE) { launchSingleTop = true }
            },
            onNavigateToDiagnostic = {
                navController.navigate(
                    Route.DIAGNOSTIC.replace("{chatRoomId}", "$chatRoomId")
                )
            },
            onBackAction = { navController.navigateUp() }
        )
    }
}

fun NavGraphBuilder.diagnosticNavigation(navController: NavHostController) {
    composable(
        Route.DIAGNOSTIC,
        arguments = listOf(navArgument("chatRoomId") { type = NavType.IntType })
    ) {
        DiagnosticScreen(
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
                onNavigateToAddPlatform = { navController.navigate(Route.SETUP_ROUTE) },
                onNavigateToPlatformSetting = { platformUid ->
                    navController.navigate(
                        Route.PLATFORM_SETTINGS.replace("{platformUid}", platformUid)
                    )
                },
                onNavigateToAboutPage = { navController.navigate(Route.ABOUT_PAGE) }
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
