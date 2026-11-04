package com.zbox.ai.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Ordinary monitor controls; the shutter itself remains the shared capture/hold implementation. */
@Composable
internal fun MonitorShutterControls(ui: UiState, vm: MainViewModel) {
    var exposureOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val blankFrame = remember { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888) }
    LaunchedEffect(ui.isConnected, ui.settingsOpen, ui.consoleOpen) {
        if (!ui.isConnected || ui.settingsOpen || ui.consoleOpen) {
            exposureOpen = false
            menuOpen = false
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ShutterRow(ui, vm)
        MonitorActionButton("曝光", onClick = { exposureOpen = true },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp).width(64.dp).testTag("monitor-exposure"),
            enabled = ui.isConnected && !ui.capturing && !ui.touchFocusBusy)
        MonitorActionButton("菜单", onClick = { menuOpen = true; vm.refreshCameraMenu() },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).width(64.dp).testTag("monitor-menu"),
            enabled = ui.isConnected && !ui.capturing && !ui.touchFocusBusy)
    }
    if (exposureOpen) Dialog(onDismissRequest = { exposureOpen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassCameraSheet(ui.liveViewBitmap ?: blankFrame, onClose = { exposureOpen = false }, bottom = true, compact = true) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(6.dp).testTag("monitor-exposure-dialog")) {
                ExposurePanel(ui, vm, collapsible = false)
                TextButton(onClick = { exposureOpen = false }, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
    if (menuOpen) Dialog(onDismissRequest = { menuOpen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CameraIMenuSheet(ui.liveViewBitmap ?: blankFrame, ui, vm, compact = true, onClose = { menuOpen = false })
    }
}
