package com.zbox.ai.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zbox.ai.ptp.Ptp

/**
 * Tab「监看」：实时取景 + 快门 + 曝光参数 + 机内照片快览。
 *
 * 交互设计原则——右手单手持机时拇指能到的地方才有按钮：
 *   取景画面全宽在上，快门/参数在下，参数用滑杆而不是键盘输入。
 */
@Composable
fun LiveMonitorScreen(
    ui: UiState,
    vm: MainViewModel,
    onRequestUsbPermission: (android.hardware.usb.UsbDevice) -> Unit = {},
) {
    // 设置里打开「监看页常亮」时给窗口加 FLAG_KEEP_SCREEN_ON。
    // 用 DisposableEffect 而不是 Activity 里的全局标志：离开这个 tab 就自动撤销，
    // 不会出现「明明在看相册，屏幕却一直亮着」的情况。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(vm, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE ||
                event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                vm.stopNativeBurst(resumeLiveView = false)
                vm.clearComposition(close = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            vm.clearComposition(close = true)
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.stopNativeBurst(resumeLiveView = false)
        }
    }
    androidx.compose.runtime.LaunchedEffect(ui.settingsOpen, ui.consoleOpen, ui.isConnected) {
        if (ui.settingsOpen || ui.consoleOpen || !ui.isConnected) vm.stopNativeBurst(resumeLiveView = false)
    }
    androidx.compose.runtime.LaunchedEffect(ui.isConnected, ui.liveViewActive, ui.settingsOpen, ui.consoleOpen,
        ui.capturing, ui.movieBusy, ui.movieRecording) {
        if (!ui.isConnected || !ui.liveViewActive || ui.settingsOpen || ui.consoleOpen || ui.capturing || ui.movieBusy || ui.movieRecording)
            vm.clearComposition(close = true)
    }
    androidx.compose.runtime.DisposableEffect(ui.settings.keepScreenOn) {
        val window = (ctx as? android.app.Activity)?.window
        if (window != null) {
            if (ui.settings.keepScreenOn) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        PageEyebrow("MONITOR / 竖屏监看")
        if (!ui.isConnected) {
            ConnectBar(ui = ui, vm = vm, onRequestUsbPermission = onRequestUsbPermission)
        }
        Spacer(Modifier.height(10.dp))
        LiveViewStage(ui = ui, vm = vm)
        Spacer(Modifier.height(4.dp))
        MonitorShutterControls(ui = ui, vm = vm)
        CompositionPanel(ui = ui, vm = vm)
        Spacer(Modifier.height(4.dp))

        if (ui.captureMsg.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZColors.Surface)
                    .padding(8.dp),
            ) {
                Text(
                    ui.captureMsg,
                    color = ZColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        SectionTitle("刚拍的这一张", trailing = {
            // 机身快门按键拍的照片不走 CaptureToSdram（App 收不到 0x9207），
            // 只能主动去存储卡尾部取最新一张，所以给一个显式入口
            ZOutlineButton(
                text = if (ui.capturing) "取回中…" else "从机身取最新",
                onClick = { vm.loadNewestFromCamera() },
                enabled = ui.isConnected && !ui.capturing,
            )
        })
        LastCaptureCard(ui = ui, vm = vm)
        PhoneInspection(ui,vm.phone)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
internal fun PtpIpClientIdentityEntry(ui: UiState, vm: MainViewModel) {
    var showDialog by remember { mutableStateOf(false) }
    val guidPreview = ui.clientIdentityGuidHex.let {
        if (it.length >= 12) "${it.take(8)}…${it.takeLast(4)}" else it.ifBlank { "默认生成" }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ZColors.Surface)
            .border(1.dp, ZColors.Outline, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("PTP/IP 客户端身份", color = ZColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                "当前名称：${ui.clientIdentityName} · GUID：$guidPreview",
                color = ZColors.TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        ZOutlineButton(
            text = "身份设置",
            onClick = { showDialog = true },
            enabled = !ui.wifiConnectBusy && !ui.pairingBusy,
        )
    }
    if (showDialog) {
        PtpIpClientIdentityDialog(ui = ui, vm = vm, onDismiss = { showDialog = false })
    }
}

@Composable
internal fun PtpIpClientIdentityDialog(ui: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val initial = remember(ui.clientIdentityName, ui.clientIdentityGuidHex) { vm.ptpIpClientIdentity() }
    var nameInput by remember(initial) { mutableStateOf(initial.name) }
    var guidInput by remember(initial) { mutableStateOf(initial.guidHex) }
    var errorText by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZColors.SurfaceHigh,
        title = { Text("PTP/IP 客户端身份", color = ZColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Nikon 相机在「连接到计算机」模式下会校验客户端名称与 GUID。若相机曾与旧版 Z-Cloud AI 配对，恢复当时的名称与 GUID 即可免重配直连；否则请在相机菜单选择「配对」重新登记一次。",
                    color = ZColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Zbox" to "Zbox（默认）", "Z-Cloud AI" to "Z-Cloud AI（旧版兼容）").forEach { (preset, label) ->
                        val selected = nameInput.trim() == preset
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selected) ZColors.NikonYellow else ZColors.Surface)
                                .border(1.dp, ZColors.Outline, RoundedCornerShape(7.dp))
                                .clickable { nameInput = preset; errorText = "" }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) {
                            Text(
                                label,
                                color = if (selected) Color(0xFF121212) else ZColors.TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it.take(32); errorText = "" },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("客户端名称") },
                )
                OutlinedTextField(
                    value = guidInput,
                    onValueChange = { guidInput = it.trim(); errorText = "" },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 2,
                    label = { Text("客户端 GUID（32位Hex 或 UUID，留空用本机默认）") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ZOutlineButton(
                        text = "复制当前 GUID",
                        onClick = { clipboard.setText(AnnotatedString(initial.guidHex)) },
                        modifier = Modifier.weight(1f),
                    )
                    ZOutlineButton(
                        text = "留空用本机默认",
                        onClick = { guidInput = ""; errorText = "" },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (errorText.isNotBlank()) {
                    Text(errorText, color = Color(0xFFFF8A80), fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val res = vm.savePtpIpClientIdentity(nameInput, guidInput)
                    if (res.isSuccess) onDismiss()
                    else errorText = res.exceptionOrNull()?.message ?: "保存失败"
                },
            ) {
                Text("保存", color = ZColors.NikonYellow)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消", color = ZColors.TextSecondary)
            }
        },
    )
}

@Composable
internal fun PairingEntry(ui: UiState, vm: MainViewModel) {
    var showDialog by remember { mutableStateOf(false) }
    val canPair = ui.deviceInfo.supportsNikonHostRegistration
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ZColors.Surface)
            .border(1.dp, ZColors.Outline, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Wi-Fi 主机配对", color = ZColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                ui.pairingStatus.ifBlank {
                    if (canPair) "首次连接时按相机提示输入 PIN；已登记的主机无需重复配对。"
                    else "连接支持 Wi-Fi 主机登记的 Nikon 相机后可用。"
                },
                color = ZColors.TextTertiary,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        ZOutlineButton(
            text = if (ui.pairingBusy) "处理中…" else "主机配对",
            onClick = { showDialog = true },
            enabled = canPair && !ui.pairingBusy,
        )
    }
    if (showDialog) {
        NikonHostPairingDialog(ui = ui, vm = vm, onDismiss = { showDialog = false })
    }
}

@Composable
private fun NikonHostPairingDialog(ui: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    var enteredCode by remember(ui.pairingCode) { mutableStateOf(ui.pairingCode.orEmpty()) }
    val pairingCode = ui.pairingCode
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!ui.pairingBusy) onDismiss() },
        containerColor = ZColors.SurfaceHigh,
        title = { Text("Nikon 主机配对", color = ZColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "自动流程只会在相机实际返回非空 PIN 时提交；未返回 PIN 或失败时不会提交。需要时可在 设置 → 高级 手动读取并确认。",
                    color = ZColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                when {
                    pairingCode == null -> Text("尚未读取 PIN。", color = ZColors.TextTertiary, fontSize = 11.sp)
                    pairingCode.isEmpty() -> Text("相机未返回数字 PIN；请按机身提示确认后再提交。", color = ZColors.YellowDim, fontSize = 11.sp)
                    else -> {
                        Text("相机返回 PIN：${pairingCode}", color = ZColors.NikonYellow, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        androidx.compose.material3.OutlinedTextField(
                            value = enteredCode,
                            onValueChange = { enteredCode = it.filter(Char::isDigit).take(12) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("输入相机屏幕上的 PIN") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            ),
                        )
                    }
                }
                if (ui.pairingStatus.isNotBlank()) {
                    Text(ui.pairingStatus, color = ZColors.TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    if (pairingCode == null) vm.readNikonPairingCode()
                    else vm.confirmNikonPairingCode(enteredCode)
                },
                enabled = !ui.pairingBusy && (pairingCode == null || pairingCode.isEmpty() || enteredCode.filter(Char::isDigit) == pairingCode),
            ) {
                Text(
                    if (ui.pairingBusy) "处理中…" else if (pairingCode == null) "准备 PIN" else "确认注册",
                    color = ZColors.NikonYellow,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !ui.pairingBusy) {
                Text("关闭", color = ZColors.TextSecondary)
            }
        },
    )
}
@Composable
private fun LiveViewStage(ui: UiState, vm: MainViewModel) {
    var fullscreen by remember { mutableStateOf(false) }
    var lastFrame by remember{mutableStateOf<android.graphics.Bitmap?>(null)}
    androidx.compose.runtime.LaunchedEffect(ui.liveViewBitmap){ui.liveViewBitmap?.let{lastFrame=it}}
    androidx.compose.runtime.LaunchedEffect(ui.isConnected,ui.settingsOpen,ui.consoleOpen){if(!ui.isConnected || ui.settingsOpen || ui.consoleOpen){fullscreen=false;lastFrame=null}}
    if(fullscreen)(ui.liveViewBitmap?:lastFrame)?.let{FullscreenViewer(frame=it,ui=ui,vm=vm,onExit={fullscreen=false})}
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ZColors.Surface)
            .border(1.dp, ZColors.Outline, RoundedCornerShape(14.dp)),
    ) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 2f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        val frame = ui.liveViewBitmap
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "实时取景",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        !ui.isConnected -> "未连接相机"
                        ui.liveViewError.isNotBlank() -> ui.liveViewError
                        ui.liveViewActive -> "等待首帧…"
                        else -> "点下方「取景」"
                    },
                    color = ZColors.TextTertiary,
                    fontSize = 12.sp,
                )

            }
        }

        if(!fullscreen)PhoneAssistOverlay(frame,vm.phone)
        IconButton(
            onClick = { fullscreen = true },
            enabled = ui.isConnected && ui.liveViewActive && frame != null && !ui.capturing && !ui.movieBusy,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(48.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha=.55f)),
        ) {
            Icon(Icons.Default.Fullscreen, contentDescription="全屏取景",
                tint=if(ui.isConnected && ui.liveViewActive && frame!=null) Color.White else ZColors.TextTertiary)
        }

        // 左上角状态与帧计数
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (ui.liveViewActive) ZColors.Success else ZColors.TextTertiary)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (ui.liveViewActive) "LIVE ${ui.liveViewFrames}" else "OFF",
                color = if (ui.liveViewActive) ZColors.NikonYellow else ZColors.TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }

        // 右上角：取景参数回显
        if (ui.isConnected) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    "1/${shutterDenom(ui.shutterSeconds)}  F${fmtF(ui.aperture)}  ISO${ui.iso}",
                    color = ZColors.NikonYellow,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "EV${if (ui.exposureComp >= 0) "+" else ""}${"%.1f".format(ui.exposureComp)}  ${
                        programName(ui.exposureProgram)
                    }",
                    color = ZColors.TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(if (ui.isConnected) ZColors.Success else ZColors.TextTertiary))
            Spacer(Modifier.width(5.dp))
            Text(
                if (ui.isConnected) "${ui.deviceInfo.model.ifBlank { "Z30" }} 已连接" else "等待相机连接",
                color = ZColors.TextSecondary,
                fontSize = 9.sp,
            )
        }
        Text("取景辅助叠加 · 手机处理", color = ZColors.TextTertiary, fontSize = 9.sp)
    }
    }

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MonitorActionButton(
            text = if (ui.liveViewActive) "停取景" else "取景",
            onClick = { vm.toggleLiveView() }, modifier=Modifier.weight(1f),
            enabled = ui.isConnected && !ui.pairingBusy && !ui.movieRecording && !ui.movieBusy,
            selected = ui.liveViewActive,
        )
        MonitorActionButton(text="AF", onClick={ vm.triggerAutoFocusNow() },
            modifier=Modifier.weight(1f), enabled=ui.isConnected && !ui.pairingBusy && !ui.capturing && !ui.movieRecording && !ui.movieBusy)
        MonitorActionButton(text="AI 构图", onClick={ if(ui.compositionOpen) vm.clearComposition(close=true) else vm.openComposition() },
            modifier=Modifier.weight(1f), selected=ui.compositionOpen,
            enabled=ui.isConnected && !ui.pairingBusy && !ui.capturing && !ui.movieBusy && !ui.movieRecording)
        MonitorActionButton(
            text=if(ui.movieBusy) "处理中…" else if(ui.movieRecording) "停止录像" else "录像",
            onClick={ vm.toggleMovieRecording() }, modifier=Modifier.weight(1f),
            enabled=ui.isConnected && !ui.pairingBusy && !ui.capturing && !ui.movieBusy, selected=ui.movieRecording,
        )
    }

}

