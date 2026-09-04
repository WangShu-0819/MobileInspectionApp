package com.wearable.inspection.mobile.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.wearable.inspection.mobile.MobileInspectionApp
import com.wearable.inspection.mobile.data.settings.PartSelectionBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wearable.inspection.mobile.ui.BottomNavItem
import com.wearable.inspection.mobile.ui.screens.*
import com.wearable.inspection.mobile.ui.screens.workbench.ViewCompletionResult
import com.wearable.inspection.mobile.ui.screens.workbench.WorkbenchViewModel
import com.wearable.inspection.mobile.ui.screens.workbench.createWorkbenchViewModelFactory

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val workbenchViewModel: WorkbenchViewModel = viewModel(
        factory = createWorkbenchViewModelFactory(
            context.applicationContext as android.app.Application
        )
    )
    val repository = remember { MobileInspectionApp.repository(context) }
    val scope = rememberCoroutineScope()

    // 二级页面隐藏底部导航
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    // 只允许三个一级 Tab 拥有根级导航；确认/导出等子流程即使发生过渡也不显示它。
    val routeKey = currentRoute?.substringBefore('/')
    val isCaptureSubFlow = routeKey == "view_confirmation" || routeKey == "export_result"
    val showBottomBar = routeKey != null && !isCaptureSubFlow && routeKey in setOf(
        Screen.LiveInspection.route,
        Screen.TraceRecords.route,
        Screen.Profile.route,
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
            modifier = if (showBottomBar) Modifier.padding(padding) else Modifier,
            // 确认/导出子流程不做淡入淡出：现场页仍带 CameraX 预览，若在切页期间
            // 淡出，根级底部导航和现场拍照栏会先被隐藏，用户会看到“实时图+模板图+
            // 空白+透明度栏”的残影。一级页面仍保留轻量过渡。
            enterTransition = {
                if (targetState.destination.route?.startsWith("view_confirmation") == true ||
                    targetState.destination.route?.startsWith("export_result") == true
                ) EnterTransition.None else fadeIn(animationSpec = tween(180))
            },
            exitTransition = {
                if (targetState.destination.route?.startsWith("view_confirmation") == true ||
                    targetState.destination.route?.startsWith("export_result") == true
                ) ExitTransition.None else fadeOut(animationSpec = tween(120))
            },
            popEnterTransition = {
                if (initialState.destination.route?.startsWith("view_confirmation") == true ||
                    initialState.destination.route?.startsWith("export_result") == true
                ) EnterTransition.None else fadeIn(animationSpec = tween(180))
            },
            popExitTransition = {
                if (initialState.destination.route?.startsWith("view_confirmation") == true ||
                    initialState.destination.route?.startsWith("export_result") == true
                ) ExitTransition.None else fadeOut(animationSpec = tween(120))
            },
        ) {
            // 一级页面
            composable(Screen.LiveInspection.route) {
                LiveInspectionScreen(
                    viewModel = workbenchViewModel,
                    isScreenVisible = currentRoute == Screen.LiveInspection.route,
                    onOpenTemplates = {
                        navController.navigate(Screen.TemplateConfig.route)
                    },
                    onDpmScan = {
                        navController.navigate(Screen.DpmScan.route)
                    },
                    onStampOcr = {
                        navController.navigate(Screen.StampOcr.route)
                    },
                    onNavigateToConfirm = { batchId, photoId, photoPath, viewIndex, templateId, templateName, partId, totalViews ->
                        navController.navigate(
                            Screen.ViewConfirmation.createRoute(
                                batchId = batchId,
                                photoId = photoId,
                                photoPath = photoPath,
                                viewIndex = viewIndex,
                                templateId = templateId,
                                templateName = templateName,
                                partId = partId,
                                totalViews = totalViews
                            )
                        )
                    },
                    onNavigateToExport = { batchId, partId, partName ->
                        navController.navigate(
                            Screen.ExportResult.createRoute(
                                batchId = batchId,
                                partId = partId,
                                partName = partName
                            )
                        )
                    }
                )
            }

            composable(Screen.TraceRecords.route) {
                TraceRecordsScreen()
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
            // TemplateConfig 重定向到 PartList
            composable(Screen.TemplateConfig.route) {
                PartListScreen(
                    onBack = { navController.popBackStack() },
                    onPartClick = { partId ->
                        navController.navigate(Screen.PartDetail.createRoute(partId))
                    },
                    onBindDpm = { partId ->
                        navController.navigate(Screen.DpmBind.createRoute(partId))
                    },
                    onPartCreated = { partId ->
                        navController.navigate(Screen.PartDetail.createRoute(partId))
                    },
                )
            }

            // 零件列表
            composable(Screen.PartList.route) {
                PartListScreen(
                    onBack = { navController.popBackStack() },
                    onPartClick = { partId ->
                        navController.navigate(Screen.PartDetail.createRoute(partId))
                    },
                    onBindDpm = { partId ->
                        navController.navigate(Screen.DpmBind.createRoute(partId))
                    },
                    onPartCreated = { partId ->
                        navController.navigate(Screen.PartDetail.createRoute(partId))
                    },
                )
            }

            // 零件详情（视角网格）
            composable(
                route = Screen.PartDetail.route,
                arguments = listOf(navArgument(Screen.PartDetail.ARG_PART_ID) { type = NavType.StringType })
            ) { backStackEntry ->
                val partId = backStackEntry.arguments?.getString(Screen.PartDetail.ARG_PART_ID)
                    ?: return@composable
                PartDetailScreen(
                    partId = partId,
                    onBack = { navController.popBackStack() },
                    onTemplateClick = { templateId ->
                        navController.navigate(Screen.TemplateDetail.createRoute(templateId))
                    },
                    onCaptureNew = { pid ->
                        navController.navigate(Screen.TemplateCapture.createRoute(pid))
                    },
                    onBindDpm = { pid ->
                        navController.navigate(Screen.DpmBind.createRoute(pid))
                    },
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
                    onBack = { navController.popBackStack() },
                    onRecapture = { partId, tplId ->
                        navController.navigate(Screen.TemplateCapture.createRecaptureRoute(partId, tplId))
                    },
                    onEditRoi = { tplId ->
                        navController.navigate(Screen.RoiEditor.createRoute(tplId))
                    },
                )
            }

            // ROI 编辑器
            composable(
                route = Screen.RoiEditor.route,
                arguments = listOf(navArgument(Screen.RoiEditor.ARG_TEMPLATE_ID) { type = NavType.StringType })
            ) { backStackEntry ->
                val templateId = backStackEntry.arguments?.getString(Screen.RoiEditor.ARG_TEMPLATE_ID)
                    ?: return@composable
                RoiEditorScreen(
                    templateId = templateId,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Screen.DpmScan.route) {
                val context = LocalContext.current
                val repository = remember { MobileInspectionApp.repository(context) }
                val scope = rememberCoroutineScope()
                DpmScanScreen(
                    onBack = { navController.popBackStack() },
                    onResult = { code ->
                        scope.launch {
                            val part = withContext(Dispatchers.IO) {
                                repository.getPartByDpmCode(code)
                            }
                            if (part == null) {
                                Toast.makeText(
                                    context,
                                    "未找到对应零件，请先在模板配置中扫码绑定",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                // 只切换已有零件；WorkBench 通过事件立即重载该零件的有序模板视角。
                                MobileInspectionApp.settings(context).selectedPartId = part.id
                                PartSelectionBus.emit(part.id)
                                navController.popBackStack(Screen.LiveInspection.route, false)
                            }
                        }
                    }
                )
            }

            composable(
                route = Screen.DpmBind.route,
                arguments = listOf(navArgument(Screen.DpmBind.ARG_PART_ID) { type = NavType.StringType })
            ) { backStackEntry ->
                val context = LocalContext.current
                val repository = remember { MobileInspectionApp.repository(context) }
                val scope = rememberCoroutineScope()
                val partId = backStackEntry.arguments?.getString(Screen.DpmBind.ARG_PART_ID)
                    ?: return@composable
                DpmScanScreen(
                    onBack = { navController.popBackStack() },
                    onResult = { code ->
                        scope.launch {
                            val cleanCode = code.trim()
                            val existing = withContext(Dispatchers.IO) {
                                repository.getPartByDpmCode(cleanCode)
                            }
                            when {
                                cleanCode.isBlank() -> {
                                    Toast.makeText(context, "DPM 码为空，无法绑定", Toast.LENGTH_SHORT).show()
                                }
                                existing != null && existing.id != partId -> {
                                    Toast.makeText(
                                        context,
                                        "该 DPM 码已绑定零件「${existing.name}」",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                else -> {
                                    withContext(Dispatchers.IO) {
                                        repository.updateDpmCode(partId, cleanCode)
                                    }
                                    Toast.makeText(context, "DPM 码绑定成功", Toast.LENGTH_SHORT).show()
                                    navController.popBackStack()
                                }
                            }
                        }
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

            composable(
                route = Screen.TemplateCapture.route,
                arguments = listOf(
                    navArgument(Screen.TemplateCapture.ARG_PART_ID) { type = NavType.StringType },
                    navArgument(Screen.TemplateCapture.ARG_TEMPLATE_ID) {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                val partId = backStackEntry.arguments?.getString(Screen.TemplateCapture.ARG_PART_ID)
                    ?: return@composable
                val templateId = backStackEntry.arguments?.getString(Screen.TemplateCapture.ARG_TEMPLATE_ID)
                    ?.takeIf { it.isNotEmpty() }
                TemplateCaptureScreen(
                    partId = partId,
                    templateId = templateId,
                    onBack = { navController.popBackStack() },
                    onCaptureSuccess = { navController.popBackStack() },
                )
            }

            // View 人工确认
            composable(
                route = Screen.ViewConfirmation.route,
                arguments = listOf(
                    navArgument(Screen.ViewConfirmation.ARG_BATCH_ID) { type = NavType.StringType },
                    navArgument(Screen.ViewConfirmation.ARG_PHOTO_ID) { type = NavType.LongType },
                    navArgument(Screen.ViewConfirmation.ARG_PHOTO_PATH) { type = NavType.StringType },
                    navArgument(Screen.ViewConfirmation.ARG_VIEW_INDEX) { type = NavType.IntType },
                    navArgument(Screen.ViewConfirmation.ARG_TEMPLATE_ID) { type = NavType.StringType },
                    navArgument(Screen.ViewConfirmation.ARG_TEMPLATE_NAME) { type = NavType.StringType },
                    navArgument(Screen.ViewConfirmation.ARG_PART_ID) { type = NavType.StringType },
                    navArgument(Screen.ViewConfirmation.ARG_TOTAL_VIEWS) { type = NavType.IntType }
                )
            ) { backStackEntry ->
                val args = backStackEntry.arguments ?: return@composable
                val batchId = args.getString(Screen.ViewConfirmation.ARG_BATCH_ID) ?: return@composable
                val photoId = args.getLong(Screen.ViewConfirmation.ARG_PHOTO_ID)
                val photoPath = args.getString(Screen.ViewConfirmation.ARG_PHOTO_PATH) ?: return@composable
                val viewIndex = args.getInt(Screen.ViewConfirmation.ARG_VIEW_INDEX)
                val templateId = args.getString(Screen.ViewConfirmation.ARG_TEMPLATE_ID) ?: return@composable
                val templateName = args.getString(Screen.ViewConfirmation.ARG_TEMPLATE_NAME) ?: ""
                val partId = args.getString(Screen.ViewConfirmation.ARG_PART_ID) ?: return@composable
                val totalViews = args.getInt(Screen.ViewConfirmation.ARG_TOTAL_VIEWS)

                val context = LocalContext.current
                val repository = remember { MobileInspectionApp.repository(context) }
                val partName = remember { mutableStateOf("") }

                // 加载零件名称
                LaunchedEffect(partId) {
                    val part = repository.getPartById(partId)
                    partName.value = part?.name ?: partId
                }

                val viewModel: ViewConfirmationViewModel = viewModel(
                    factory = ViewConfirmationViewModel.factory(
                        repository = repository,
                        batchId = batchId,
                        photoId = photoId,
                        photoPath = photoPath,
                        viewIndex = viewIndex,
                        templateId = templateId,
                        templateName = templateName,
                        partId = partId,
                        totalViews = totalViews
                    )
                )

                ViewConfirmationScreen(
                    viewModel = viewModel,
                    partName = partName.value.ifEmpty { partId },
                    currentViewIndex = viewIndex,
                    totalViews = totalViews,
                    onConfirmed = {
                        when (workbenchViewModel.completeView(viewIndex)) {
                            ViewCompletionResult.ADVANCED -> {
                                // 确认数据已保存后，显式推进并返回现场采集页。
                                navController.popBackStack()
                            }
                            ViewCompletionResult.COMPLETED -> {
                                // 最后一个 View：先持久化批次结束时间，再进入导出页。
                                scope.launch {
                                    repository.finishCaptureBatch(batchId)
                                    navController.navigate(
                                        Screen.ExportResult.createRoute(
                                            batchId,
                                            partId,
                                            partName.value.ifEmpty { partId }
                                        )
                                    ) {
                                        popUpTo(Screen.LiveInspection.route) { inclusive = false }
                                    }
                                }
                            }
                            ViewCompletionResult.IGNORED -> Unit
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            // 检测结果导出
            composable(
                route = Screen.ExportResult.route,
                arguments = listOf(
                    navArgument(Screen.ExportResult.ARG_BATCH_ID) { type = NavType.StringType },
                    navArgument(Screen.ExportResult.ARG_PART_ID) { type = NavType.StringType },
                    navArgument(Screen.ExportResult.ARG_PART_NAME) { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val args = backStackEntry.arguments ?: return@composable
                val batchId = args.getString(Screen.ExportResult.ARG_BATCH_ID) ?: return@composable
                val partId = args.getString(Screen.ExportResult.ARG_PART_ID) ?: return@composable
                val partName = args.getString(Screen.ExportResult.ARG_PART_NAME) ?: partId

                ExportResultScreen(
                    batchId = batchId,
                    partId = partId,
                    partName = partName,
                    onBack = { navController.popBackStack() },
                    onDone = {
                        navController.popBackStack(Screen.LiveInspection.route, false)
                    }
                )
            }
        }
    }
}
