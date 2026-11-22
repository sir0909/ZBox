package com.zbox.ai.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.zbox.ai.core.TouchFocusPolicy
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zbox.ai.core.CompositionMode
import com.zbox.ai.core.LocalCompStyle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun Context.activity():Activity? {var c=this;while(c is ContextWrapper){if(c is Activity)return c;c=c.baseContext};return c as? Activity}

/** Non-floating fullscreen window: platform floating Dialog insets must never shrink the shooting surface. */
@Composable internal fun FullscreenViewer(frame:Bitmap,ui:UiState,vm:MainViewModel,onExit:()->Unit){
    val activity=LocalContext.current.activity()?:return
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latestFrame=rememberUpdatedState(frame);val latestUi=rememberUpdatedState(ui);val latestExit=rememberUpdatedState(onExit)
    val parentContext=rememberCompositionContext()
    val dialog=remember(activity){ComponentDialog(activity,android.R.style.Theme_Black_NoTitleBar_Fullscreen)}
    DisposableEffect(activity,lifecycle,dialog){
        val original=activity.requestedOrientation
        activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener{latestExit.value()}
        dialog.setContentView(ComposeView(activity).apply{
            setParentCompositionContext(parentContext)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent{BackHandler{latestExit.value()};FullscreenCameraSurface(latestFrame.value,latestUi.value,vm){latestExit.value()}}
        })
        dialog.show()
        dialog.window?.let{w->
            w.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK));w.decorView.setPadding(0,0,0,0)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            WindowCompat.setDecorFitsSystemWindows(w,false)
            if(android.os.Build.VERSION.SDK_INT>=28)w.attributes=w.attributes.apply{layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES}
            val controller=WindowInsetsControllerCompat(w,w.decorView)
            controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)latestExit.value()}
        lifecycle.addObserver(observer)
        onDispose{vm.cancelFocusModeChange();lifecycle.removeObserver(observer);dialog.setOnCancelListener(null);dialog.dismiss();activity.requestedOrientation=original}
    }
    DisposableEffect(dialog,ui.settings.keepScreenOn){
        if(ui.settings.keepScreenOn)dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose{}
    }
}

