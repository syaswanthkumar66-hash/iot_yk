package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun YkpAppRoot(viewModel: YkpViewModel) {
    val currentRoute = viewModel.currentRoute

    // BackHandler binds the platform physical go-back button events
    // to our ViewModel backstack pops. Safe navigation.
    BackHandler(enabled = viewModel.backStack.isNotEmpty()) {
        viewModel.navigateBack()
    }

    if (currentRoute is ScreenRoute.Login) {
        LoginScreen(viewModel = viewModel)
    } else {
        Scaffold(
            bottomBar = {
                // Bottom bar visible ONLY when inside one of the four main navigation hubs
                if (currentRoute is ScreenRoute.Home ||
                    currentRoute is ScreenRoute.Groups ||
                    currentRoute is ScreenRoute.Automation ||
                    currentRoute is ScreenRoute.Settings
                ) {
                    NavigationBar(
                        containerColor = PremiumCardBg,
                        contentColor = PremiumAccent,
                        tonalElevation = 8.dp
                    ) {
                        NavigationBarItem(
                            selected = currentRoute is ScreenRoute.Home,
                            onClick = { viewModel.navigateTo(ScreenRoute.Home) },
                            icon = { Icon(Icons.Default.Home, "Devices") },
                            label = { Text("Devices", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PremiumGlowGreen,
                                selectedTextColor = PremiumGlowGreen,
                                indicatorColor = PremiumAccent.copy(alpha = 0.2f),
                                unselectedIconColor = Color.LightGray.copy(0.4f),
                                unselectedTextColor = Color.LightGray.copy(0.4f)
                            )
                        )

                        NavigationBarItem(
                            selected = currentRoute is ScreenRoute.Groups,
                            onClick = { viewModel.navigateTo(ScreenRoute.Groups) },
                            icon = { Icon(Icons.Default.AccountCircle, "Groups") },
                            label = { Text("Groups", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PremiumGlowGreen,
                                selectedTextColor = PremiumGlowGreen,
                                indicatorColor = PremiumAccent.copy(alpha = 0.2f),
                                unselectedIconColor = Color.LightGray.copy(0.4f),
                                unselectedTextColor = Color.LightGray.copy(0.4f)
                            )
                        )

                        NavigationBarItem(
                            selected = currentRoute is ScreenRoute.Automation,
                            onClick = { viewModel.navigateTo(ScreenRoute.Automation) },
                            icon = { Icon(Icons.Default.Refresh, "Automation") },
                            label = { Text("Automation", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PremiumGlowGreen,
                                selectedTextColor = PremiumGlowGreen,
                                indicatorColor = PremiumAccent.copy(alpha = 0.2f),
                                unselectedIconColor = Color.LightGray.copy(0.4f),
                                unselectedTextColor = Color.LightGray.copy(0.4f)
                            )
                        )

                        NavigationBarItem(
                            selected = currentRoute is ScreenRoute.Settings,
                            onClick = { viewModel.navigateTo(ScreenRoute.Settings) },
                            icon = { Icon(Icons.Default.Settings, "Settings") },
                            label = { Text("Settings", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PremiumGlowGreen,
                                selectedTextColor = PremiumGlowGreen,
                                indicatorColor = PremiumAccent.copy(alpha = 0.2f),
                                unselectedIconColor = Color.LightGray.copy(0.4f),
                                unselectedTextColor = Color.LightGray.copy(0.4f)
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentRoute) {
                    is ScreenRoute.Home -> DeviceListScreen(viewModel = viewModel)
                    is ScreenRoute.Groups -> GroupScreen(viewModel = viewModel)
                    is ScreenRoute.Automation -> AutomationScreen(viewModel = viewModel)
                    is ScreenRoute.Settings -> SettingsScreen(viewModel = viewModel)
                    is ScreenRoute.DeviceDetail -> DeviceDetailScreen(viewModel = viewModel)
                    is ScreenRoute.Provisioning -> ProvisioningScreen(viewModel = viewModel)
                    is ScreenRoute.BleDiagnostics -> BleDiagnosticsScreen(viewModel = viewModel)
                    is ScreenRoute.NetworkScanner -> NetworkScannerScreen(viewModel = viewModel)
                    else -> {}
                }
            }
        }
    }
}
