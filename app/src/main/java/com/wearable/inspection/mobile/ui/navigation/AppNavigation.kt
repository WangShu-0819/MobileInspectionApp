package com.wearable.inspection.mobile.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wearable.inspection.mobile.ui.BottomNavItem
import com.wearable.inspection.mobile.ui.screens.*

@Composable
fun AppRoot() {
    val navController = rememberNavController()

    // 二级页面隐藏底部导航
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute in listOf(
        Screen.LiveInspection.route,
        Screen.TraceRecords.route,
        Screen.Profile.route
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                BottomNavigationBar(navController = navController)
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.LiveInspection.route,
            modifier = if (showBottomBar) Modifier.padding(padding) else Modifier
        ) {
            // 一级页面
            composable(Screen.LiveInspection.route) {
                LiveInspectionScreen(
                    onStartInspection = { partId ->
                        navController.navigate(Screen.CameraPreview.createRoute(partId))
                    },
                    onOpenTemplates = {
                        navController.navigate(Screen.Profile.route) {
                            popUpTo(Screen.Profile.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onViewRecord = { recordId ->
                        navController.navigate(Screen.InspectionResult.createRoute(recordId))
                    },
                    onDpmScan = {
                        navController.navigate(Screen.DpmScan.route)
                    },
                    onStampOcr = {
                        navController.navigate(Screen.StampOcr.route)
                    },
                )
            }

            composable(Screen.TraceRecords.route) {
                TraceRecordsScreen(
                    onViewRecord = { recordId ->
                        navController.navigate(Screen.InspectionResult.createRoute(recordId))
                    }
                )
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    onOpenTemplateConfig = {
                        navController.navigate(Screen.TemplateConfig.route)
                    },
                    onOpenPartManagement = {
                        navController.navigate(Screen.PartManagement.route)
                    },
                    onOpenTemplatePackages = {
                        navController.navigate(Screen.TemplatePackages.route)
                    },
                    onOpenResultManagement = {
                        navController.navigate(Screen.ResultManagement.route)
                    },
                    onOpenSettings = {
                        navController.navigate(Screen.AppSettings.route)
                    }
                )
            }

            // 二级页面
            composable(Screen.TemplateConfig.route) {
                TemplateConfigScreen(
                    onBack = { navController.popBackStack() },
                    onTemplateClick = { templateId ->
                        navController.navigate(Screen.TemplateDetail.createRoute(templateId))
                    }
                )
            }

            composable(Screen.AppSettings.route) {
                AppSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.PartManagement.route) {
                PartManagementScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.TemplatePackages.route) {
                TemplatePackageScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.ResultManagement.route) {
                ResultManagementScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.CameraPreview.route,
                arguments = listOf(navArgument(Screen.CameraPreview.ARG_PART_ID) { type = NavType.StringType })
            ) { backStackEntry ->
                val partId = backStackEntry.arguments?.getString(Screen.CameraPreview.ARG_PART_ID) ?: return@composable
                CameraPreviewScreen(
                    partId = partId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.InspectionResult.route,
                arguments = listOf(navArgument(Screen.InspectionResult.ARG_SESSION_ID) { type = NavType.StringType })
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString(Screen.InspectionResult.ARG_SESSION_ID) ?: return@composable
                InspectionResultScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.TemplateDetail.route,
                arguments = listOf(navArgument(Screen.TemplateDetail.ARG_TEMPLATE_ID) { type = NavType.StringType })
            ) { backStackEntry ->
                val templateId = backStackEntry.arguments?.getString(Screen.TemplateDetail.ARG_TEMPLATE_ID) ?: return@composable
                TemplateDetailScreen(
                    templateId = templateId,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.DpmScan.route) {
                DpmScanScreen(
                    onBack = { navController.popBackStack() },
                    onResult = { code ->
                        // 解码结果回调，后续可导航到结果页
                    }
                )
            }

            composable(Screen.StampOcr.route) {
                StampOcrScreen(
                    onBack = { navController.popBackStack() },
                    onResult = { text ->
                        // OCR 结果回调，后续可导航到结果页
                    }
                )
            }
        }
    }
}
