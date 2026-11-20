package com.llmhub.llmhub.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.llmhub.llmhub.R
import com.llmhub.llmhub.navigation.Screen
import com.llmhub.llmhub.ui.LocalLanApiRunning

/** Shared palette for the neural / system-monitor home UI. */
object NeuralHubColors {
    val Bg = Color(0xFF050507)
    val Glass = Color(0x08FFFFFF)
    val GlassBorder = Color(0x14FFFFFF)
    val GlassHighlight = Color(0x1EFFFFFF)
    val AccentCyan = Color(0xFF00F2FF)
    val AccentMagenta = Color(0xFFFF00EA)
    val AccentGreen = Color(0xFF00FFAA)
    val TextDim = Color(0xFF88888E)
}

enum class NeuralHubTab {
    HOME,
    CHAT,
    MODELS,
    SETTINGS,
    ABOUT,
    OTHER
}

fun neuralTabFromRoute(route: String?): NeuralHubTab {
    if (route == null) return NeuralHubTab.OTHER
    return when {
        route == Screen.Home.route -> NeuralHubTab.HOME
        route == Screen.Models.route -> NeuralHubTab.MODELS
        route == Screen.Settings.route -> NeuralHubTab.SETTINGS
        route == Screen.About.route -> NeuralHubTab.ABOUT
        route == Screen.ChatHistory.route || route.startsWith("chat/") -> NeuralHubTab.CHAT
        else -> NeuralHubTab.OTHER
    }
}

/**
 * Full-screen shell: neural background + main content + bottom primary navigation.
 */
@Composable
fun NeuralHubShell(
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val tab = neuralTabFromRoute(navBackStackEntry?.destination?.route)
    val lanApiRunning = LocalLanApiRunning.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeuralHubColors.Bg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0x2D00F2FF),
                                Color.Transparent
                            ),
                            center = Offset(0f, 0f),
                            radius = size.width * 0.8f
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0x2DFF00EA),
                                Color.Transparent
                            ),
                            center = Offset(size.width, size.height),
                            radius = size.width * 0.8f
                        )
                    )
                }
        )
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                content()
            }
            NeuralHubBottomBar(
                selectedTab = tab,
                lanApiBlocksChat = lanApiRunning,
                onNavigateHome = {
                    navController.navigate(Screen.Home.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateChat = {
                    navController.navigate(Screen.Chat.createRoute("new")) {
                        launchSingleTop = true
                    }
                },
                onNavigateModels = {
                    navController.navigate(Screen.Models.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateSettings = {
                    navController.navigate(Screen.Settings.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateAbout = {
                    navController.navigate(Screen.About.route) {
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}

@Composable
private fun NeuralHubBottomBar(
    selectedTab: NeuralHubTab,
    lanApiBlocksChat: Boolean,
    onNavigateHome: () -> Unit,
    onNavigateChat: () -> Unit,
    onNavigateModels: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(Color(0xCC0A0A0C))
            .drawBehind {
                drawLine(
                    color = NeuralHubColors.GlassBorder,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1f
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            NavItem(
                icon = Icons.Default.Apps,
                isActive = selectedTab == NeuralHubTab.HOME,
                enabled = true,
                contentDescription = null,
                disabledMessage = null,
                onClick = onNavigateHome
            )
            NavItem(
                icon = Icons.AutoMirrored.Filled.Chat,
                isActive = selectedTab == NeuralHubTab.CHAT,
                enabled = !lanApiBlocksChat,
                contentDescription = stringResource(R.string.feature_ai_chat),
                disabledMessage = stringResource(R.string.nav_chat_disabled_lan_api),
                onClick = onNavigateChat
            )
            NavItem(
                icon = Icons.Default.Download,
                isActive = selectedTab == NeuralHubTab.MODELS,
                enabled = true,
                contentDescription = null,
                disabledMessage = null,
                onClick = onNavigateModels
            )
            NavItem(
                icon = Icons.Outlined.Settings,
                isActive = selectedTab == NeuralHubTab.SETTINGS,
                enabled = true,
                contentDescription = null,
                disabledMessage = null,
                onClick = onNavigateSettings
            )
            NavItem(
                icon = Icons.Outlined.Info,
                isActive = selectedTab == NeuralHubTab.ABOUT,
                enabled = true,
                contentDescription = null,
                disabledMessage = null,
                onClick = onNavigateAbout
            )
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    isActive: Boolean,
    enabled: Boolean,
    contentDescription: String?,
    disabledMessage: String?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable {
                if (enabled) {
                    onClick()
                } else if (disabledMessage != null) {
                    Toast.makeText(context, disabledMessage, Toast.LENGTH_SHORT).show()
                }
            }
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> NeuralHubColors.TextDim.copy(alpha = 0.35f)
                isActive -> Color.White
                else -> NeuralHubColors.TextDim
            },
            modifier = Modifier.size(24.dp)
        )
        if (isActive) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(NeuralHubColors.AccentCyan, CircleShape)
            )
        }
    }
}
