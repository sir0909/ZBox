package com.zbox.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zbox.ai.ptp.NikonPtpDriver
import kotlinx.coroutines.launch

@Composable
fun AppRoot(
    vm: MainViewModel,
    onPickImage: () -> Unit,
    onPickEditorPhoto: () -> Unit,
    onPickNcp: () -> Unit,
    onRequestUsbPermission: (android.hardware.usb.UsbDevice) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val isLight = ui.settings.lightTheme
    val localContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.SideEffect {
        ZColors.isLightTheme = isLight
        var context = localContext
        while (context is android.content.ContextWrapper && context !is android.app.Activity) {
            context = context.baseContext
        }
        (context as? android.app.Activity)?.window?.let { window ->
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = isLight
                isAppearanceLightNavigationBars = isLight
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val startupLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) {
        vm.checkStartupUpdate { startupLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    }

    LaunchedEffect(ui.toast) {
        if (ui.toast.isNotEmpty()) {
            snackbar.showSnackbar(ui.toast)
            vm.consumeToast()
        }
    }

    if (ui.showChangelogDialog) {
        ChangelogDialog(onDismiss = { vm.dismissChangelog() })
    }

    if (!ui.showChangelogDialog) ui.startupUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { vm.dismissStartupUpdate() },
            containerColor = ZColors.SurfaceHigh,
            title = { Text("发现新版本", color = ZColors.TextPrimary) },
            text = { Text("新版本 ${update.version}，当前 ${com.zbox.ai.BuildConfig.VERSION_NAME}。\n是否打开 GitHub 下载更新？", color = ZColors.TextSecondary) },
            confirmButton = { TextButton(onClick = { vm.openStartupUpdate() }) { Text("去更新", color = ZColors.NikonYellow) } },
            dismissButton = { TextButton(onClick = { vm.dismissStartupUpdate() }) { Text("暂不更新", color = ZColors.TextSecondary) } },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(ZColors.Bg)) {
        Scaffold(
            containerColor = ZColors.Bg,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = ZColors.SurfaceHigher,
                        contentColor = ZColors.NikonYellow,
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            },
            bottomBar = {
                // Full-screen overlays must not be covered by Scaffold's later-drawn tab bar.
                if (!ui.settingsOpen && !ui.consoleOpen) {
                    BottomBar(
                        selected = ui.selectedTab,
                        onSelect = { vm.selectTab(it) },
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            ) {
                TopStatusBar(ui = ui, vm = vm)
                when (ui.selectedTab) {
                    0 -> LiveMonitorScreen(ui = ui, vm = vm)
                    1 -> PhotoEditorScreen(ui = ui, vm = vm, onPickEditorPhoto = onPickEditorPhoto)
                    2 -> AiLabScreen(
                        ui = ui, vm = vm,
                        onPickImage = onPickImage,
                        onRequestUsbPermission = onRequestUsbPermission,
                    )
                    3 -> GalleryScreen(ui = ui, vm = vm)
                    else -> PresetLibraryScreen(ui = ui, vm = vm, onPickNcp = onPickNcp)
                }
            }

            // 设置与调试台都是整屏覆盖层：不占底部 tab，进来后视野反而比 tab 里更大
            if (ui.settingsOpen) {
                BackHandler { vm.openSettings(false) }
                // Edge-to-edge 下此覆盖层不会继承主内容的 status-bar inset；为顶部返回条留出安全区，
                // 防止系统状态栏盖住左上角返回按钮。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ZColors.Bg)
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                ) {
                    SettingsScreen(ui = ui, vm = vm)
                }
            }
            if (ui.consoleOpen) {
                BackHandler { vm.openConsole(false) }
                Box(modifier = Modifier.fillMaxSize().background(ZColors.Bg)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                    ) {
                        ConsoleHeader(onBack = { vm.openConsole(false) })
                        UsbConsoleScreen(ui = ui, vm = vm, onRequestUsbPermission = onRequestUsbPermission)
                    }
                }
            }
        }
    }
}