@Composable
internal fun ShutterRow(ui: UiState, vm: MainViewModel,translucent:Boolean=false) {
    val shutterAccent=if(translucent)Color.White else ZColors.NikonYellow
    Column(Modifier.fillMaxWidth().padding(vertical=2.dp), horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment=Alignment.Center) {
        // 普通模式为黄色外环+实心中心；全屏只将强调色替换为白色。
        Box(
            modifier = Modifier
                .size(60.dp)
                .semantics { contentDescription="拍照快门"; role=Role.Button; onClick(label="拍摄单张") { vm.capturePhoto(); true } }
                .clip(RoundedCornerShape(50))
                .background(if(translucent)Color.Black.copy(alpha=.12f) else if (ui.capturing) ZColors.SurfaceHigher else Color(0xFF17191B))
                .border(3.dp,shutterAccent,RoundedCornerShape(50))
                .pointerInput(vm) {
                    // Never key on capturing: starting capture must not cancel the held pointer.
                    detectTapGestures(
                        onPress = {
                            try { tryAwaitRelease() } finally { vm.stopNativeBurst() }
                        },
                        onTap = { vm.capturePhoto() },
                        onLongPress = { vm.startNativeBurst() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(shutterAccent))
            if (ui.capturing) {
                Text("…", color = Color(0xFF17191B), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

        }
    }
}

}

@Composable
internal fun ExposurePanel(ui: UiState, vm: MainViewModel,collapsible:Boolean=true) {
    var expanded by remember{mutableStateOf(false)}
    if(collapsible){CollapsibleSectionHeader("曝光参数",expanded,{expanded=!expanded});if(!expanded)return}
    else SectionTitle("曝光参数")

    ModeChips(ui = ui, vm = vm)

    // 机身当前拍摄模式下，哪些参数由相机自己接管——写进去只会拿到 AccessDenied，所以直接禁掉
    // 另外：机身自己通过 0x1014 描述符声明为「只读」的属性（Z30 上 0x500E/0x500D/0xD100 就是）
    // 无论什么档位都写不进去，滑杆一律禁掉，避免无效自动写入及反复 AccessDenied
    val shutterReadOnly = Ptp.DPC_NIKON_EXPOSURE_TIME in ui.readOnlyProps &&
        Ptp.DPC_EXPOSURE_TIME in ui.readOnlyProps
    val apertureByCamera = ui.exposureProgram != 1 && ui.exposureProgram != 3 // P/S 档由机身配
    val shutterByCamera = !(ui.exposureProgram == 1 || ui.exposureProgram == 4) || shutterReadOnly
    val apertureOptions = ui.apertureValues.ifEmpty { listOf(ui.aperture) }
    val apertureEnabled = ui.isConnected && !ui.capturing && !ui.writing && !apertureByCamera &&
        ui.apertureRangeReady && ui.apertureWritable && Ptp.DPC_F_NUMBER !in ui.readOnlyProps

    androidx.compose.runtime.LaunchedEffect(ui.isConnected) {
        while (ui.isConnected) {
            vm.refreshApertureCapabilities()
            kotlinx.coroutines.delay(2500)
        }
    }


    ParamRow(
        label = "光圈",
        value = "F${fmtF(ui.aperture)}",
        enabled = apertureEnabled,
        slider = {
            Slider(
                value = fStopIndex(ui.aperture, apertureOptions),
                onValueChange = { vm.setAperture(apertureOptions[Math.round(it).coerceIn(0, apertureOptions.lastIndex)]) },
                valueRange = 0f..apertureOptions.lastIndex.toFloat(),
                steps = (apertureOptions.size - 2).coerceAtLeast(0),
                enabled = apertureEnabled,
                colors = SliderDefaults.colors(
                    thumbColor = ZColors.NikonYellow,
                    activeTrackColor = ZColors.NikonYellow,
                    inactiveTrackColor = ZColors.Outline,
                ),
            )
        },
    )
    ParamRow(
        label = "快门",
        value = if (ui.shutterSeconds >= 1f) "${"%.1f".format(ui.shutterSeconds)}s"
        else "1/${shutterDenom(ui.shutterSeconds)}",
        enabled = ui.isConnected && !ui.capturing && !ui.writing && !shutterByCamera,
        slider = {
            Slider(
                value = shutterIndex(ui.shutterSeconds).toFloat(),
                onValueChange = { vm.setShutterSeconds(shutterValues[Math.round(it).coerceIn(0, shutterValues.lastIndex)]) },
                valueRange = 0f..(shutterValues.size - 1).toFloat(),
                steps = shutterValues.size - 2,
                enabled = ui.isConnected && !ui.capturing && !ui.writing && !shutterByCamera,
                colors = SliderDefaults.colors(
                    thumbColor = ZColors.NikonYellow,
                    activeTrackColor = ZColors.NikonYellow,
                    inactiveTrackColor = ZColors.Outline,
                ),
            )
        },
    )
    ParamRow(
        label = "ISO",
        value = "${ui.iso}",
        enabled = ui.isConnected && !ui.capturing && !ui.writing && Ptp.DPC_ISO !in ui.readOnlyProps,
        slider = {
            Slider(
                value = isoIndex(ui.iso).toFloat(),
                onValueChange = { vm.setIso(isoValues[Math.round(it).coerceIn(0, isoValues.lastIndex)]) },
                valueRange = 0f..(isoValues.size - 1).toFloat(),
                steps = isoValues.size - 2,
                enabled = ui.isConnected && !ui.capturing && !ui.writing && Ptp.DPC_ISO !in ui.readOnlyProps,
                colors = SliderDefaults.colors(
                    thumbColor = ZColors.NikonYellow,
                    activeTrackColor = ZColors.NikonYellow,
                    inactiveTrackColor = ZColors.Outline,
                ),
            )
        },
    )
    ParamRow(
        label = "曝光补偿",
        value = "${if (ui.exposureComp >= 0) "+" else ""}${"%.1f".format(ui.exposureComp)}",
        enabled = ui.isConnected && !ui.capturing && !ui.writing && ui.exposureCompWritable && Ptp.DPC_EXPOSURE_BIAS_COMPENSATION !in ui.readOnlyProps,
        slider = {
            Slider(
                value = ui.exposureComp,
                onValueChange = { vm.setExposureComp((Math.round(it * 3f) / 3f)) },
                valueRange = -5f..5f,
                enabled = ui.isConnected && !ui.capturing && !ui.writing && ui.exposureCompWritable && Ptp.DPC_EXPOSURE_BIAS_COMPENSATION !in ui.readOnlyProps,
                colors = SliderDefaults.colors(
                    thumbColor = ZColors.NikonYellow,
                    activeTrackColor = ZColors.NikonYellow,
                    inactiveTrackColor = ZColors.Outline,
                ),
            )
        },
    )
}

/** Compact overlay only; all writes reuse existing serialized setters and mode/read-only gates. */
@Composable internal fun CameraExposureOverlay(ui:UiState,vm:MainViewModel){
    val ready=!ui.touchFocusBusy && ui.isConnected && !ui.capturing && !ui.writing && !ui.pairingBusy && !ui.movieBusy && !ui.movieRecording && !ui.exposureSyncing && !vm.phone.cameraBusy
    val shutterReadOnly=Ptp.DPC_NIKON_EXPOSURE_TIME in ui.readOnlyProps && Ptp.DPC_EXPOSURE_TIME in ui.readOnlyProps
    val apertureOptions=ui.apertureValues.ifEmpty{listOf(ui.aperture)}
    androidx.compose.runtime.LaunchedEffect(ui.isConnected){while(ui.isConnected){vm.refreshApertureCapabilities();kotlinx.coroutines.delay(2500)}}
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(2.dp)){
        CameraExposureSlider("光圈","F${fmtF(ui.aperture)}",fStopIndex(ui.aperture,apertureOptions),0f..apertureOptions.lastIndex.toFloat(),(apertureOptions.size-2).coerceAtLeast(0),
            ready && ui.exposureProgram in listOf(1,3) && ui.apertureRangeReady && ui.apertureWritable && Ptp.DPC_F_NUMBER !in ui.readOnlyProps){vm.setAperture(apertureOptions[Math.round(it).coerceIn(0,apertureOptions.lastIndex)])}
        CameraExposureSlider("ISO","${ui.iso}",isoIndex(ui.iso).toFloat(),0f..isoValues.lastIndex.toFloat(),isoValues.size-2,
            ready && Ptp.DPC_ISO !in ui.readOnlyProps){vm.setIso(isoValues[Math.round(it).coerceIn(0,isoValues.lastIndex)])}
        CameraExposureSlider("快门",if(ui.shutterSeconds>=1f)"${"%.1f".format(ui.shutterSeconds)}s"else"1/${shutterDenom(ui.shutterSeconds)}",shutterIndex(ui.shutterSeconds).toFloat(),0f..shutterValues.lastIndex.toFloat(),shutterValues.size-2,
            ready && ui.exposureProgram in listOf(1,4) && !shutterReadOnly){vm.setShutterSeconds(shutterValues[Math.round(it).coerceIn(0,shutterValues.lastIndex)])}
    }
}
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable private fun CameraExposureSlider(label:String,value:String,current:Float,range:ClosedFloatingPointRange<Float>,steps:Int,enabled:Boolean,change:(Float)->Unit){
    Column(Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,color=Color.White.copy(alpha=if(enabled).85f else .38f),fontSize=11.sp);Text(value,color=Color.White.copy(alpha=if(enabled).95f else .38f),fontSize=12.sp,fontFamily=FontFamily.Monospace)}
        Slider(value=current,onValueChange=change,valueRange=range,steps=steps,enabled=enabled,modifier=Modifier.fillMaxWidth(),
            thumb={androidx.compose.foundation.Canvas(Modifier.size(14.dp)){drawCircle(Color.White.copy(alpha=if(enabled).82f else .22f))}},
            track={androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(24.dp)){
                val y=size.height/2;val fraction=((current-range.start)/(range.endInclusive-range.start)).coerceIn(0f,1f)
                drawLine(Color.White.copy(alpha=if(enabled).22f else .1f),androidx.compose.ui.geometry.Offset(0f,y),androidx.compose.ui.geometry.Offset(size.width,y),2.dp.toPx())
                drawLine(Color.White.copy(alpha=if(enabled).65f else .15f),androidx.compose.ui.geometry.Offset(0f,y),androidx.compose.ui.geometry.Offset(size.width*fraction,y),2.dp.toPx())
            }})
    }
}

