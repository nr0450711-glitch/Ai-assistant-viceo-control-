package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.firebase.configureAppCheck
import com.example.ui.ApkAiViewModel
import com.example.ui.screens.AiStudioScreen
import com.example.ui.screens.ApkInspectorScreen
import com.example.ui.screens.ReportsScreen
import com.example.ui.theme.ApkAiTheme
import kotlinx.coroutines.launch

enum class AppTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    AI_STUDIO(
        route = "ai_studio",
        labelRes = R.string.nav_ai_studio,
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome
    ),
    INSPECTOR(
        route = "inspector",
        labelRes = R.string.nav_inspector,
        selectedIcon = Icons.Filled.Security,
        unselectedIcon = Icons.Outlined.Security
    ),
    REPORTS(
        route = "reports",
        labelRes = R.string.nav_reports,
        selectedIcon = Icons.Filled.Description,
        unselectedIcon = Icons.Outlined.Description
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure App Check with launch intent before UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            val viewModel: ApkAiViewModel = viewModel()
            val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()

            ApkAiTheme(darkTheme = isDarkTheme) {
                ApkAiAppRoot(viewModel = viewModel, isDarkTheme = isDarkTheme)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkAiAppRoot(
    viewModel: ApkAiViewModel,
    isDarkTheme: Boolean
) {
    val chatState by viewModel.chatState.collectAsStateWithLifecycle()
    val inspectorState by viewModel.inspectorState.collectAsStateWithLifecycle()
    val savedReports by viewModel.savedReports.collectAsStateWithLifecycle()
    val selectedReport by viewModel.selectedReport.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(AppTab.AI_STUDIO) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val showCopiedNotice: () -> Unit = {
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Copied to clipboard")
        }
    }

    if (currentTab != AppTab.AI_STUDIO && selectedReport == null) {
        BackHandler { currentTab = AppTab.AI_STUDIO }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_apk_ai_logo_1795007762096),
                                contentDescription = "APK AI emblem",
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = viewModel::toggleTheme,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("theme_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                contentDescription = if (isDarkTheme) "Switch to light theme" else "Switch to dark theme"
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        AppTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    TabIcon(
                                        tab = tab,
                                        selected = selected,
                                        reportCount = savedReports.size
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("nav_tab_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        AppTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    TabIcon(
                                        tab = tab,
                                        selected = selected,
                                        reportCount = savedReports.size
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag("rail_tab_${tab.route}")
                            )
                        }
                    }
                }

                when (currentTab) {
                    AppTab.AI_STUDIO -> {
                        AiStudioScreen(
                            state = chatState,
                            onSelectModel = viewModel::selectModel,
                            onAttachImage = viewModel::attachImage,
                            onSendMessage = viewModel::sendChatMessage,
                            onResetChat = viewModel::resetChat,
                            onClearError = viewModel::clearChatError,
                            onCopiedMessage = showCopiedNotice,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    AppTab.INSPECTOR -> {
                        ApkInspectorScreen(
                            state = inspectorState,
                            onInspectCurrentApp = viewModel::inspectRunningApp,
                            onPickApkUri = viewModel::inspectApkUri,
                            onRunAiAudit = viewModel::runAiAuditOnCurrentApk,
                            onAskAiAboutApk = { meta ->
                                val summaryPrompt = "Let's review ${meta.appLabel} (${meta.packageName}, targetSdk ${meta.targetSdk}, debuggable=${meta.isDebuggable}, ${meta.permissions.size} permissions). What should I optimize or secure before release?"
                                currentTab = AppTab.AI_STUDIO
                                viewModel.sendChatMessage(summaryPrompt)
                            },
                            onCopied = showCopiedNotice,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    AppTab.REPORTS -> {
                        ReportsScreen(
                            reports = savedReports,
                            selectedReport = selectedReport,
                            splitList = viewModel::splitJoinedList,
                            onSelectReport = viewModel::openReportDetail,
                            onCloseDetail = viewModel::closeReportDetail,
                            onDeleteReport = viewModel::deleteReport,
                            onGoToInspector = { currentTab = AppTab.INSPECTOR },
                            onCopied = showCopiedNotice,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(
    tab: AppTab,
    selected: Boolean,
    reportCount: Int
) {
    val icon = if (selected) tab.selectedIcon else tab.unselectedIcon
    if (tab == AppTab.REPORTS && reportCount > 0) {
        BadgedBox(
            badge = {
                Badge {
                    Text(reportCount.toString())
                }
            }
        ) {
            Icon(imageVector = icon, contentDescription = null)
        }
    } else {
        Icon(imageVector = icon, contentDescription = null)
    }
}