/** 调试台覆盖层的返回条。 */
@Composable
private fun ConsoleHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZColors.Surface)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "返回",
                tint = ZColors.NikonYellow,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            "USB 调试台",
            color = ZColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "PTP · 0x90CC / 0x90CD",
            color = ZColors.TextTertiary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun TopStatusBar(ui: UiState, vm: MainViewModel) {
    val connected = ui.isConnected
    val connecting = ui.wifiConnectBusy || ui.usbConnectBusy || ui.pairingBusy
    val dotColor = when {
        connected -> ZColors.Success
        ui.usbState == NikonPtpDriver.State.ERROR -> ZColors.Danger
        connecting || ui.usbState == NikonPtpDriver.State.CONNECTED -> ZColors.NikonYellow
        else -> ZColors.TextTertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(ZColors.SurfaceHigh, ZColors.Bg)
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌标：Z 黄块
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(ZColors.ButtonBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Z",
                color = ZColors.ButtonFg,
                fontWeight = FontWeight.Black,
                fontSize = 19.sp,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                "ZBOX",
                color = ZColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 1.4.sp,
            )
            Text(
                "NIKON Z30 · EXPEED 6",
                color = ZColors.TextTertiary,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dotColor))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = when {
                        ui.pairingBusy -> "主机配对/重连中"
                        ui.wifiConnectBusy || ui.usbConnectBusy -> "连接校验中"
                        ui.usbState == NikonPtpDriver.State.SESSION_OPEN -> ui.deviceInfo.model.ifBlank { "Z 30" } + " 已连接"
                        ui.usbState == NikonPtpDriver.State.CONNECTED -> "PTP握手中"
                        ui.usbState == NikonPtpDriver.State.PERMISSION_PENDING -> "等待授权"
                        ui.usbState == NikonPtpDriver.State.ERROR -> "连接异常"
                        else -> "未连接"
                    },
                    color = when {
                        connected -> ZColors.Success
                        ui.usbState == NikonPtpDriver.State.ERROR -> ZColors.Danger
                        connecting -> ZColors.NikonYellow
                        else -> ZColors.TextSecondary
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (connected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ui.batteryPercent in 0..100) {
                        Text(
                            "🔋${ui.batteryPercent}%",
                            color = if (ui.batteryPercent <= 20) ZColors.Danger else ZColors.TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        "C-${ui.targetSlotIndex} 目标槽位",
                        color = ZColors.YellowDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        if (connected) {
            androidx.compose.material3.IconButton(
                onClick = { vm.disconnect() },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.LinkOff,
                    contentDescription = "断开相机连接",
                    tint = ZColors.Danger,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 设置入口：调试台和常规选项都收在这里，底部 tab 只留日常功能
        androidx.compose.material3.IconButton(
            onClick = { vm.openSettings(true) },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "设置",
                tint = ZColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private data class TabSpec(val title: String, val icon: ImageVector)

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf(
        TabSpec("监看", Icons.Filled.Videocam),
        TabSpec("修图", Icons.Filled.Tune),
        TabSpec("逆向配方", Icons.Filled.AutoAwesome),
        TabSpec("相册", Icons.Filled.PhotoLibrary),
        TabSpec("预设库", Icons.Filled.Folder),
    )
    NavigationBar(
        containerColor = ZColors.Surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() * 0f),
    ) {
        tabs.forEachIndexed { i, tab ->
            NavigationBarItem(
                selected = selected == i,
                onClick = { onSelect(i) },
                icon = { Icon(tab.icon, contentDescription = tab.title, modifier = Modifier.size(20.dp)) },
                label = {
                    Text(
                        tab.title,
                        fontSize = 10.sp,
                        fontWeight = if (selected == i) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ZColors.NavSelectedIcon,
                    selectedTextColor = ZColors.NikonYellow,
                    unselectedIconColor = ZColors.TextTertiary,
                    unselectedTextColor = ZColors.TextTertiary,
                    indicatorColor = ZColors.NavIndicator,
                ),
            )
        }
    }
}

@Composable
private fun ChangelogDialog(onDismiss: () -> Unit) {
    val items = listOf(
        "全场景离线智能构图 + 全屏构图" to "监看页与全屏模式新增「构图」按钮，点击立即生效；可在「设置 → 构图模式」切换「本地智能构图」（人像眼线锚点 + 离线通用物体检测 + Achanta Lab 边界抑制显著性 + 地平线三分与实时水平仪，0 流量）或「AI Agent 构图」。",
        "60fps / 30fps 实时取景帧率升级" to "取景帧率调整为「流畅 · 60fps」「标准 · 30fps」「省电 · 2fps」，结合整帧预算调度带来更顺滑的监看与跟焦体验。",
        "新增「修图」工作台与离线智能蒙版" to "Lightroom 风格非破坏性本地调色（曝光/影调/白平衡/8 色相 HSL/曲线/裁切），支持离线 AI 选区（人物/天空/主体/背景）与径向/线性/画笔/亮度局部蒙版，拖动时才临时预览红膜且一键导出至 Pictures/Zbox。",
        "全链路联动与双主题精修" to "支持从「相册」或「逆向配方」送入「修图」精修，调色滑杆支持点击输入精确数值与双击归零，并全面适配浅色 Material 3 Mono 与尼康暗黑主题。",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZColors.SurfaceHigh,
        title = {
            Column {
                Text(
                    "ZBOX 更新说明",
                    color = ZColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Spacer(Modifier.height(4.dp))
                Chip("v${com.zbox.ai.BuildConfig.VERSION_NAME} · 全场景构图与 60fps 监看升级")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items.forEachIndexed { index, (title, desc) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ZColors.Surface)
                            .padding(10.dp),
                    ) {
                        Text(
                            "${index + 1}. $title",
                            color = ZColors.NikonYellow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            desc,
                            color = ZColors.TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ZColors.ButtonBg,
                    contentColor = ZColors.ButtonFg,
                ),
            ) {
                Text("开始使用", fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** Nikon 黄色主按钮。 */
@Composable
fun ZPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = ZColors.ButtonBg,
            contentColor = ZColors.ButtonFg,
            disabledContainerColor = ZColors.SurfaceHigher,
            disabledContentColor = ZColors.TextTertiary,
        ),
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = ZColors.ButtonFg,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.4.sp)
    }
}

/** 描边次级按钮。 */
@Composable
fun ZOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ZColors.Outline),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = ZColors.TextPrimary,
        ),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** 小标签气泡。 */
@Composable
fun Chip(text: String, color: Color = ZColors.NikonYellow, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** HTML 原型中的页面眉题：黄字、字距拉开，不挤占正文层级。 */
@Composable
fun PageEyebrow(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = ZColors.NikonYellow,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.0.sp,
    )
}

/** 区块标题。 */
@Composable
fun SectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            color = ZColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
        )
        trailing?.invoke()
    }
}
