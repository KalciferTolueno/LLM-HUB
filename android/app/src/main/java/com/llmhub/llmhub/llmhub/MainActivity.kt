package com.llmhub.llmhub

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import com.llmhub.llmhub.navigation.LlmHubNavigation
import com.llmhub.llmhub.ui.theme.LlmHubTheme
import com.llmhub.llmhub.viewmodels.ChatViewModelFactory
import com.llmhub.llmhub.viewmodels.ThemeViewModel
import com.llmhub.llmhub.utils.LocaleHelper
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private lateinit var themeViewModel: ThemeViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as LlmHubApplication
        val chatRepository = app.chatRepository
        val chatViewModelFactory = ChatViewModelFactory(app, chatRepository, this)

        // Initialize ThemeViewModel with InferenceService
        themeViewModel = ThemeViewModel(this, app.inferenceService)

        enableEdgeToEdge()
        applyNeuralNavigationBarStyle()

        setContent {
            val currentThemeMode by themeViewModel.themeMode.collectAsState()
            val currentLanguage by themeViewModel.appLanguage.collectAsState()

            // Apply locale (updates Context resources + AppCompat locales)
            LocaleHelper.setLocale(this@MainActivity, currentLanguage)

            // RTL locales: Persian (fa), Arabic (ar), Hebrew (he/iw)
            val isRtl = currentLanguage in setOf("fa", "ar", "he", "iw")
            val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

            LlmHubTheme(themeMode = currentThemeMode, dynamicColor = false) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            themeViewModel.syncLocalApiServerState()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    LlmHubNavigation(
                        navController = navController,
                        chatViewModelFactory = chatViewModelFactory,
                        themeViewModel = themeViewModel
                    )
                }
                } // CompositionLocalProvider
            }
        }
    }
    
    /**
     * Match [com.llmhub.llmhub.components.NeuralHubShell] bottom bar scrim so the
     * system gesture/navigation area is not a transparent strip below it.
     */
    private fun applyNeuralNavigationBarStyle() {
        val window = window
        // Same ARGB as NeuralHubBottomBar: Color(0xCC0A0A0C)
        window.navigationBarColor = AndroidColor.argb(0xCC, 0x0A, 0x0A, 0x0C)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Dark bar → light (white) navigation icons
            isAppearanceLightNavigationBars = false
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Get the saved language preference and apply locale configuration
        val themePrefs = com.llmhub.llmhub.data.ThemePreferences(newBase)
        val savedLanguage = try {
            // Try to get the saved language synchronously
            kotlinx.coroutines.runBlocking {
                themePrefs.appLanguage.first()
            }
        } catch (e: Exception) {
            null // Fall back to system default if we can't read preferences
        }
        
        super.attachBaseContext(LocaleHelper.setLocale(newBase, savedLanguage))
    }
}