@Composable
private fun ModeChips(ui: UiState, vm: MainViewModel) {
    var expanded by remember { mutableStateOf(true) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(1 to "M", 2 to "P", 3 to "A", 4 to "S").forEach { (v, label) ->
            val sel = ui.exposureProgram == v
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel) ZColors.NikonYellow else ZColors.SurfaceHigh)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .pointerInput(v, ui.isConnected, ui.readOnlyProps) {
                        // 点档位就直接发到机身，不必再点「下发」——这是用户最容易误会的一步
                        detectTapGestures(onTap = { if (ui.isConnected && Ptp.DPC_EXPOSURE_PROGRAM_MODE !in ui.readOnlyProps) vm.applyExposureProgram(v) })
                    },
            ) {
                Text(
                    label,
                    color = if (sel) Color(0xFF121212) else ZColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ZColors.SurfaceHigh)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { expanded = !expanded }) },
        ) {
            Text(
                "PC ${picCtrlName(ui.picCtrlItem)}",
                color = ZColors.YellowDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    if (expanded) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            listOf(
                1 to "标准", 2 to "自然", 3 to "鲜艳", 4 to "单色",
            ).forEach { (v, label) ->
                val sel = ui.picCtrlItem == v
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (sel) ZColors.YellowDim.copy(alpha = 0.3f) else ZColors.Surface)
                        .padding(vertical = 5.dp)
                        .pointerInput(v, ui.isConnected) {
                            detectTapGestures(onTap = { if (ui.isConnected) vm.setPicCtrlItem(v) })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (sel) ZColors.NikonYellow else ZColors.TextTertiary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ParamRow(
    label: String,
    value: String,
    enabled: Boolean,
    slider: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = ZColors.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.width(56.dp),
        )
        Box(modifier = Modifier.weight(1f)) { slider() }
        Text(
            value,
            color = if (enabled) ZColors.NikonYellow else ZColors.TextTertiary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(66.dp),
        )
    }
}

@Composable
private fun LastCaptureCard(ui: UiState, vm: MainViewModel) {
    val bmp = ui.lastCapture
    if (bmp == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ZColors.Surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.padding(start = 11.dp, top = 11.dp, bottom = 11.dp)
                    .size(width = 70.dp, height = 62.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF202327))
                    .border(1.dp, ZColors.Outline, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("PREVIEW", color = ZColors.TextTertiary, fontSize = 7.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 11.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("机身照片快览", color = ZColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Chip("暂无照片", ZColors.TextTertiary)
                }
                Spacer(Modifier.height(4.dp))
                // 关键区分：App 快门走 0x9207；机身按键拍摄仍需从存储卡取回。
                Text(
                    "点上面的快门拍摄；机身按键拍的照片，点右上「从机身取最新」",
                    color = ZColors.TextTertiary.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                )
            }
        }
        return
    }
    var full by remember(bmp) { mutableStateOf(false) }
    Column {
        // 大图：按原始宽高比铺满整宽，Fit 不裁切——要看细节，裁一刀就没意义了
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF000000))
                .pointerInput(bmp) { detectTapGestures(onTap = { full = true }) },
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "刚拍摄的照片",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
                contentScale = ContentScale.Fit,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "${bmp.width}×${bmp.height} · 点开看细节",
                    color = ZColors.TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZPrimaryButton(
                text = "存到手机相册",
                onClick = { vm.saveLastCaptureToPhone() },
                modifier = Modifier.weight(1f),
            )
            ZOutlineButton(
                text = "当作参考图",
                onClick = {
                    // 把刚拍的这张当参考图，直接进 AI 还原调色
                    vm.useLastCaptureAsReference()
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        ZOutlineButton(
            text = "全屏查看原图",
            onClick = { full = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (full) {
        FullScreenPhoto(bmp = bmp, onDismiss = { full = false })
    }
}

/**
 * 全屏看图：双指缩放 + 拖动 + 双击复位。
 *
 * 拍完要判断对焦、噪点、色彩，180dp 高的缩略图看不出任何东西，
 * 所以这里给一个真正的 1:1 观察窗口。
 */
@Composable
private fun FullScreenPhoto(bmp: android.graphics.Bitmap, onDismiss: () -> Unit) {
    var scale by remember(bmp) { mutableStateOf(1f) }
    var offset by remember(bmp) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .pointerInput(bmp) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1.01f) 1f else 2.2f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                        },
                        onTap = { /* 单击不退出，避免误触 */ },
                    )
                }
                .pointerInput(bmp) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (ns <= 1.001f) {
                            androidx.compose.ui.geometry.Offset.Zero
                        } else {
                            offset + pan
                        }
                        scale = ns
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "拍摄原图",
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${bmp.width}×${bmp.height}  ${"%.0f".format(scale * 100)}%",
                    color = ZColors.NikonYellow,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            ZOutlineButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
            )
        }
    }
}