@Composable private fun FullscreenCameraSurface(frame:Bitmap,ui:UiState,vm:MainViewModel,onExit:()->Unit){
    var exposure by remember{mutableStateOf(false)};var poses by remember{mutableStateOf(false)};var menu by remember{mutableStateOf(false)}
    var showFocus by remember{mutableStateOf(false)}
    LaunchedEffect(ui.touchFocusRequest){if(ui.touchFocusRequest>0){showFocus=true;delay(2000);showFocus=false}}
    BackHandler(enabled=exposure){exposure=false}
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("fullscreen-camera")){
        val aspect=frame.width.toFloat()/frame.height
        val pictureWidth=minOf(maxWidth,maxHeight*aspect);val pictureHeight=minOf(maxHeight,maxWidth/aspect)
        val sideInset=(maxWidth-pictureWidth)/2
        val controlRailWidth=96.dp
        // Image and assistance own the full viewport; controls never reserve an opaque side column.
        Image(frame.asImageBitmap(),"横屏全屏取景",Modifier.fillMaxSize().pointerInput(frame.width,frame.height,exposure){
            detectTapGestures{point->if(exposure)exposure=false else TouchFocusPolicy.map(point.x,point.y,size.width.toFloat(),size.height.toFloat(),frame.width,frame.height)?.let{vm.focusAtFrame(it.first,it.second)}}
        },contentScale=ContentScale.Fit)
        PhoneAssistOverlay(frame,vm.phone,compactHistogram=true)
        LocalCompositionHudOverlay(frame,ui,vm)
        if (ui.compositionOpen && ui.compositionMode == CompositionMode.AGENT && ui.compositionAdvice != null) {
            val advice = ui.compositionAdvice
            Canvas(Modifier.fillMaxSize()) {
                val scale = minOf(size.width / frame.width, size.height / frame.height)
                val w = frame.width * scale
                val h = frame.height * scale
                val ox = (size.width - w) * 0.5f
                val oy = (size.height - h) * 0.5f
                fun drawNormBox(b: com.zbox.ai.core.CompositionBox, color: Color) {
                    drawRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(ox + b.left * w, oy + b.top * h),
                        size = androidx.compose.ui.geometry.Size((b.right - b.left) * w, (b.bottom - b.top) * h),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                    )
                }
                advice.subject?.let { drawNormBox(it, Color(0xFF67C7FF)) }
                drawNormBox(advice.crop, Color(0xFFFFD35F))
            }
        }
        if (ui.compositionOpen && ui.compositionMode == CompositionMode.LOCAL) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .displayCutoutPadding()
                    .padding(top = 10.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LocalCompStyle.entries.forEach { st ->
                    val on = ui.localCompStyle == st
                    Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .background(if (on) Color(0xFFFFE600).copy(alpha = 0.22f) else Color.Transparent)
                            .clickable { vm.setLocalCompStyle(st) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            st.label,
                            color = if (on) Color(0xFFFFE600) else Color.White.copy(alpha = 0.78f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        if (ui.compositionOpen && ui.compositionMode == CompositionMode.AGENT) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .displayCutoutPadding()
                    .padding(start = 12.dp, bottom = 12.dp)
                    .widthIn(max = 320.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .border(1.dp, Color.White.copy(alpha = 0.20f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    ui.compositionBusy -> Text("AI Agent 构图分析中…", color = Color(0xFFFFE600), fontSize = 12.sp)
                    ui.compositionError.isNotBlank() -> Text(ui.compositionError, color = Color(0xFFFF6B6B), fontSize = 11.sp)
                    ui.compositionAdvice != null -> {
                        val adv = ui.compositionAdvice
                        Text(adv.summary, color = Color.White, fontSize = 12.sp)
                        adv.moves.take(2).forEach { Text("→ $it", color = Color(0xFFFFE600), fontSize = 11.sp) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "重新分析",
                        color = Color(0xFFFFE600),
                        fontSize = 11.sp,
                        modifier = Modifier.clickable(enabled = !ui.compositionBusy) { vm.analyzeComposition() }.padding(vertical = 2.dp),
                    )
                    Text(
                        "关闭",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { vm.clearComposition(close = true) }.padding(vertical = 2.dp),
                    )
                }
            }
        }
        if(exposure)Box(Modifier.align(Alignment.CenterEnd).displayCutoutPadding().padding(end=maxOf(sideInset,controlRailWidth)+8.dp,top=12.dp,bottom=12.dp)
            .width(288.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha=.24f)).padding(10.dp)){
            CameraExposureOverlay(ui,vm)
        }
        Column(Modifier.align(Alignment.TopStart).displayCutoutPadding().padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalAlignment=Alignment.CenterHorizontally){
            IconButton(onClick=onExit,modifier=Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha=.25f))){Icon(Icons.Default.FullscreenExit,"退出全屏",tint=Color.White.copy(alpha=.85f))}
            CameraOverlayButton("姿势",true){exposure=false;menu=false;poses=true}
            CameraOverlayButton("构图",ui.isConnected && !ui.capturing && !ui.movieRecording && !ui.movieBusy,selected=ui.compositionOpen){
                if(ui.compositionOpen) vm.clearComposition(close=true) else vm.openComposition()
            }
        }
        val railPadding=if(sideInset>=controlRailWidth)(sideInset-controlRailWidth)/2 else 0.dp
        Box(Modifier.align(Alignment.CenterEnd).padding(end=railPadding).width(controlRailWidth).fillMaxHeight().displayCutoutPadding()){
            Box(Modifier.align(Alignment.TopCenter).padding(top=40.dp)){CameraOverlayButton("曝光",ui.isConnected && !ui.capturing && !ui.touchFocusBusy,large=true){exposure=!exposure}}
            Box(Modifier.align(Alignment.Center)){ShutterRow(ui,vm,translucent=true)}
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom=28.dp)){CameraOverlayButton("菜单",ui.isConnected && !ui.capturing && !ui.touchFocusBusy,large=true){exposure=false;poses=false;menu=true;vm.refreshCameraMenu()}}
        }
        if(showFocus && ui.touchFocusX>=0f)Canvas(Modifier.fillMaxSize()){
            val scale=minOf(size.width/frame.width,size.height/frame.height);val w=frame.width*scale;val h=frame.height*scale
            val x=(size.width-w)/2+ui.touchFocusX*w;val y=(size.height-h)/2+ui.touchFocusY*h;val half=14.dp.toPx()
            drawRect(if(ui.touchFocusBusy)Color.Yellow.copy(alpha=.85f)else if(ui.touchFocusMoved)Color.White.copy(alpha=.85f)else Color.Red.copy(alpha=.85f),androidx.compose.ui.geometry.Offset(x-half,y-half),androidx.compose.ui.geometry.Size(half*2,half*2),style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
        ui.pendingFocusModePoint?.let{point->AlertDialog(onDismissRequest={vm.cancelFocusModeChange()},title={Text("切换为单点AF？")},
            text={Text("当前机身使用自动区域AF，会由相机选择对焦区域。按点击位置对焦需要改为单点AF；这会更改机身AF区域设置，不改变曝光参数。")},
            confirmButton={TextButton(onClick={vm.cancelFocusModeChange();vm.focusAtFrame(point.first,point.second,allowSingleAreaChange=true)}){Text("切换为单点并对焦")}},
            dismissButton={TextButton(onClick={vm.cancelFocusModeChange()}){Text("取消，不更改")}})}
        if(poses)PoseReferenceDialog(vm.poses,backdrop=frame){poses=false}
        if(menu)CameraIMenuSheet(frame,ui,vm,compact=true,landscapePanel=true){menu=false}
    }
}

@Composable private fun CameraOverlayButton(label:String,enabled:Boolean,large:Boolean=false,selected:Boolean=false,onClick:()->Unit){
    OutlinedButton(onClick=onClick,enabled=enabled,modifier=if(large)Modifier.size(width=76.dp,height=52.dp)else Modifier.size(width=60.dp,height=40.dp),
        contentPadding=PaddingValues(4.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        border=BorderStroke(1.dp,if(selected)Color(0xFFFFE600).copy(alpha=.85f) else Color.White.copy(alpha=.22f)),
        colors=ButtonDefaults.outlinedButtonColors(containerColor=if(selected)Color(0xFFFFE600).copy(alpha=.16f) else Color.Black.copy(alpha=.18f),contentColor=if(selected)Color(0xFFFFE600) else Color.White.copy(alpha=.82f),disabledContainerColor=Color.Black.copy(alpha=.10f),disabledContentColor=Color.White.copy(alpha=.3f))){
        Text(label,fontSize=if(large)14.sp else 12.sp,maxLines=1)
    }
}
