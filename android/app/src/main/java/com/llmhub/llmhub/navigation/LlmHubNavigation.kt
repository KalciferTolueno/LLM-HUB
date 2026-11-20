package com.llmhub.llmhub.navigation



import androidx.compose.runtime.Composable

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.remember

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.runtime.setValue

import androidx.compose.ui.platform.LocalContext

import androidx.compose.material3.DrawerState

import androidx.compose.material3.DrawerValue

import androidx.navigation.compose.currentBackStackEntryAsState

import kotlinx.coroutines.launch

import androidx.navigation.NavHostController

import androidx.navigation.compose.NavHost

import androidx.navigation.compose.composable

import com.llmhub.llmhub.LlmHubApplication

import com.llmhub.llmhub.components.NeuralHubShell

import com.llmhub.llmhub.screens.*

import com.llmhub.llmhub.viewmodels.ChatViewModelFactory

import com.llmhub.llmhub.viewmodels.ThemeViewModel

import androidx.activity.ComponentActivity

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import com.llmhub.llmhub.ui.LocalLanApiRunning



sealed class Screen(val route: String) {

    object Home : Screen("home")

    object Chat : Screen("chat/{chatId}?creatorId={creatorId}") {

        fun createRoute(chatId: String = "new", creatorId: String? = null): String {

            return if (creatorId != null) "chat/$chatId?creatorId=$creatorId" else "chat/$chatId"

        }

    }

    object ChatHistory : Screen("chat_history")

    object Settings : Screen("settings")

    object Models : Screen("models")

    object About : Screen("about")

    object Terms : Screen("terms")

    object LocalApi : Screen("local_api")

}



@Composable

fun LlmHubNavigation(

    navController: NavHostController,

    chatViewModelFactory: ChatViewModelFactory,

    themeViewModel: ThemeViewModel,

    startDestination: String = Screen.Home.route

) {

    val drawerState = rememberSaveable(saver = DrawerState.Saver(confirmStateChange = { true })) {

        DrawerState(DrawerValue.Closed)

    }



    // Unload chat model only when leaving Chat route (e.g. to Home). Don't unload when switching chat/123 -> chat/new.

    val navBackStackEntry by navController.currentBackStackEntryAsState()

    val currentRoute = navBackStackEntry?.destination?.route

    val isOnChatRoute = when (val r = currentRoute) {
        null -> false
        Screen.Chat.route -> true
        else -> r.startsWith("chat/")
    }

    var wasOnChatRoute by remember { mutableStateOf(isOnChatRoute) }

    val context = LocalContext.current

    val activity = context as? ComponentActivity



    LaunchedEffect(isOnChatRoute) {

        if (wasOnChatRoute && !isOnChatRoute) {

            (context.applicationContext as? LlmHubApplication)?.inferenceService?.unloadModel()

            com.llmhub.llmhub.embedding.RagServiceManager.getInstance(context.applicationContext).cleanup()

        }

        wasOnChatRoute = isOnChatRoute

    }



    val lanApiRunning by themeViewModel.isApiServerRunning.collectAsState()

    CompositionLocalProvider(LocalLanApiRunning provides lanApiRunning) {
    NavHost(

        navController = navController,

        startDestination = startDestination

    ) {

        composable(Screen.Home.route) {
            NeuralHubShell(navController) {
                SystemMonitorHomeScreen(
                    themeViewModel = themeViewModel,
                    onNavigateToChatHistory = {
                        navController.navigate(Screen.ChatHistory.route)
                    },
                    onNavigateToModels = {
                        navController.navigate(Screen.Models.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToAbout = {
                        navController.navigate(Screen.About.route)
                    },
                    onNavigateToLocalApi = {
                        navController.navigate(Screen.LocalApi.route)
                    }
                )
            }
        }

        // Chat History Screen

        composable(Screen.ChatHistory.route) {

            NeuralHubShell(navController) {
                ChatHistoryScreen(

                    onNavigateBack = {

                        navController.popBackStack()

                    },

                    onNavigateToChat = { chatId ->

                        navController.navigate(Screen.Chat.createRoute(chatId))

                    },

                    onCreateNewChat = {

                        navController.navigate(Screen.Chat.createRoute("new"))

                    }

                )
            }

        }

        

        // Chat Screen (existing functionality preserved)

        composable(

            route = Screen.Chat.route,

            arguments = listOf(

                androidx.navigation.navArgument("chatId") { defaultValue = "new" },

                androidx.navigation.navArgument("creatorId") { nullable = true; defaultValue = null }

            )

        ) { backStackEntry ->

            val chatId = backStackEntry.arguments?.getString("chatId") ?: "new"

            val creatorId = backStackEntry.arguments?.getString("creatorId")



            // Interstitial ads are disabled in the current build.


            // We need to pass creatorId to ChatScreen/ViewModel somehow.

            // Since ChatScreen takes a ViewModel, we might need to update ChatScreen signature

            // or rely on ViewModel to handle "new" chat with params.

            // Actually, ChatScreen instantiates the ViewModel. We should pass arguments there.

            

            NeuralHubShell(navController) {
                ChatScreen(

                    chatId = chatId,

                    creatorId = creatorId,

                    viewModelFactory = chatViewModelFactory,

                    onNavigateToSettings = {

                        navController.navigate(Screen.Settings.route)

                    },

                    onNavigateToModels = {

                        navController.navigate(Screen.Models.route)

                    },

                    onNavigateToChat = { newChatId ->

                        navController.navigate(Screen.Chat.createRoute(newChatId)) {

                            popUpTo(Screen.Chat.route) { inclusive = true }

                        }

                    },

                    onNavigateBack = {

                        navController.popBackStack(Screen.Home.route, inclusive = false)

                    },

                    drawerState = drawerState

                )
            }

        }

        composable(Screen.Settings.route) {
            NeuralHubShell(navController) {
                SettingsScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToModels = {
                        navController.navigate(Screen.Models.route)
                    },
                    onNavigateToAbout = {
                        navController.navigate(Screen.About.route)
                    },
                    onNavigateToTerms = {
                        navController.navigate(Screen.Terms.route)
                    },
                    themeViewModel = themeViewModel
                )
            }
        }
        
        composable(Screen.Models.route) {
            NeuralHubShell(navController) {
                ModelDownloadScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
        
        composable(Screen.About.route) {
            NeuralHubShell(navController) {
                AboutScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
        
        composable(Screen.Terms.route) {
            NeuralHubShell(navController) {
                TermsOfServiceScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(Screen.LocalApi.route) {
            NeuralHubShell(navController) {
                LocalApiScreen(
                    navController = navController,
                    themeViewModel = themeViewModel
                )
            }
        }
    }
    }
}