// ---------------- 数值表与格式化 ----------------

private val shutterValues = floatArrayOf(
    1f / 4000, 1f / 2000, 1f / 1000, 1f / 500, 1f / 250, 1f / 125, 1f / 60,
    1f / 30, 1f / 15, 1f / 8, 1f / 4, 1f / 2, 1f, 2f, 4f, 8f, 15f, 30f,
)

private val isoValues = intArrayOf(
    100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
    1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000,
    10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200,
)

private fun fStopIndex(f: Float, values: List<Float>): Float {
    var best = 0
    var bestDiff = Float.MAX_VALUE
    values.forEachIndexed { i, v ->
        val d = kotlin.math.abs(v - f)
        if (d < bestDiff) { bestDiff = d; best = i }
    }
    return best.toFloat()
}

private fun shutterIndex(s: Float): Int {
    var best = 0
    var bestDiff = Float.MAX_VALUE
    shutterValues.forEachIndexed { i, v ->
        val d = kotlin.math.abs(v - s)
        if (d < bestDiff) { bestDiff = d; best = i }
    }
    return best
}

private fun isoIndex(iso: Int): Int {
    var best = 0
    var bestDiff = Int.MAX_VALUE
    isoValues.forEachIndexed { i, v ->
        val d = kotlin.math.abs(v - iso)
        if (d < bestDiff) { bestDiff = d; best = i }
    }
    return best
}

private fun shutterDenom(s: Float): Int = if (s <= 0f) 0 else Math.round(1f / s)

private fun fmtF(f: Float): String {
    val r = Math.round(f * 10f) / 10f
    return if (r == Math.floor(r.toDouble()).toFloat()) r.toInt().toString() else "%.1f".format(r)
}

private fun programName(p: Int): String = when (p) {
    1 -> "M"; 2 -> "P"; 3 -> "A"; 4 -> "S"; else -> "A"
}

private fun picCtrlName(v: Int): String = when (v) {
    1 -> "标准"; 2 -> "自然"; 3 -> "鲜艳"; 4 -> "单色"
    5 -> "人像"; 6 -> "风景"; 7 -> "平直"; 8 -> "自动"
    else -> "标准"
}

/**
 * 未连接时的连接条：直接在主页面就能连相机，不用先跳到调试台。
 *
 * 连接是监看的前置条件，把入口放在需要它的地方，比藏在第四个 tab 里合理。
 */
@Composable
private fun ConnectBar(
    ui: UiState,
    vm: MainViewModel,
    onRequestUsbPermission: (android.hardware.usb.UsbDevice) -> Unit,
) {
    val busy = ui.hotspotScanBusy || ui.wifiConnectBusy || ui.usbConnectBusy
    var showIdentityDialog by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1C1A0E)).padding(10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("连接 Nikon 相机", color = ZColors.NikonYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("自动识别有线 / 手机热点", color = ZColors.TextTertiary, fontSize = 10.sp)
                }
                ZPrimaryButton(
                    text = when {
                        ui.usbConnectBusy || ui.wifiConnectBusy -> "连接中…"
                        ui.hotspotScanBusy -> "扫描中…"
                        else -> "连接"
                    },
                    onClick = { vm.connectAutomatically(onRequestUsbPermission) },
                    enabled = !busy,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "插入相机数据线，或让相机加入手机热点并停在 Wi-Fi STA 连接页面，再点击连接。使用相机现有连接配置。",
                color = ZColors.TextSecondary, fontSize = 10.sp, lineHeight = 15.sp,
            )
            if (ui.hotspotScanMessage.isNotBlank()) {
                Text(ui.hotspotScanMessage, color = ZColors.TextSecondary, fontSize = 10.sp,
                    lineHeight = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            if (ui.wifiInitRejected) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZColors.Surface)
                        .border(1.dp, ZColors.Outline, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "身份不匹配修复（当前：${ui.clientIdentityName}）：① 若相机此前与旧版配对过，可点「客户端身份设置」恢复原名称/GUID；② 或在相机菜单进入「网络 → 连接到计算机 → 配对」保持等待画面后再点「连接」自动完成配对。",
                        color = ZColors.NikonYellow,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZOutlineButton(
                            text = "客户端身份设置",
                            enabled = !busy,
                            onClick = { showIdentityDialog = true },
                            modifier = Modifier.weight(1f),
                        )
                        ZOutlineButton(
                            text = "重置自动配对锁",
                            enabled = !busy,
                            onClick = { vm.resetAutoPairingHistory() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (showIdentityDialog) {
                PtpIpClientIdentityDialog(ui = ui, vm = vm, onDismiss = { showIdentityDialog = false })
            }
            ui.usbConnectionCandidates.forEach { dev ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${dev.productName ?: "Nikon"} · PID 0x%04X".format(dev.productId),
                        color = ZColors.TextPrimary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    ZOutlineButton(text = "选择", enabled = !busy, onClick = {
                        if (vm.hasPermission(dev)) vm.connectDevice(dev) else onRequestUsbPermission(dev)
                    })
                }
            }
            if (ui.hotspotCameras.size > 1) {
                ui.hotspotCameras.forEach { candidate ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${candidate.cameraAddress} · ${candidate.interfaceName}", color = ZColors.TextPrimary,
                            fontSize = 10.sp, modifier = Modifier.weight(1f))
                        ZOutlineButton(text = "选择", enabled = !busy, onClick = {
                            vm.connectPhoneHotspotCamera(candidate.cameraAddress, candidate.phoneAddress)
                        })
                    }
                }
            }
        }
    }
}
